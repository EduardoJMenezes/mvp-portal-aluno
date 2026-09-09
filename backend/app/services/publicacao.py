"""Aprovação e publicação de rascunhos.

Esta é a metade "humano aprova, backend publica" da regra da seção 6, e ela
não depende de o modelo se comportar bem:

* `publicar_rascunho` recusa qualquer rascunho sem aprovação humana gravada em
  `drafts.aprovado_por_id`. É uma checagem de estado no banco, não uma
  instrução no prompt — um agente que "esquecer" de pedir confirmação leva
  AprovacaoNecessaria na cara e não publica nada.
* Só existem dois jeitos de gravar essa aprovação, e os dois exigem um humano:
  o professor clicando no portal (`aprovar_rascunho`, que barra o canal MCP),
  ou o professor respondendo à elicitation do cliente MCP
  (`registrar_confirmacao_do_cliente_mcp`, que o servidor chama sozinho depois
  do aceite — não há tool exposta para isso).
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AprovacaoNecessaria, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import LETRAS, Questao, Rascunho, Simulado, Status, TurmaQuestao


class ViaAprovacao:
    PORTAL = "PORTAL"
    ELICITATION_MCP = "ELICITATION_MCP"


def _carregar(db: Session, rascunho_id: int) -> Rascunho:
    r = db.get(Rascunho, rascunho_id)
    if r is None:
        raise NaoEncontrado(f"Rascunho {rascunho_id} não existe.")
    return r


def _marcar_aprovado(db: Session, r: Rascunho, ident: Identidade, via: str) -> None:
    r.aprovado_por_id = ident.usuario_id
    r.aprovado_em = datetime.now(UTC)
    r.aprovado_via = via


def aprovar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Aprovação feita por um professor logado no portal."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Aprovar um rascunho")

    r = _carregar(db, rascunho_id)
    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} já foi publicado.")

    _marcar_aprovado(db, r, ident, ViaAprovacao.PORTAL)
    db.commit()
    return {"rascunho_id": r.id, "aprovado_por": ident.nome, "via": r.aprovado_via}


def registrar_confirmacao_do_cliente_mcp(db: Session, ident: Identidade, rascunho_id: int) -> None:
    """Grava a aprovação obtida via elicitation do cliente MCP.

    Chamada apenas pelo servidor MCP, depois de o usuário aceitar a
    confirmação — nunca por uma tool. O modelo não tem como invocar isto: o
    que ele controla é o pedido de publicação, e o aceite vem do cliente.
    """
    r = _carregar(db, rascunho_id)
    _marcar_aprovado(db, r, ident, ViaAprovacao.ELICITATION_MCP)
    db.flush()


def resumo_para_confirmacao(db: Session, ident: Identidade, rascunho_id: int) -> str:
    """Texto que o humano lê antes de decidir. Sempre gerado pelo backend."""
    from app.services.rascunhos import detalhar_rascunho

    d = detalhar_rascunho(db, ident, rascunho_id)
    linhas = [d["resumo"]]
    if d.get("turma"):
        linhas.append(f"Turma: {d['turma']}")
    if d.get("capitulo"):
        linhas.append(f"Capítulo: {d['capitulo']}")

    for q in d.get("questoes", []):
        marca = "" if q["completa"] else "  (sem alternativas A-E)"
        video = f" — vídeo {q['video']['vimeo_id']}" if q["video"] else ""
        linhas.append(f"  Q{q['numero']:02d} {q['enunciado'][:70]}{video}{marca}")

    simulado = d.get("simulado")
    if simulado:
        for q in simulado["questoes"]:
            linhas.append(f"  {q['ordem']}. {q['enunciado'][:70]}")

    linhas.append("")
    linhas.append("Publicar torna este conteúdo visível para os alunos da turma.")
    return "\n".join(linhas)


def publicar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Publica um rascunho JÁ aprovado por um humano.

    Levanta AprovacaoNecessaria se ninguém aprovou — é este erro que a borda
    MCP usa como gatilho para pedir a confirmação ao usuário.
    """
    ident.exigir_operador()
    r = _carregar(db, rascunho_id)

    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} já foi publicado.")

    if r.aprovado_por_id is None:
        raise AprovacaoNecessaria(
            f"O rascunho {rascunho_id} não tem aprovação humana registrada e não pode ser "
            "publicado. Peça a confirmação do professor, ou aprove pelo portal em "
            f"Admin > Rascunhos > #{rascunho_id}."
        )

    vinculos = db.scalars(select(TurmaQuestao).where(TurmaQuestao.rascunho_id == r.id)).all()
    questoes = db.scalars(select(Questao).where(Questao.rascunho_id == r.id)).all()
    simulado = db.scalar(select(Simulado).where(Simulado.rascunho_id == r.id))

    if not vinculos and simulado is None:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} está vazio; não há o que publicar.")

    agora = datetime.now(UTC)
    for v in vinculos:
        v.status = Status.PUBLICADO
    for q in questoes:
        q.status = Status.PUBLICADO
    if simulado is not None:
        if not simulado.questoes:
            raise RegraDeNegocio("Simulado sem questões; não é possível publicar.")
        simulado.status = Status.PUBLICADO
        simulado.publicado_em = agora

    r.status = Status.PUBLICADO
    r.publicado_em = agora
    db.commit()

    incompletas = sum(1 for q in questoes if len(q.alternativas) < len(LETRAS))
    return {
        "rascunho_id": r.id,
        "publicado": True,
        "turma": r.turma.nome if r.turma else None,
        "questoes_publicadas": len(vinculos),
        "questoes_sem_alternativas": incompletas,
        "simulado_publicado": simulado.titulo if simulado else None,
        "aprovado_por": r.aprovado_por.nome,
        "aprovado_via": r.aprovado_via,
        "mensagem": (
            f"Publicado. O conteúdo já aparece para os alunos de "
            f"{r.turma.nome if r.turma else 'sua turma'}."
        ),
    }


def aprovar_e_publicar(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Um clique no portal: o professor revisou, aprova e publica."""
    aprovar_rascunho(db, ident, rascunho_id)
    return publicar_rascunho(db, ident, rascunho_id)


def descartar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Joga fora uma proposta que o professor não quis."""
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Descartar um rascunho")

    r = _carregar(db, rascunho_id)
    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio("Rascunho já publicado não pode ser descartado.")

    simulado = db.scalar(select(Simulado).where(Simulado.rascunho_id == r.id))
    if simulado is not None:
        db.delete(simulado)
    for v in db.scalars(select(TurmaQuestao).where(TurmaQuestao.rascunho_id == r.id)).all():
        db.delete(v)
    for q in db.scalars(select(Questao).where(Questao.rascunho_id == r.id)).all():
        db.delete(q)

    # O flush força os filhos a saírem antes do pai: sem ele o SQLAlchemy pode
    # emitir o DELETE do rascunho primeiro e esbarrar na FK.
    db.flush()
    db.delete(r)
    db.commit()
    return {"rascunho_id": rascunho_id, "descartado": True}
