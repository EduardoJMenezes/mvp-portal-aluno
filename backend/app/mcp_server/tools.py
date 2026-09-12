"""Tools MCP da plataforma (seção 17 do MVP).

Poucas tools, orientadas ao domínio, com nomes que o professor reconheceria.
Nenhuma delas fala com o banco: todas chamam os mesmos application services
que o portal REST chama (seção 19).
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from typing import Annotated, Any

import anyio
from fastmcp import Context
from fastmcp.exceptions import ToolError
from fastmcp.server.elicitation import AcceptedElicitation
from mcp_types import ElicitRequest, ElicitRequestFormParams, InputRequiredResult
from pydantic import Field
from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.errors import AprovacaoNecessaria, ErroDominio
from app.identidade import Identidade
from app.mcp_server.auth import identidade_da_sessao
from app.mcp_server.server import mcp
from app.integracoes.vimeo import VimeoErro
from app.services import (
    analytics,
    catalogo,
    publicacao,
    rascunhos,
    simulados,
    vimeo_importacao,
)
from app.vimeo.client import get_cliente_vimeo

logger = logging.getLogger("plataforma.mcp")

SOMENTE_LEITURA = {"read_only_hint": True, "open_world_hint": False}
ESCREVE_RASCUNHO = {"read_only_hint": False, "destructive_hint": False, "open_world_hint": False}


@contextmanager
def _sessao():
    """Sessão + identidade + tradução de erro de domínio para erro de tool.

    O modelo precisa LER o motivo da recusa para se corrigir, então a mensagem
    do domínio vai inteira para o cliente — são mensagens escritas para isso.
    """
    ident = identidade_da_sessao()
    db = SessionLocal()
    try:
        yield db, ident
    except ErroDominio as e:
        db.rollback()
        raise ToolError(str(e)) from e
    finally:
        db.close()


def _em_thread(funcao, *args) -> Any:
    """Roda um trecho síncrono de banco fora do event loop (tools async)."""
    return anyio.to_thread.run_sync(funcao, *args)


# --- consulta ----------------------------------------------------------------


@mcp.tool(name="listar_turmas", annotations=SOMENTE_LEITURA)
def listar_turmas() -> list[dict]:
    """Lista as turmas da plataforma, com quantos alunos e questões cada uma tem.

    Ponto de partida de quase tudo: as demais tools aceitam o NOME da turma
    ("Extensivo 2027"), então normalmente basta chamar esta uma vez para saber
    o que existe.

    Retorna [{id, nome, ano, alunos, questoes_publicadas, questoes_em_rascunho}].
    """
    with _sessao() as (db, ident):
        return catalogo.listar_turmas(db, ident)


@mcp.tool(name="listar_capitulos", annotations=SOMENTE_LEITURA)
def listar_capitulos() -> list[dict]:
    """Lista os capítulos/assuntos disponíveis (ex.: Atomística, Estequiometria).

    Capítulos são compartilhados entre turmas: o que pertence a uma turma é o
    vínculo turma+capítulo+questão, não o capítulo em si.
    """
    with _sessao() as (db, _ident):
        return catalogo.listar_capitulos(db)


@mcp.tool(name="buscar_questoes", annotations=SOMENTE_LEITURA)
def buscar_questoes(
    turma: Annotated[str | None, Field(description="Nome ou id da turma, ex.: 'Extensivo 2027'")] = None,
    capitulo: Annotated[str | None, Field(description="Nome ou id do capítulo, ex.: 'Estequiometria'")] = None,
    status: Annotated[
        str | None, Field(description="'RASCUNHO' ou 'PUBLICADO'; vazio traz os dois")
    ] = None,
    limite: Annotated[int, Field(ge=1, le=200)] = 50,
) -> list[dict]:
    """Busca questões pelo vínculo com a turma e o capítulo.

    O que volta é o que ESTA identidade pode ver — a segregação por turma é
    aplicada no backend, não aqui.

    Cada item traz numero (Q01, Q02...), enunciado, alternativas, gabarito,
    dificuldade, tópico, status e o vídeo do Vimeo associado, quando houver.
    Use o `numero` ao montar simulados; é assim que o professor se refere às
    questões.
    """
    with _sessao() as (db, ident):
        return catalogo.buscar_questoes(db, ident, turma, capitulo, status, limite)


@mcp.tool(name="listar_videos_vimeo", annotations={"read_only_hint": True, "open_world_hint": True})
async def listar_videos_vimeo(
    pasta: Annotated[
        str | None, Field(description="Nome ou id da pasta no Vimeo. Vazio lista todas as pastas.")
    ] = None,
    busca: Annotated[str | None, Field(description="Filtra por texto no título do vídeo")] = None,
    limite: Annotated[int, Field(ge=1, le=100)] = 25,
) -> dict:
    """Consulta o acervo de vídeos no Vimeo do professor.

    Sem `pasta`, devolve a lista de pastas (os capítulos como estão
    organizados no Vimeo) para você escolher uma. Com `pasta`, devolve os
    vídeos daquela pasta: id do Vimeo, título, link, duração e `embed_url`.

    É a porta de entrada do fluxo de importação: leia os vídeos aqui e depois
    chame importar_questoes_vimeo com os que o professor confirmar. Repasse o
    `embed_url` como veio — é ele que faz o vídeo tocar na tela do aluno.
    """
    identidade_da_sessao().exigir_operador()
    cliente = get_cliente_vimeo()
    try:
        if pasta is None:
            pastas = await cliente.listar_pastas()
            return {
                "pastas": [{"id": p.id, "nome": p.nome, "videos": p.total_videos} for p in pastas],
                "dica": "Chame de novo passando `pasta` para ver os vídeos de uma delas.",
            }
        videos = await cliente.listar_videos(pasta, busca, limite)
        return {
            "pasta": pasta,
            "videos": [
                {
                    "vimeo_id": v.id,
                    "titulo": v.titulo,
                    "url": v.url,
                    "embed_url": v.embed_url,
                    "duracao_segundos": v.duracao_segundos,
                }
                for v in videos
            ],
        }
    except ErroDominio as e:
        raise ToolError(str(e)) from e


# --- importação a partir de uma pasta do Vimeo -------------------------------


async def _ler_plano(pasta: str):
    try:
        return await vimeo_importacao.ler_plano(pasta)
    except (ErroDominio, VimeoErro) as e:
        raise ToolError(str(e)) from e


def _avaliar_plano(ident: Identidade, plano, turma, capitulo) -> dict:
    with SessionLocal() as db:
        try:
            return vimeo_importacao.avaliar(db, ident, plano, turma, capitulo)
        except ErroDominio as e:
            raise ToolError(str(e)) from e


def _aplicar_plano(ident: Identidade, plano, turma, capitulo) -> dict:
    with SessionLocal() as db:
        try:
            return vimeo_importacao.aplicar(db, ident, plano, turma, capitulo)
        except ErroDominio as e:
            db.rollback()
            raise ToolError(str(e)) from e


@mcp.tool(name="listar_pastas_vimeo", annotations={"read_only_hint": True, "open_world_hint": True})
async def listar_pastas_vimeo(
    busca: Annotated[
        str | None, Field(description="Filtra pelo nome da pasta, ex.: 'K03', 'QUESTOES' ou '2026'")
    ] = None,
    limite: Annotated[int, Field(ge=1, le=200)] = 60,
) -> dict:
    """Lista as pastas do Vimeo com a hierarquia, para escolher o que importar.

    O acervo tem centenas de pastas em vários níveis (o ano, depois EXTENSIVO,
    depois QUESTÕES APOSTILA, e então K01, K02...). Cada item traz o id, o nome,
    dentro de qual pasta ele está e quantos vídeos tem — contando ou não as
    subpastas. É esse id que `simular_importacao_vimeo` e
    `importar_pasta_vimeo_como_rascunho` pedem.
    """
    identidade_da_sessao().exigir_operador()
    try:
        async with vimeo_importacao.abrir_leitura() as leitura:
            pastas = await leitura.listar_pastas()
    except (ErroDominio, VimeoErro) as e:
        raise ToolError(str(e)) from e

    nomes = {pasta.uri: pasta.nome for pasta in pastas}
    filtradas = [p for p in pastas if not busca or busca.lower() in (p.nome or "").lower()]
    return {
        "total_no_vimeo": len(pastas),
        "mostrando": min(len(filtradas), limite),
        "pastas": [
            {
                "id": pasta.id,
                "nome": pasta.nome,
                "dentro_de": nomes.get(pasta.pai_uri) if pasta.pai_uri else None,
                "videos": pasta.total_videos,
                "videos_com_subpastas": pasta.total_videos_com_subpastas,
                "tem_subpasta": pasta.tem_subpasta,
            }
            for pasta in filtradas[:limite]
        ],
    }


@mcp.tool(name="simular_importacao_vimeo", annotations={"read_only_hint": True, "open_world_hint": True})
async def simular_importacao_vimeo(
    pasta: Annotated[str, Field(description="Id da pasta no Vimeo, vindo de listar_pastas_vimeo")],
    turma: Annotated[str, Field(description="Nome ou id da turma, ex.: 'Extensivo 2026'")],
    capitulo: Annotated[str, Field(description="Nome do capítulo, ex.: 'K03 - Estequiometria'")],
) -> dict:
    """Mostra o que a importação faria, sem gravar nada.

    Devolve, questão por questão, o número lido do título (o acervo usa Q04,
    Q52...) e a confiança dessa leitura, mais os vídeos que já estão no acervo,
    os conflitos de numeração e os avisos — vídeo ainda processando, embed
    restrito a domínios, e assim por diante.

    Mostre este resumo ao professor antes de importar de fato.
    """
    ident = identidade_da_sessao()
    plano = await _ler_plano(pasta)
    return await _em_thread(_avaliar_plano, ident, plano, turma, capitulo)


@mcp.tool(name="listar_rascunhos", annotations=SOMENTE_LEITURA)
def listar_rascunhos(
    status: Annotated[
        str | None, Field(description="'RASCUNHO' (pendentes) ou 'PUBLICADO'")
    ] = "RASCUNHO",
) -> list[dict]:
    """Lista as propostas criadas e ainda não publicadas.

    Use para retomar um rascunho de outra conversa, ou para descobrir o
    `rascunho_id` que publicar_rascunho pede.
    """
    with _sessao() as (db, ident):
        return rascunhos.listar_rascunhos(db, ident, status)


@mcp.tool(name="detalhar_rascunho", annotations=SOMENTE_LEITURA)
def detalhar_rascunho(rascunho_id: int) -> dict:
    """Mostra tudo que um rascunho contém, para o professor revisar antes de aprovar."""
    with _sessao() as (db, ident):
        return rascunhos.detalhar_rascunho(db, ident, rascunho_id)


@mcp.tool(name="buscar_desempenho_aluno", annotations=SOMENTE_LEITURA)
def buscar_desempenho_aluno(
    aluno: Annotated[str, Field(description="Nome, e-mail ou id do aluno, ex.: 'João'")],
    simulado: Annotated[
        str | None, Field(description="Título ou id. Vazio = o simulado mais recente do aluno.")
    ] = None,
) -> dict:
    """Como um aluno foi num simulado: acertos, percentual e o erro questão a questão.

    Traz também `erros_por_topico`, já ordenado — é o material para dizer em
    que assunto o aluno tropeçou. `encontrou_dados: false` significa que ele
    ainda não respondeu nada; não invente números nesse caso.
    """
    with _sessao() as (db, ident):
        return analytics.desempenho_aluno(db, ident, aluno, simulado)


@mcp.tool(name="buscar_estatisticas_simulado", annotations=SOMENTE_LEITURA)
def buscar_estatisticas_simulado(
    simulado: Annotated[str, Field(description="Título ou id do simulado")],
) -> dict:
    """Desempenho da turma inteira num simulado.

    Traz média, resultado por aluno, percentual de acerto por questão com a
    distribuição das alternativas marcadas, e `maior_dificuldade` — a questão
    com pior aproveitamento e o tópico dela.
    """
    with _sessao() as (db, ident):
        return analytics.estatisticas_simulado(db, ident, simulado)


@mcp.tool(name="listar_simulados", annotations=SOMENTE_LEITURA)
def listar_simulados(
    turma: Annotated[str | None, Field(description="Nome ou id da turma")] = None,
) -> list[dict]:
    """Lista simulados, com status (RASCUNHO/PUBLICADO) e quantas tentativas cada um teve."""
    with _sessao() as (db, ident):
        return simulados.listar_simulados(db, ident, turma)


# --- escrita: sempre em rascunho ---------------------------------------------


@mcp.tool(name="criar_questao_rascunho", annotations=ESCREVE_RASCUNHO)
def criar_questao_rascunho(
    turma: Annotated[str, Field(description="Nome ou id da turma, ex.: 'Extensivo 2027'")],
    capitulo: Annotated[str, Field(description="Nome ou id do capítulo, ex.: 'Estequiometria'")],
    enunciado: Annotated[str, Field(description="Texto da questão")],
    alternativas: Annotated[
        dict[str, str], Field(description='As cinco alternativas: {"A": "...", "B": "...", ... "E": "..."}')
    ],
    gabarito: Annotated[str, Field(description="Letra correta: A, B, C, D ou E")],
    topico: Annotated[str | None, Field(description="Assunto, ex.: 'Reagente limitante'")] = None,
    subtopico: str | None = None,
    dificuldade: Annotated[
        str | None, Field(description="FACIL, MEDIA ou DIFICIL (padrão MEDIA)")
    ] = None,
    vimeo_id: Annotated[
        str | None, Field(description="Id do vídeo do Vimeo com a resolução, se houver")
    ] = None,
) -> dict:
    """Cadastra UMA questão como RASCUNHO na turma e no capítulo indicados.

    A questão NÃO fica visível para os alunos: nasce em rascunho e só aparece
    depois que o professor aprovar. Apresente o retorno ao professor e espere
    a decisão dele antes de chamar publicar_rascunho.

    Exige as cinco alternativas (A-E) e o gabarito. Para cadastrar vários
    vídeos de uma vez, prefira importar_questoes_vimeo.
    """
    with _sessao() as (db, ident):
        return rascunhos.criar_questao_rascunho(
            db, ident, turma, capitulo, enunciado, alternativas, gabarito,
            topico, subtopico, dificuldade, vimeo_id,
        )


@mcp.tool(name="importar_questoes_vimeo", annotations=ESCREVE_RASCUNHO)
def importar_questoes_vimeo(
    turma: Annotated[str, Field(description="Turma que vai receber o conteúdo, ex.: 'Extensivo 2027'")],
    capitulo: Annotated[str, Field(description="Capítulo em que as questões entram")],
    videos: Annotated[
        list[dict],
        Field(
            description=(
                "Um item por vídeo. Obrigatório: vimeo_id. Opcionais: titulo, url, "
                "embed_url (repasse o que listar_videos_vimeo devolveu), enunciado, "
                "alternativas ({'A': ...}), gabarito, topico, subtopico, dificuldade. "
                "Sem enunciado, o título do vídeo é usado."
            )
        ),
    ],
) -> dict:
    """Cria, em RASCUNHO, uma questão por vídeo do Vimeo, já vinculada à turma e ao capítulo.

    É o fluxo principal: liste os vídeos com listar_videos_vimeo, confirme com
    o professor quais entram, e chame esta tool com eles. Cada questão fica
    numerada na sequência do capítulo e ligada ao vídeo da resolução.

    Nada é publicado. O retorno traz `questoes`, `videos_associados` e `erros`
    — mostre esse resumo ao professor e pergunte se pode publicar.
    """
    with _sessao() as (db, ident):
        return rascunhos.importar_questoes_vimeo(db, ident, turma, capitulo, videos)


@mcp.tool(
    name="importar_pasta_vimeo_como_rascunho",
    annotations={"read_only_hint": False, "destructive_hint": False, "open_world_hint": True},
)
async def importar_pasta_vimeo_como_rascunho(
    pasta: Annotated[str, Field(description="Id da pasta no Vimeo, vindo de listar_pastas_vimeo")],
    turma: Annotated[str, Field(description="Turma que recebe o capítulo, ex.: 'Extensivo 2026'")],
    capitulo: Annotated[str, Field(description="Nome do capítulo, ex.: 'K03 - Estequiometria'")],
) -> dict:
    """Importa uma pasta inteira do Vimeo como um capítulo, em RASCUNHO.

    Cria o capítulo se ele ainda não existir e uma questão por vídeo, numerada
    pelo número da apostila lido do título — não por uma contagem nossa, para
    que aluno e professor chamem a questão pelo mesmo nome.

    Nada é publicado: o retorno é um rascunho para o professor revisar. Mostre
    o resumo e pergunte antes de chamar publicar_rascunho.

    Rode `simular_importacao_vimeo` antes: é o mesmo trabalho, sem gravar.
    """
    ident = identidade_da_sessao()
    plano = await _ler_plano(pasta)
    return await _em_thread(_aplicar_plano, ident, plano, turma, capitulo)


@mcp.tool(name="criar_simulado_rascunho", annotations=ESCREVE_RASCUNHO)
def criar_simulado_rascunho(
    turma: Annotated[str, Field(description="Turma para a qual o simulado será publicado")],
    titulo: Annotated[str, Field(description="Nome do simulado, ex.: 'Revisão de Estequiometria'")],
    questoes: Annotated[
        list[str],
        Field(description="Números das questões no capítulo (ex.: ['1','3','5'] ou ['Q01','Q03'])"),
    ],
    capitulo: Annotated[
        str | None,
        Field(description="Capítulo de onde vêm as questões, ex.: 'Estequiometria'"),
    ] = None,
) -> dict:
    """Monta um simulado em RASCUNHO com questões já publicadas na turma.

    A numeração recomeça a cada capítulo, então informe `capitulo` quando o
    professor disser "as questões 1, 3 e 5 de Estequiometria" — sem ele, uma
    referência ambígua é recusada em vez de adivinhada.

    Só entram questões que a turma já enxerga e que têm as cinco alternativas:
    uma questão que só tem vídeo de resolução não pode ser respondida.

    O simulado não aparece para os alunos até ser publicado.
    """
    with _sessao() as (db, ident):
        return rascunhos.criar_simulado_rascunho(db, ident, turma, titulo, questoes, capitulo)


# --- publicação: exige aprovação humana --------------------------------------


def _publicar(ident: Identidade, rascunho_id: int) -> dict:
    with SessionLocal() as db:
        return publicacao.publicar_rascunho(db, ident, rascunho_id)


def _resumo(ident: Identidade, rascunho_id: int) -> str:
    with SessionLocal() as db:
        return publicacao.resumo_para_confirmacao(db, ident, rascunho_id)


def _confirmar_e_publicar(ident: Identidade, rascunho_id: int) -> dict:
    """Grava a aprovação vinda da confirmação do usuário e publica, numa transação."""
    with SessionLocal() as db:
        publicacao.registrar_confirmacao_do_cliente_mcp(db, ident, rascunho_id)
        return publicacao.publicar_rascunho(db, ident, rascunho_id)


CHAVE_CONFIRMACAO = "confirmacao_publicacao"

SCHEMA_CONFIRMACAO = {
    "type": "object",
    "properties": {
        "publicar": {
            "type": "boolean",
            "title": "Publicar para os alunos desta turma",
            "description": "Só marque depois de revisar o conteúdo listado acima.",
        }
    },
    "required": ["publicar"],
}


def _pedido_de_confirmacao(mensagem: str, rascunho_id: int) -> InputRequiredResult:
    """Pausa a tool e devolve a decisão ao usuário (SEP-2322).

    Substituto da elicitation iniciada pelo servidor, que a versão 2026-07-28
    do protocolo removeu: a tool devolve o pedido, o cliente mostra o formulário
    ao usuário e re-invoca a tool com a resposta.
    """
    return InputRequiredResult(
        inputRequests={
            CHAVE_CONFIRMACAO: ElicitRequest(
                params=ElicitRequestFormParams(
                    message=mensagem, requestedSchema=SCHEMA_CONFIRMACAO
                )
            )
        },
        requestState=str(rascunho_id),
    )


def _recusado(rascunho_id: int) -> dict:
    return {
        "rascunho_id": rascunho_id,
        "publicado": False,
        "mensagem": (
            "Publicação não confirmada. Nada foi alterado — o rascunho continua "
            "disponível para revisão."
        ),
    }


@mcp.tool(
    name="publicar_rascunho",
    annotations={"read_only_hint": False, "destructive_hint": False, "idempotent_hint": True},
)
async def publicar_rascunho(rascunho_id: int, ctx: Context) -> dict | InputRequiredResult:
    """Publica um rascunho — depois que o professor aprovar, e só então.

    A aprovação não é opcional e não depende de você lembrar de pedir: o
    backend recusa publicar qualquer rascunho sem aprovação humana registrada.
    Esta tool pede a confirmação ao professor pelo próprio cliente e publica se
    ele aceitar.

    Se ele recusar, nada acontece — e o rascunho continua no portal, em
    Admin > Rascunhos, para revisar com calma.

    Publicado, o conteúdo passa a aparecer imediatamente para os alunos daquela
    turma (e só daquela turma).
    """
    ident = identidade_da_sessao()
    alvo = int(ctx.request_state or rascunho_id)

    # Rodada de volta: o professor respondeu ao formulário de confirmação.
    respostas = ctx.input_responses
    if respostas and CHAVE_CONFIRMACAO in respostas:
        resposta = respostas[CHAVE_CONFIRMACAO]
        aceitou = getattr(resposta, "action", None) == "accept" and bool(
            (getattr(resposta, "content", None) or {}).get("publicar")
        )
        if not aceitou:
            return _recusado(alvo)
        try:
            return await _em_thread(_confirmar_e_publicar, ident, alvo)
        except ErroDominio as e:
            raise ToolError(str(e)) from e

    # Primeira rodada. Se um humano já aprovou no portal, publica direto.
    try:
        return await _em_thread(_publicar, ident, alvo)
    except AprovacaoNecessaria:
        pass
    except ErroDominio as e:
        raise ToolError(str(e)) from e

    try:
        resumo = await _em_thread(_resumo, ident, alvo)
    except ErroDominio as e:
        raise ToolError(str(e)) from e
    mensagem = f"Publicar este conteúdo para os alunos?\n\n{resumo}"

    # Clientes anteriores a 2026-07-28 ainda aceitam a elicitation empurrada
    # pelo servidor; nesses, resolvemos na mesma chamada.
    try:
        resposta = await ctx.elicit(
            mensagem, response_type=bool, response_title="Confirmo a publicação"
        )
    except Exception as e:
        logger.info(
            "elicitation direta indisponível (%s); usando o canal guard/return", type(e).__name__
        )
        return _pedido_de_confirmacao(mensagem, alvo)

    if not (isinstance(resposta, AcceptedElicitation) and bool(resposta.data)):
        return _recusado(alvo)

    try:
        return await _em_thread(_confirmar_e_publicar, ident, alvo)
    except ErroDominio as e:
        raise ToolError(str(e)) from e
