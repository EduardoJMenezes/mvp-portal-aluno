"""Confirmação humana pelo cliente MCP, para as tools que alteram o curso.

Publicar exige aprovação **gravada no banco** (`drafts.aprovado_por_id`): o
backend recusa publicar sem ela, e nenhuma tool pode contornar isso. Editar e
remover são diretos — não passam por rascunho —, e aí a confirmação daqui é a
única barreira antes da alteração acontecer.

Por isso ela é obrigatória e **fail-safe**: cliente que não sabe confirmar
recebe uma recusa, nunca a alteração aplicada em silêncio. É a diferença entre
uma proteção e uma boa intenção.

O canal é o `InputRequiredResult` (SEP-2322): a tool devolve o pedido, o
cliente mostra o formulário, e a tool é re-invocada com a resposta. Clientes
anteriores a 2026-07-28 ainda aceitam a elicitation empurrada pelo servidor, e
os dois caminhos continuam implementados — não simplifique para um só.
"""

from __future__ import annotations

import logging

from fastmcp import Context
from fastmcp.server.elicitation import AcceptedElicitation
from mcp_types import ElicitRequest, ElicitRequestFormParams, InputRequiredResult

logger = logging.getLogger("plataforma.mcp")

CHAVE_CONFIRMACAO = "confirmacao"


def _schema(titulo: str, descricao: str) -> dict:
    return {
        "type": "object",
        "properties": {
            "confirmar": {
                "type": "boolean",
                "title": titulo,
                "description": descricao,
            }
        },
        "required": ["confirmar"],
    }


def pedido(mensagem: str, estado: str, titulo: str, descricao: str) -> InputRequiredResult:
    """Pausa a tool e devolve a decisão ao usuário."""
    return InputRequiredResult(
        inputRequests={
            CHAVE_CONFIRMACAO: ElicitRequest(
                params=ElicitRequestFormParams(
                    message=mensagem, requestedSchema=_schema(titulo, descricao)
                )
            )
        },
        requestState=estado,
    )


def resposta_do_usuario(ctx: Context, chave: str = CHAVE_CONFIRMACAO) -> bool | None:
    """Lê a resposta da rodada de volta. `None` quando ainda não houve pergunta."""
    respostas = ctx.input_responses
    if not respostas or chave not in respostas:
        return None
    resposta = respostas[chave]
    if getattr(resposta, "action", None) != "accept":
        return False
    conteudo = getattr(resposta, "content", None) or {}
    return bool(conteudo.get("confirmar") or conteudo.get("publicar"))


async def perguntar(
    ctx: Context, mensagem: str, estado: str, titulo: str, descricao: str
) -> bool | InputRequiredResult:
    """Pergunta ao usuário, pelo caminho que o cliente souber falar.

    Devolve `True`/`False` quando a resposta veio na hora, ou o pedido para o
    cliente quando ele usa o canal de duas rodadas.
    """
    try:
        resposta = await ctx.elicit(mensagem, response_type=bool, response_title=titulo)
    except Exception as e:
        logger.info(
            "elicitation direta indisponível (%s); usando o canal guard/return", type(e).__name__
        )
        return pedido(mensagem, estado, titulo, descricao)

    return isinstance(resposta, AcceptedElicitation) and bool(resposta.data)


def recusa(o_que: str) -> dict:
    return {
        "aplicado": False,
        "mensagem": f"{o_que} não confirmado. Nada foi alterado.",
    }
