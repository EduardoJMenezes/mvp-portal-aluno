"""Materiais em PDF e o que cada aluno risca em cima deles.

Três regras sustentam este módulo (ver [docs/MATERIAIS.md](../../../docs/MATERIAIS.md)):

* **Quem alcança o material é o backend que decide** (§11). O aluno vê o que
  está publicado e endereçado a uma turma dele ou a ele mesmo — não existe
  listagem que o frontend filtre depois.
* **A anotação é privada.** Ela é sempre a de quem está pedindo; não há como
  passar o id de outra pessoa, nem sendo o professor.
* **O arquivo sai em fatias.** O leitor pede uma faixa de bytes e o Postgres
  devolve só ela, com `substring` sobre a coluna sem compressão.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.errors import NaoAutorizado, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import Material, MaterialAnotacao, Status
from app.services.analytics import resolver_aluno
from app.services.catalogo import ids_das_turmas_do_aluno, resolver_turma
from app.services.consultas import remover, selecionar, tocar

LIMITE_DO_ARQUIVO = 60 * 1024 * 1024
LIMITE_DA_ANOTACAO = 200 * 1024
TIPO = "application/pdf"


def _material(db: Session, material_id: int | str) -> Material:
    referencia = str(material_id).strip()
    alvo = (
        db.scalar(selecionar(Material).where(Material.id == int(referencia)))
        if referencia.isdigit()
        else None
    )
    if alvo is None:
        disponiveis = ", ".join(
            f"#{m.id} {m.titulo}" for m in db.scalars(selecionar(Material).order_by(Material.id))
        )
        raise NaoEncontrado(
            f"Material '{material_id}' não existe. Materiais: {disponiveis or '(nenhum)'}."
        )
    return alvo


def _alcanca(db: Session, ident: Identidade, material: Material) -> bool:
    """O material chega a quem pergunta? Publicado, e endereçado à turma ou à pessoa."""
    if material.status != Status.PUBLICADO:
        return False
    if any(a.id == ident.usuario_id for a in material.alunos):
        return True
    minhas = set(ids_das_turmas_do_aluno(db, ident.usuario_id))
    return any(t.id in minhas for t in material.turmas)


def exigir_acesso(db: Session, ident: Identidade, material: Material) -> None:
    if ident.e_operador or _alcanca(db, ident, material):
        return
    raise NaoAutorizado(f"'{material.titulo}' não está liberado para {ident.nome}.")


def _resumo(material: Material, anotadas: int | None = None) -> dict:
    dados = {
        "material_id": material.id,
        "titulo": material.titulo,
        "arquivo": material.arquivo_nome,
        "tamanho": material.tamanho,
        "status": material.status,
        "turmas": [t.nome for t in material.turmas],
        "alunos": [{"id": a.id, "nome": a.nome, "email": a.email} for a in material.alunos],
        "criado_em": material.criado_em.isoformat() if material.criado_em else None,
        "publicado_em": material.publicado_em.isoformat() if material.publicado_em else None,
    }
    if anotadas is not None:
        dados["paginas_anotadas"] = anotadas
    return dados


# --- leitura -----------------------------------------------------------------


def listar_materiais(db: Session, ident: Identidade) -> list[dict]:
    """Operador vê todos, inclusive os rascunhos; aluno, só o que o alcança."""
    materiais = list(db.scalars(selecionar(Material).order_by(Material.criado_em.desc())))
    if not ident.e_operador:
        materiais = [m for m in materiais if _alcanca(db, ident, m)]

    anotadas = dict(
        db.execute(
            select(MaterialAnotacao.material_id, func.count(MaterialAnotacao.id))
            .where(MaterialAnotacao.usuario_id == ident.usuario_id)
            .group_by(MaterialAnotacao.material_id)
        ).all()
    )
    return [_resumo(m, anotadas.get(m.id, 0)) for m in materiais]


def detalhar_material(db: Session, ident: Identidade, material_id: int | str) -> dict:
    material = _material(db, material_id)
    exigir_acesso(db, ident, material)
    return _resumo(material)


def abrir_arquivo(db: Session, ident: Identidade, material_id: int | str) -> Material:
    """O material, já conferido o acesso — quem serve o arquivo pede por aqui."""
    material = _material(db, material_id)
    exigir_acesso(db, ident, material)
    return material


def fatia(db: Session, material: Material, inicio: int, tamanho: int) -> bytes:
    """Uma faixa de bytes do PDF — o que o leitor pede para abrir uma página.

    `substring` sobre a coluna sem compressão faz o Postgres ler só o pedaço;
    sem isso, cada página custaria descomprimir o arquivo inteiro.
    """
    if tamanho <= 0:
        return b""
    return (
        db.scalar(
            select(func.substring(Material.conteudo, inicio + 1, tamanho)).where(
                Material.id == material.id
            )
        )
        or b""
    )


def pedacos(material_id: int, total: int, tamanho: int = 1 << 20):
    """O arquivo inteiro, em pedaços de 1 MB, para quem pediu sem `Range`.

    Abre a própria sessão de banco: o corpo de uma resposta em streaming é
    gerado **depois** que a dependência do FastAPI fechou a sessão da
    requisição. O acesso já foi conferido em `abrir_arquivo`.
    """
    from app.db import SessionLocal

    with SessionLocal() as db:
        material = db.get(Material, material_id)
        lido = 0
        while material is not None and lido < total:
            trecho = fatia(db, material, lido, min(tamanho, total - lido))
            if not trecho:
                return
            lido += len(trecho)
            yield trecho


# --- o professor publicando --------------------------------------------------


def criar_material(
    db: Session,
    ident: Identidade,
    titulo: str,
    conteudo: bytes,
    arquivo_nome: str | None = None,
    turmas: list[str | int] | None = None,
    alunos: list[str | int] | None = None,
) -> dict:
    """Recebe o PDF e o guarda em rascunho: nenhum aluno vê antes de publicar."""
    ident.exigir_operador()
    titulo = (titulo or "").strip()
    if not titulo:
        raise RegraDeNegocio("O material precisa de um título.")
    _validar_pdf(conteudo)

    material = Material(
        titulo=titulo,
        arquivo_nome=(arquivo_nome or "").strip()[:200] or None,
        tipo=TIPO,
        tamanho=len(conteudo),
        conteudo=conteudo,
        status=Status.RASCUNHO,
        criado_por_id=ident.usuario_id,
    )
    db.add(material)
    db.flush()
    _enderecar(db, material, turmas, alunos)
    db.commit()
    return _resumo(material)


def editar_material(
    db: Session,
    ident: Identidade,
    material_id: int | str,
    titulo: str | None = None,
    status: str | None = None,
    turmas: list[str | int] | None = None,
    alunos: list[str | int] | None = None,
) -> dict:
    """Título, quem alcança, e publicar ou tirar do ar."""
    ident.exigir_operador()
    material = _material(db, material_id)

    if titulo is not None:
        if not titulo.strip():
            raise RegraDeNegocio("O material precisa de um título.")
        material.titulo = titulo.strip()

    if turmas is not None or alunos is not None:
        _enderecar(db, material, turmas, alunos)

    if status is not None:
        alvo = str(status).strip().upper()
        if alvo not in (Status.RASCUNHO, Status.PUBLICADO):
            raise RegraDeNegocio(f"Situação '{status}' inválida. Use RASCUNHO ou PUBLICADO.")
        if alvo == Status.PUBLICADO and not (material.turmas or material.alunos):
            raise RegraDeNegocio(
                f"'{material.titulo}' não chegaria a ninguém: escolha uma turma ou um aluno "
                "antes de publicar."
            )
        material.status = alvo
        material.publicado_em = datetime.now(UTC) if alvo == Status.PUBLICADO else None

    tocar(ident, material)
    db.commit()
    return _resumo(material)


def remover_material(db: Session, ident: Identidade, material_id: int | str) -> dict:
    """Remoção lógica: some da tela do aluno, e o que ele riscou continua guardado."""
    ident.exigir_operador()
    material = _material(db, material_id)
    remover(db, ident, material)
    db.commit()
    return {"material_id": material.id, "titulo": material.titulo, "reversivel": True}


def _validar_pdf(conteudo: bytes) -> None:
    if not conteudo:
        raise RegraDeNegocio("Arquivo vazio.")
    if len(conteudo) > LIMITE_DO_ARQUIVO:
        raise RegraDeNegocio(
            f"O arquivo tem {len(conteudo) / 1024 / 1024:.1f} MB; o limite é "
            f"{LIMITE_DO_ARQUIVO // 1024 // 1024} MB."
        )
    if not conteudo.startswith(b"%PDF-"):
        raise RegraDeNegocio("Só entra PDF aqui.")


def _enderecar(
    db: Session,
    material: Material,
    turmas: list[str | int] | None,
    alunos: list[str | int] | None,
) -> None:
    """Troca a lista de quem alcança. Vazia dos dois lados é ninguém — e aí não publica."""
    if turmas is not None:
        material.turmas = list(
            {t.id: t for t in (resolver_turma(db, ref) for ref in turmas)}.values()
        )
    if alunos is not None:
        material.alunos = list(
            {a.id: a for a in (resolver_aluno(db, ref) for ref in alunos)}.values()
        )
    db.flush()


# --- o que o aluno risca -----------------------------------------------------


def anotacoes(db: Session, ident: Identidade, material_id: int | str) -> dict:
    """As páginas que **quem está pedindo** já riscou. Nunca as de outra pessoa."""
    material = _material(db, material_id)
    exigir_acesso(db, ident, material)
    linhas = db.scalars(
        select(MaterialAnotacao)
        .where(
            MaterialAnotacao.material_id == material.id,
            MaterialAnotacao.usuario_id == ident.usuario_id,
        )
        .order_by(MaterialAnotacao.pagina)
    ).all()
    return {
        "material_id": material.id,
        "paginas": {
            str(linha.pagina): linha.dados
            for linha in linhas
            if linha.dados and linha.dados.get("tracos")
        },
    }


def salvar_anotacao(
    db: Session, ident: Identidade, material_id: int | str, pagina: int, dados: dict
) -> dict:
    """Grava uma página. É o que o salvamento automático chama, e ele chama muito."""
    material = _material(db, material_id)
    exigir_acesso(db, ident, material)
    if pagina < 1:
        raise RegraDeNegocio("Página inválida.")

    tracos = (dados or {}).get("tracos")
    if not isinstance(tracos, list):
        raise RegraDeNegocio('A anotação vai como {"v": 1, "tracos": [...]}.')
    if len(json.dumps(tracos, separators=(",", ":"))) > LIMITE_DA_ANOTACAO:
        raise RegraDeNegocio(
            f"A anotação desta página passou de {LIMITE_DA_ANOTACAO // 1024} KB. "
            "Apague alguns traços antes de continuar."
        )

    linha = db.scalar(
        select(MaterialAnotacao).where(
            MaterialAnotacao.material_id == material.id,
            MaterialAnotacao.usuario_id == ident.usuario_id,
            MaterialAnotacao.pagina == pagina,
        )
    )
    if linha is None:
        linha = MaterialAnotacao(
            material_id=material.id, usuario_id=ident.usuario_id, pagina=pagina
        )
        db.add(linha)
    linha.dados = {"v": 1, "tracos": tracos}
    linha.atualizado_em = datetime.now(UTC)
    db.commit()
    return {"material_id": material.id, "pagina": pagina, "tracos": len(tracos)}
