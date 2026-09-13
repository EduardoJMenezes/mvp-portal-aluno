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

**Publicar é item a item, sem abrir buraco nessa regra.** `publicar_rascunho`
aceita `itens_ids`: o professor aprova o rascunho uma vez e libera os itens
quando quiser, e o rascunho só se dá por publicado quando não sobra item
pendente. Sem `itens_ids`, publica tudo de uma vez — é a publicação em lote,
que é o caso da importação de uma pasta inteira do Vimeo.

O que **não** existe é um caminho que mude `Item.status` para PUBLICADO sem
passar por um rascunho aprovado. Editar e remover são diretos (ver
`estrutura.py`); publicar, não.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AprovacaoNecessaria, ErroDominio, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import LETRAS, Item, Questao, Rascunho, Simulado, Status
from app.services.simulados import em_brasilia, pendencias_para_publicar


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


def _itens_do_rascunho(db: Session, rascunho_id: int, apenas_pendentes: bool = False) -> list[Item]:
    # Sem filtro de remoção de propósito: um item removido depois de proposto
    # não volta a existir por causa de uma publicação.
    consulta = select(Item).where(Item.rascunho_id == rascunho_id, Item.removido_em.is_(None))
    if apenas_pendentes:
        consulta = consulta.where(Item.status == Status.RASCUNHO)
    return list(db.scalars(consulta.order_by(Item.ordem)))


def aprovar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Aprovação feita por um professor logado no portal.

    Não faz commit: a aprovação é gravada junto com a publicação que ela
    autoriza (ver `aprovar_e_publicar`).
    """
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Aprovar um rascunho")

    r = _carregar(db, rascunho_id)
    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} já foi publicado.")

    _marcar_aprovado(db, r, ident, ViaAprovacao.PORTAL)
    db.flush()
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
    if d.get("modulo"):
        linhas.append(f"Módulo: {d['modulo']} › {d.get('submodulo', '')}".rstrip(" ›"))

    for item in d.get("itens", []):
        marca = "  (já publicado)" if item["status"] == Status.PUBLICADO else ""
        linhas.append(f"  {item['ordem']:>2}. {item['nome'][:70]}{marca}")

    for q in d.get("questoes", []):
        marca = "" if q["completa"] else "  (sem alternativas A-E)"
        linhas.append(f"  {q['enunciado'][:70]}{marca}")

    simulado = d.get("simulado")
    if simulado:
        linhas.append(f"Turmas: {', '.join(simulado['turmas'])}")
        linhas.append(
            f"Abre {simulado['abre_em'] or '(sem data)'}, fecha {simulado['fecha_em'] or '(sem data)'}, "
            f"{simulado['duracao_minutos'] or '?'} min de prova"
        )
        for q in simulado["questoes"]:
            linhas.append(f"  {q['ordem']}. {q['enunciado'][:70]}")

    linhas.append("")
    linhas.append("Publicar torna este conteúdo visível para os alunos da turma.")
    return "\n".join(linhas)


def publicar_rascunho(
    db: Session,
    ident: Identidade,
    rascunho_id: int,
    itens_ids: list[int] | None = None,
) -> dict:
    """Publica um rascunho JÁ aprovado por um humano.

    Com `itens_ids`, libera só aqueles itens e deixa o rascunho aberto para o
    resto. Sem, publica tudo que está pendente nele.

    Levanta AprovacaoNecessaria se ninguém aprovou — é este erro que a borda
    MCP usa como gatilho para pedir a confirmação ao usuário.
    """
    ident.exigir_operador()
    r = _carregar(db, rascunho_id)

    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} já foi publicado por inteiro.")

    if r.aprovado_por_id is None:
        raise AprovacaoNecessaria(
            f"O rascunho {rascunho_id} não tem aprovação humana registrada e não pode ser "
            "publicado. Peça a confirmação do professor, ou aprove pelo portal em "
            f"Admin > Rascunhos > #{rascunho_id}."
        )

    pendentes = _itens_do_rascunho(db, r.id, apenas_pendentes=True)
    questoes = db.scalars(
        select(Questao).where(Questao.rascunho_id == r.id, Questao.removido_em.is_(None))
    ).all()
    simulado = db.scalar(
        select(Simulado).where(Simulado.rascunho_id == r.id, Simulado.removido_em.is_(None))
    )

    if not pendentes and not questoes and simulado is None:
        raise RegraDeNegocio(f"Rascunho {rascunho_id} está vazio; não há o que publicar.")

    if itens_ids is not None:
        escolhidos = [i for i in pendentes if i.id in set(itens_ids)]
        desconhecidos = set(itens_ids) - {i.id for i in escolhidos}
        if desconhecidos:
            disponiveis = ", ".join(f"{i.id} ({i.nome})" for i in pendentes) or "nenhum"
            raise RegraDeNegocio(
                f"Itens {sorted(desconhecidos)} não estão pendentes neste rascunho. "
                f"Pendentes: {disponiveis}."
            )
    else:
        escolhidos = pendentes

    agora = datetime.now(UTC)

    # Antes de mudar qualquer status: simulado com pendência não vai ao ar, e
    # a prova sem agenda ou com figura faltando não pode chegar ao aluno.
    if simulado is not None and itens_ids is None:
        pendencias = pendencias_para_publicar(simulado, agora)
        if pendencias:
            raise RegraDeNegocio(
                f"'{simulado.titulo}' ainda não pode ser publicado: {'; '.join(pendencias)}. "
                "Peça ao Claude para acertar isso e publique de novo."
            )

    for item in escolhidos:
        item.status = Status.PUBLICADO
        item.alterado_por_id = ident.usuario_id
        item.alterado_em = agora

    # Questão e simulado continuam sendo tudo ou nada: uma prova pela metade
    # não é uma prova.
    publicou_resto = itens_ids is None
    if publicou_resto:
        for q in questoes:
            q.status = Status.PUBLICADO
        if simulado is not None:
            simulado.status = Status.PUBLICADO
            simulado.publicado_em = agora

    sobraram = [i for i in pendentes if i not in escolhidos]
    if not sobraram and publicou_resto:
        r.status = Status.PUBLICADO
        r.publicado_em = agora
    db.commit()

    incompletas = sum(1 for q in questoes if len(q.alternativas) < len(LETRAS))
    return {
        "rascunho_id": r.id,
        "publicado": r.status == Status.PUBLICADO,
        "turma": r.turma.nome if r.turma else None,
        "itens_publicados": len(escolhidos),
        "itens_ainda_pendentes": len(sobraram),
        "questoes_publicadas": len(questoes) if publicou_resto else 0,
        "questoes_sem_alternativas": incompletas if publicou_resto else 0,
        "simulado_publicado": simulado.titulo if simulado and publicou_resto else None,
        "aprovado_por": r.aprovado_por.nome,
        "aprovado_via": r.aprovado_via,
        "mensagem": (
            f"Publicado. '{simulado.titulo}' abre em {em_brasilia(simulado.abre_em)} e fecha em "
            f"{em_brasilia(simulado.fecha_em)} para {', '.join(x.nome for x in simulado.turmas)}."
            if simulado and publicou_resto
            else f"Publicado. O conteúdo já aparece para os alunos de "
            f"{r.turma.nome if r.turma else 'sua turma'}."
            + (f" Sobraram {len(sobraram)} item(ns) neste rascunho." if sobraram else "")
        ),
    }


def aprovar_e_publicar(
    db: Session, ident: Identidade, rascunho_id: int, itens_ids: list[int] | None = None
) -> dict:
    """Um clique no portal: o professor revisou, aprova e publica — tudo ou nada.

    Recusada a publicação (um simulado com pendência, por exemplo), a aprovação
    sai junto. Ela valia para publicar aquela versão, naquela hora: se ficasse
    gravada, o rascunho corrigido depois pelo MCP iria ao ar sem ninguém ter
    visto a correção.
    """
    try:
        aprovar_rascunho(db, ident, rascunho_id)
        return publicar_rascunho(db, ident, rascunho_id, itens_ids)
    except ErroDominio:
        db.rollback()
        raise


def descartar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    """Joga fora uma proposta que o professor não quis.

    Aqui o DELETE é físico, e de propósito: conteúdo publicado tem remoção
    lógica porque alguém pode precisar dele de volta, mas um rascunho recusado
    nunca existiu para ninguém. Guardá-lo só encheria a lista de rascunhos de
    coisa que o professor já disse que não quer.
    """
    ident.exigir_operador()
    ident.exigir_humano_no_portal("Descartar um rascunho")

    r = _carregar(db, rascunho_id)
    if r.status == Status.PUBLICADO:
        raise RegraDeNegocio("Rascunho já publicado não pode ser descartado.")

    publicados = [i for i in _itens_do_rascunho(db, r.id) if i.status == Status.PUBLICADO]
    if publicados:
        raise RegraDeNegocio(
            f"Este rascunho já teve {len(publicados)} item(ns) publicado(s) e não pode ser "
            "descartado. Remova os itens que não quiser mais — a remoção é reversível."
        )

    simulado = db.scalar(select(Simulado).where(Simulado.rascunho_id == r.id))
    if simulado is not None:
        db.delete(simulado)
    for item in db.scalars(select(Item).where(Item.rascunho_id == r.id)).all():
        db.delete(item)
    for q in db.scalars(select(Questao).where(Questao.rascunho_id == r.id)).all():
        db.delete(q)

    # O flush força os filhos a saírem antes do pai: sem ele o SQLAlchemy pode
    # emitir o DELETE do rascunho primeiro e esbarrar na FK.
    db.flush()
    db.delete(r)
    db.commit()
    return {"rascunho_id": rascunho_id, "descartado": True}
