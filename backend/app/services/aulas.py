"""Aulas ao vivo: a sala é do Zoom, a porta é nossa.

Três regras sustentam este módulo (ver
[docs/AULAS-AO-VIVO.md](../../../docs/AULAS-AO-VIVO.md)):

* **Quem alcança a aula é o backend que decide** (§11), igual ao material:
  turma inteira ou pessoa a pessoa. O Zoom não sabe quem é aluno.
* **O link de entrar é pessoal e nasce no clique.** Nunca aparece numa
  listagem, e é guardado porque o Zoom só deixa inscrever o mesmo e-mail três
  vezes por dia na mesma reunião.
* **Só mexemos no que é nosso.** Toda chamada ao Zoom leva o
  `zoom_meeting_id` que gravamos ao publicar — a conta é dividida com outra
  plataforma, que tem aula rodando.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import NaoAutorizado, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import Aula, AulaPresenca, Status
from app.services.analytics import resolver_aluno
from app.services.catalogo import ids_das_turmas_do_aluno, resolver_turma
from app.services.consultas import remover, selecionar, tocar

# A porta abre antes de a aula começar e fecha um tempo depois do fim: aluno
# que chega atrasado ainda entra, e link velho não serve de chave.
ABRE_ANTES = timedelta(minutes=15)
FECHA_DEPOIS = timedelta(minutes=30)
DURACAO_MAXIMA = 8 * 60


def _aula(db: Session, aula_id: int | str) -> Aula:
    referencia = str(aula_id).strip()
    alvo = (
        db.scalar(selecionar(Aula).where(Aula.id == int(referencia)))
        if referencia.isdigit()
        else None
    )
    if alvo is None:
        raise NaoEncontrado(f"Aula '{aula_id}' não existe.")
    return alvo


def por_meeting_id(db: Session, meeting_id: str) -> Aula | None:
    """A aula daquela sala do Zoom — ou nada, e aí o aviso não é nosso.

    É o que protege as aulas da outra plataforma: o webhook escuta a conta
    inteira, e o que não está nesta tabela não recebe reação nenhuma.
    """
    return db.scalar(selecionar(Aula).where(Aula.zoom_meeting_id == str(meeting_id)))


def _alcanca(db: Session, ident: Identidade, aula: Aula) -> bool:
    if aula.status != Status.PUBLICADO:
        return False
    if any(a.id == ident.usuario_id for a in aula.alunos):
        return True
    minhas = set(ids_das_turmas_do_aluno(db, ident.usuario_id))
    return any(t.id in minhas for t in aula.turmas)


def exigir_acesso(db: Session, ident: Identidade, aula: Aula) -> None:
    if ident.e_operador or _alcanca(db, ident, aula):
        return
    raise NaoAutorizado(f"'{aula.titulo}' não está liberada para {ident.nome}.")


def _fim(aula: Aula) -> datetime:
    return aula.inicio_em + timedelta(minutes=aula.minutos)


def _estado(aula: Aula, agora: datetime) -> str:
    if aula.status != Status.PUBLICADO:
        return "RASCUNHO"
    if agora > _fim(aula) + FECHA_DEPOIS:
        return "ENCERRADA"
    if agora >= aula.inicio_em - ABRE_ANTES:
        return "ABERTA"
    return "AGENDADA"


def _resumo(aula: Aula, agora: datetime) -> dict:
    return {
        "aula_id": aula.id,
        "titulo": aula.titulo,
        "descricao": aula.descricao,
        "inicio_em": aula.inicio_em.isoformat(),
        "minutos": aula.minutos,
        "status": aula.status,
        "estado": _estado(aula, agora),
        "abre_em": (aula.inicio_em - ABRE_ANTES).isoformat(),
        "grava": aula.gravar,
        "tem_sala": bool(aula.zoom_meeting_id),
        "turmas": [t.nome for t in aula.turmas],
        "alunos": [{"id": a.id, "nome": a.nome, "email": a.email} for a in aula.alunos],
        "gravacao_item_id": aula.gravacao_item_id,
    }


# --- leitura -----------------------------------------------------------------


def listar_aulas(db: Session, ident: Identidade, agora: datetime | None = None) -> list[dict]:
    """Operador vê todas, inclusive rascunho; aluno, só o que o alcança."""
    agora = agora or datetime.now(UTC)
    aulas = list(db.scalars(selecionar(Aula).order_by(Aula.inicio_em.desc())))
    if not ident.e_operador:
        aulas = [a for a in aulas if _alcanca(db, ident, a)]
    return [_resumo(a, agora) for a in aulas]


def detalhar_aula(db: Session, ident: Identidade, aula_id: int | str, agora: datetime | None = None) -> dict:
    aula = _aula(db, aula_id)
    exigir_acesso(db, ident, aula)
    return _resumo(aula, agora or datetime.now(UTC))


# --- o aluno entrando --------------------------------------------------------


def entrar(db: Session, ident: Identidade, aula_id: int | str, zoom, agora: datetime | None = None) -> dict:
    """Confere o acesso e a hora, e devolve o link **daquele** aluno.

    A inscrição no Zoom acontece aqui, no clique, e só uma vez por pessoa: é o
    que espalha as chamadas pelos minutos antes da aula em vez de disparar
    seiscentas de uma vez.
    """
    agora = agora or datetime.now(UTC)
    aula = _aula(db, aula_id)
    exigir_acesso(db, ident, aula)

    if aula.status != Status.PUBLICADO or not aula.zoom_meeting_id:
        raise RegraDeNegocio(f"'{aula.titulo}' ainda não foi aberta pelo professor.")

    estado = _estado(aula, agora)
    if estado == "AGENDADA":
        quando = (aula.inicio_em - ABRE_ANTES).astimezone(UTC).isoformat()
        raise RegraDeNegocio(f"A sala abre 15 minutos antes, às {quando}.")
    if estado == "ENCERRADA":
        raise RegraDeNegocio(f"'{aula.titulo}' já terminou.")

    presenca = db.scalar(
        select(AulaPresenca).where(
            AulaPresenca.aula_id == aula.id, AulaPresenca.usuario_id == ident.usuario_id
        )
    )
    if presenca is None:
        nome, _, sobrenome = (ident.nome or "Aluno").partition(" ")
        link = zoom.inscrever(
            aula.zoom_meeting_id, nome=nome, sobrenome=sobrenome or ".", email=ident.email
        )
        presenca = AulaPresenca(aula_id=aula.id, usuario_id=ident.usuario_id, join_url=link)
        db.add(presenca)
        db.commit()

    return {"aula_id": aula.id, "titulo": aula.titulo, "url": presenca.join_url}


def link_do_professor(db: Session, ident: Identidade, aula_id: int | str, zoom) -> dict:
    """O link de iniciar, buscado na hora — o do Zoom expira em duas horas."""
    ident.exigir_operador()
    aula = _aula(db, aula_id)
    if not aula.zoom_meeting_id:
        raise RegraDeNegocio(f"'{aula.titulo}' ainda não tem sala: publique a aula primeiro.")
    return {"aula_id": aula.id, "url": zoom.link_de_inicio(aula.zoom_meeting_id)}


# --- o professor montando ----------------------------------------------------


def criar_aula(
    db: Session,
    ident: Identidade,
    zoom,
    *,
    titulo: str,
    inicio_em: datetime,
    minutos: int = 60,
    descricao: str = "",
    gravar: bool = True,
    turmas: list[str | int] | None = None,
    alunos: list[str | int] | None = None,
    submodulo_id: int | None = None,
    publicar_gravacao: bool = True,
) -> dict:
    """Nasce em rascunho, **sem sala no Zoom**.

    A sala só é criada ao publicar: rascunho que muda de horário cinco vezes
    não gasta cinco das cem criações diárias que dividimos com a outra
    plataforma.
    """
    ident.exigir_operador()
    titulo = (titulo or "").strip()
    if not titulo:
        raise RegraDeNegocio("A aula precisa de um título.")
    _validar_horario(inicio_em, minutos)

    aula = Aula(
        titulo=titulo,
        descricao=(descricao or "").strip() or None,
        inicio_em=inicio_em,
        minutos=minutos,
        gravar=gravar,
        status=Status.RASCUNHO,
        submodulo_id=submodulo_id,
        publicar_gravacao=publicar_gravacao,
        criado_por_id=ident.usuario_id,
    )
    db.add(aula)
    db.flush()
    _enderecar(db, aula, turmas, alunos)
    db.commit()
    return _resumo(aula, datetime.now(UTC))


def editar_aula(
    db: Session,
    ident: Identidade,
    zoom,
    aula_id: int | str,
    *,
    titulo: str | None = None,
    inicio_em: datetime | None = None,
    minutos: int | None = None,
    status: str | None = None,
    turmas: list[str | int] | None = None,
    alunos: list[str | int] | None = None,
    gravar: bool | None = None,
    submodulo_id: int | None = None,
    publicar_gravacao: bool | None = None,
) -> dict:
    """Título, horário, quem alcança, e abrir ou fechar a sala."""
    ident.exigir_operador()
    aula = _aula(db, aula_id)

    if titulo is not None:
        if not titulo.strip():
            raise RegraDeNegocio("A aula precisa de um título.")
        aula.titulo = titulo.strip()
    if inicio_em is not None:
        aula.inicio_em = inicio_em
    if minutos is not None:
        aula.minutos = minutos
    if gravar is not None:
        aula.gravar = gravar
    if submodulo_id is not None:
        aula.submodulo_id = submodulo_id or None
    if publicar_gravacao is not None:
        aula.publicar_gravacao = publicar_gravacao
    _validar_horario(aula.inicio_em, aula.minutos)

    if turmas is not None or alunos is not None:
        _enderecar(db, aula, turmas, alunos)

    if status is not None:
        _mudar_status(db, aula, str(status).strip().upper(), zoom)
    elif aula.zoom_meeting_id:
        # Sala já aberta: o Zoom precisa saber do novo horário.
        zoom.editar_aula(
            aula.zoom_meeting_id, titulo=aula.titulo, inicio=aula.inicio_em, minutos=aula.minutos
        )

    tocar(aula, ident)
    db.commit()
    return _resumo(aula, datetime.now(UTC))


def remover_aula(db: Session, ident: Identidade, zoom, aula_id: int | str) -> dict:
    """Some do portal e a sala do Zoom é desmarcada — a nossa, pelo id nosso."""
    ident.exigir_operador()
    aula = _aula(db, aula_id)
    if aula.zoom_meeting_id:
        zoom.cancelar_aula(aula.zoom_meeting_id)
        aula.zoom_meeting_id = None
        aula.zoom_join_url = None
    remover(db, ident, aula)
    db.commit()
    return {"aula_id": aula.id, "titulo": aula.titulo, "reversivel": True}


# --- apoio -------------------------------------------------------------------


def _validar_horario(inicio_em: datetime, minutos: int) -> None:
    if inicio_em.tzinfo is None:
        raise RegraDeNegocio("O horário da aula precisa de fuso.")
    if minutos < 5 or minutos > DURACAO_MAXIMA:
        raise RegraDeNegocio(f"Duração fora do razoável: de 5 a {DURACAO_MAXIMA} minutos.")


def _enderecar(
    db: Session, aula: Aula, turmas: list[str | int] | None, alunos: list[str | int] | None
) -> None:
    if turmas is not None:
        aula.turmas = list({t.id: t for t in (resolver_turma(db, r) for r in turmas)}.values())
    if alunos is not None:
        aula.alunos = list({a.id: a for a in (resolver_aluno(db, r) for r in alunos)}.values())
    db.flush()


def _mudar_status(db: Session, aula: Aula, alvo: str, zoom) -> None:
    """Publicar abre a sala no Zoom; tirar do ar desmarca."""
    if alvo not in (Status.RASCUNHO, Status.PUBLICADO):
        raise RegraDeNegocio("Status da aula: RASCUNHO ou PUBLICADO.")

    if alvo == Status.PUBLICADO and aula.status != Status.PUBLICADO:
        if not aula.turmas and not aula.alunos:
            raise RegraDeNegocio(
                f"'{aula.titulo}' não alcança ninguém: escolha uma turma ou um aluno antes de publicar."
            )
        if not aula.zoom_meeting_id:
            sala = zoom.criar_aula(
                titulo=aula.titulo,
                inicio=aula.inicio_em,
                minutos=aula.minutos,
                descricao=aula.descricao or "",
                gravar=aula.gravar,
            )
            aula.zoom_meeting_id = sala["id"]
            aula.zoom_join_url = sala["join_url"]
        aula.publicado_em = datetime.now(UTC)

    if alvo == Status.RASCUNHO and aula.zoom_meeting_id:
        zoom.cancelar_aula(aula.zoom_meeting_id)
        aula.zoom_meeting_id = None
        aula.zoom_join_url = None
        # Link pessoal de sala que não existe mais é lixo que enganaria o aluno.
        db.query(AulaPresenca).filter(AulaPresenca.aula_id == aula.id).delete()

    aula.status = alvo
