"""Contas do portal: entrar, trocar a senha, alunos da turma e tokens do MCP.

As regras de segurança moram aqui, e não na tela:

* **O login não diz se o e-mail existe.** "E-mail ou senha incorretos" nos dois
  casos, com o mesmo custo de bcrypt — senão o tempo de resposta contaria.
* **Tentativa demais trava**, por conta e por IP, numa janela deslizante.
* **Senha que outra pessoa definiu é temporária.** O professor cadastra o aluno
  ou redefine a senha dele; até o aluno trocar, a sessão só serve para trocar
  a senha (quem barra é `api/deps.py`, para toda rota).
* **Trocar a senha derruba as sessões antigas** (`senha_alterada_em`).
* **Senha e token só nascem pelo navegador.** Um agente com token do MCP não
  cria conta, não redefine senha nem emite outro token para si.
"""

from __future__ import annotations

import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import UTC, datetime
from functools import cache

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.errors import (
    CredenciaisInvalidas,
    MuitasTentativas,
    NaoAutorizado,
    NaoEncontrado,
    RegraDeNegocio,
)
from app.identidade import Identidade
from app.models import Matricula, Papel, TokenMCP, Turma, Usuario
from app.security import confere_senha, hash_senha, hash_token, novo_token_mcp
from app.services.analytics import resolver_aluno
from app.services.catalogo import resolver_turma

# --- limite de tentativas ----------------------------------------------------

JANELA_SEGUNDOS = 15 * 60
FALHAS_POR_CONTA = 5
FALHAS_POR_IP = 20


class _Falhas:
    """Falhas recentes por chave, numa janela deslizante.

    ponytail: memória do processo. Com mais de uma instância cada uma conta
    sozinha — aí o contador vai para o Postgres ou para um Redis.
    """

    def __init__(self, limite: int) -> None:
        self.limite = limite
        self._falhas: dict[str, deque[float]] = defaultdict(deque)
        self._trava = threading.Lock()

    def _recentes(self, chave: str, agora: float) -> deque[float]:
        fila = self._falhas[chave]
        while fila and agora - fila[0] >= JANELA_SEGUNDOS:
            fila.popleft()
        return fila

    def espera(self, chave: str, agora: float) -> int:
        """Segundos até poder tentar de novo; 0 quando está livre."""
        with self._trava:
            fila = self._recentes(chave, agora)
            if len(fila) < self.limite:
                if not fila:
                    del self._falhas[chave]  # chave velha não fica ocupando memória
                return 0
            return max(1, int(JANELA_SEGUNDOS - (agora - fila[0])))

    def registrar(self, chave: str, agora: float) -> None:
        with self._trava:
            self._recentes(chave, agora).append(agora)

    def zerar(self, chave: str) -> None:
        with self._trava:
            self._falhas.pop(chave, None)

    def esquecer_tudo(self) -> None:
        with self._trava:
            self._falhas.clear()


_por_conta = _Falhas(FALHAS_POR_CONTA)
_por_ip = _Falhas(FALHAS_POR_IP)


def esquecer_tentativas() -> None:
    """Zera os contadores. Existe para os testes não herdarem trava um do outro."""
    _por_conta.esquecer_tudo()
    _por_ip.esquecer_tudo()


@cache
def _hash_de_ninguem() -> str:
    """Hash de uma senha que ninguém tem: e-mail inexistente custa o mesmo bcrypt."""
    return hash_senha(secrets.token_urlsafe(16))


# --- entrar ------------------------------------------------------------------


def entrar(db: Session, email: str, senha: str, ip: str, agora: float | None = None) -> Usuario:
    """Confere e-mail e senha. Mesmo erro e mesmo custo, exista a conta ou não."""
    agora = time.monotonic() if agora is None else agora
    conta = (email or "").strip().lower()

    espera = max(_por_conta.espera(conta, agora), _por_ip.espera(ip, agora))
    if espera:
        raise MuitasTentativas(espera)

    usuario = db.scalar(select(Usuario).where(Usuario.email == conta))
    senha_confere = confere_senha(senha, usuario.senha_hash if usuario else _hash_de_ninguem())
    if usuario is None or not senha_confere:
        _por_conta.registrar(conta, agora)
        _por_ip.registrar(ip, agora)
        raise CredenciaisInvalidas()

    _por_conta.zerar(conta)
    return usuario


def perfil(db: Session, usuario: Usuario) -> dict:
    """O que a tela sabe de quem entrou. Nada de hash, nada de token."""
    turmas = db.scalars(
        select(Turma.nome)
        .join(Matricula, Matricula.turma_id == Turma.id)
        .where(Matricula.usuario_id == usuario.id, Turma.removido_em.is_(None))
        .order_by(Turma.nome)
    ).all()
    return {
        "id": usuario.id,
        "nome": usuario.nome,
        "email": usuario.email,
        "papel": usuario.papel,
        "turmas": list(turmas),
        "trocar_senha": usuario.senha_temporaria,
    }


# --- senha -------------------------------------------------------------------

SENHA_MINIMA = 10
# bcrypt só lê os primeiros 72 bytes; acima disso a biblioteca recusa.
SENHA_MAXIMA_BYTES = 72
_OBVIAS = {
    "0123456789", "1234567890", "12345678910", "0987654321", "1111111111",
    "qwertyuiop", "asdfghjkl1", "senha12345", "senha123456", "minhasenha",
    "password12", "password123", "abcdefghij", "abc1234567", "demo123456",
}


def validar_nova_senha(senha: str, usuario: Usuario) -> None:
    """Tamanho e o óbvio. Sem regra de maiúscula e símbolo: comprimento protege mais."""
    if len(senha) < SENHA_MINIMA:
        raise RegraDeNegocio(f"A senha precisa de pelo menos {SENHA_MINIMA} caracteres.")
    if len(senha.encode()) > SENHA_MAXIMA_BYTES:
        raise RegraDeNegocio("A senha pode ter no máximo 72 bytes (cerca de 70 letras).")

    minuscula = senha.lower()
    local = usuario.email.split("@")[0].lower()
    nomes = [parte for parte in usuario.nome.lower().split() if len(parte) >= 4]
    if (
        minuscula in _OBVIAS
        or len(set(minuscula)) <= 2
        or (len(local) >= 4 and local in minuscula)
        or any(parte in minuscula for parte in nomes)
    ):
        raise RegraDeNegocio(
            "Essa senha é fácil de adivinhar. Evite o próprio nome, o e-mail e "
            "sequências como 1234567890."
        )


def trocar_senha(db: Session, ident: Identidade, senha_atual: str, nova: str) -> Usuario:
    usuario = db.get(Usuario, ident.usuario_id)
    if usuario is None:
        raise NaoEncontrado("Esta conta não existe mais.")
    if not confere_senha(senha_atual, usuario.senha_hash):
        raise RegraDeNegocio("A senha atual não confere.")
    if senha_atual == nova:
        raise RegraDeNegocio("A nova senha precisa ser diferente da atual.")
    validar_nova_senha(nova, usuario)

    usuario.senha_hash = hash_senha(nova)
    usuario.senha_temporaria = False
    usuario.senha_alterada_em = datetime.now(UTC)
    db.commit()
    return usuario


_ALFABETO = "abcdefghjkmnpqrstuvwxyz23456789"


def _senha_temporaria() -> str:
    """12 caracteres sem os que se confundem (l e 1, o e 0): cerca de 59 bits."""
    return "".join(secrets.choice(_ALFABETO) for _ in range(12))


# --- alunos da turma ---------------------------------------------------------


def _aluno(usuario: Usuario) -> dict:
    return {
        "id": usuario.id,
        "nome": usuario.nome,
        "email": usuario.email,
        "senha_temporaria": usuario.senha_temporaria,
        "criado_em": usuario.criado_em.isoformat() if usuario.criado_em else None,
    }


def alunos_da_turma(db: Session, ident: Identidade, turma: str | int) -> dict:
    ident.exigir_operador()
    alvo = resolver_turma(db, turma)
    alunos = db.scalars(
        select(Usuario)
        .join(Matricula, Matricula.usuario_id == Usuario.id)
        .where(Matricula.turma_id == alvo.id)
        .order_by(Usuario.nome)
    ).all()
    return {"turma_id": alvo.id, "turma": alvo.nome, "alunos": [_aluno(a) for a in alunos]}


def matricular(db: Session, ident: Identidade, turma: str | int, nome: str, email: str) -> dict:
    """Matricula pelo e-mail. Aluno novo nasce com senha temporária, que só aparece aqui."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Cadastrar aluno")
    alvo = resolver_turma(db, turma)
    email = (email or "").strip().lower()

    usuario = db.scalar(select(Usuario).where(Usuario.email == email))
    senha = None
    if usuario is None:
        nome = (nome or "").strip()
        if not nome:
            raise RegraDeNegocio("Informe o nome do aluno: é o que aparece no ranking e no portal.")
        senha = _senha_temporaria()
        usuario = Usuario(
            nome=nome, email=email, senha_hash=hash_senha(senha), papel=Papel.ALUNO,
            senha_temporaria=True,
        )
        db.add(usuario)
        db.flush()
    elif usuario.papel != Papel.ALUNO:
        raise RegraDeNegocio(f"{email} é a conta de um {usuario.papel}, não de um aluno.")

    ja_matriculado = db.scalar(
        select(Matricula).where(Matricula.usuario_id == usuario.id, Matricula.turma_id == alvo.id)
    )
    if ja_matriculado is not None:
        raise RegraDeNegocio(f"{usuario.nome} já está em {alvo.nome}.")

    db.add(Matricula(usuario_id=usuario.id, turma_id=alvo.id))
    db.commit()
    return {"turma": alvo.nome, "aluno": _aluno(usuario), "senha_temporaria": senha}


def desmatricular(db: Session, ident: Identidade, turma: str | int, aluno: str | int) -> dict:
    """Tira da turma. A matrícula é vínculo, não conteúdo: sai de verdade, e o
    que o aluno já fez (tentativas, respostas) continua no histórico."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Tirar aluno da turma")
    alvo = resolver_turma(db, turma)
    usuario = resolver_aluno(db, aluno)
    matricula = db.scalar(
        select(Matricula).where(Matricula.usuario_id == usuario.id, Matricula.turma_id == alvo.id)
    )
    if matricula is None:
        raise NaoEncontrado(f"{usuario.nome} não está em {alvo.nome}.")
    db.delete(matricula)
    db.commit()
    return {"turma": alvo.nome, "aluno": usuario.nome, "removido": True}


def redefinir_senha(db: Session, ident: Identidade, aluno: str | int) -> dict:
    """Nova senha temporária para um aluno. As sessões dele caem na hora."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Redefinir senha de aluno")
    usuario = resolver_aluno(db, aluno)
    senha = _senha_temporaria()
    usuario.senha_hash = hash_senha(senha)
    usuario.senha_temporaria = True
    usuario.senha_alterada_em = datetime.now(UTC)
    db.commit()
    return {"aluno": _aluno(usuario), "senha_temporaria": senha}


# --- tokens do MCP -----------------------------------------------------------


def _token(t: TokenMCP) -> dict:
    return {
        "id": t.id,
        "nome": t.nome,
        "criado_em": t.criado_em.isoformat() if t.criado_em else None,
        "ultimo_uso_em": t.ultimo_uso_em.isoformat() if t.ultimo_uso_em else None,
        "revogado": t.revogado,
    }


def tokens_do_operador(db: Session, ident: Identidade) -> list[dict]:
    """Os tokens de quem pergunta — só os dele, e nunca o valor."""
    ident.exigir_operador()
    return [
        _token(t)
        for t in db.scalars(
            select(TokenMCP)
            .where(TokenMCP.usuario_id == ident.usuario_id)
            .order_by(TokenMCP.criado_em.desc(), TokenMCP.id.desc())
        )
    ]


def emitir_token(db: Session, ident: Identidade, nome: str) -> dict:
    """O valor em claro sai uma vez, nesta resposta; o banco guarda só o hash."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Emitir token do MCP")
    valor = novo_token_mcp()
    token = TokenMCP(
        usuario_id=ident.usuario_id,
        nome=((nome or "").strip() or "Claude")[:120],
        token_hash=hash_token(valor),
    )
    db.add(token)
    db.commit()
    return {**_token(token), "token": valor}


def revogar_token(db: Session, ident: Identidade, token_id: int) -> dict:
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Revogar token do MCP")
    token = db.get(TokenMCP, token_id)
    if token is None or token.usuario_id != ident.usuario_id:
        raise NaoEncontrado("Token não encontrado entre os seus.")
    token.revogado = True
    db.commit()
    return _token(token)


# --- modo demonstração -------------------------------------------------------

DOMINIOS_DEMO = ("@escola.demo", "@aluno.demo")


def contas_demo(db: Session) -> list[dict]:
    usuarios = db.scalars(
        select(Usuario)
        .where(or_(*(Usuario.email.endswith(dominio) for dominio in DOMINIOS_DEMO)))
        .order_by(Usuario.papel, Usuario.nome)
    ).all()
    return [
        {k: v for k, v in perfil(db, u).items() if k in ("nome", "email", "papel", "turmas")}
        for u in usuarios
    ]


def entrar_como_demo(db: Session, email: str) -> Usuario:
    """Só as contas de exemplo do seed. Quem decide se o modo está ligado é a rota."""
    email = (email or "").strip().lower()
    if not email.endswith(DOMINIOS_DEMO):
        raise NaoAutorizado("Só as contas de demonstração entram sem senha.")
    usuario = db.scalar(select(Usuario).where(Usuario.email == email))
    if usuario is None:
        raise NaoEncontrado("Essa conta de demonstração não existe neste banco.")
    return usuario
