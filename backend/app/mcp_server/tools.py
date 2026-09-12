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

    Retorna [{id, nome, ano, alunos, modulos, itens_publicados, itens_em_rascunho}].
    """
    with _sessao() as (db, ident):
        return catalogo.listar_turmas(db, ident)


@mcp.tool(name="buscar_questoes", annotations=SOMENTE_LEITURA)
def buscar_questoes(
    assunto: Annotated[
        str | None, Field(description="Filtra pela etiqueta, ex.: 'Estequiometria'")
    ] = None,
    status: Annotated[
        str | None, Field(description="'RASCUNHO' ou 'PUBLICADO'; vazio traz os dois")
    ] = None,
    dificuldade: Annotated[str | None, Field(description="FACIL, MEDIA ou DIFICIL")] = None,
    limite: Annotated[int, Field(ge=1, le=200)] = 50,
) -> list[dict]:
    """Busca no acervo de questões de simulado — enunciado, alternativas, gabarito.

    Não confunda com as "questões da apostila": essas são vídeos de resolução
    e moram na árvore do curso, em `listar_modulos`. Aqui está só o que pode
    virar prova.

    Questão não pertence a turma nenhuma — quem pertence é o simulado onde ela
    entra —, então o filtro é por assunto, não por turma. Use o `questao_id`
    ao montar simulados.
    """
    with _sessao() as (db, ident):
        return catalogo.buscar_questoes(db, ident, assunto, status, dificuldade, limite)


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
    chame importar_videos_como_itens com os que o professor confirmar. Repasse o
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


def _avaliar_plano(ident: Identidade, plano, turma, destinos) -> dict:
    with SessionLocal() as db:
        try:
            return vimeo_importacao.avaliar(db, ident, plano, turma, destinos)
        except ErroDominio as e:
            raise ToolError(str(e)) from e


def _aplicar_plano(ident: Identidade, plano, turma, destinos) -> dict:
    with SessionLocal() as db:
        try:
            return vimeo_importacao.aplicar(db, ident, plano, turma, destinos)
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
    destinos: Annotated[
        list[dict],
        Field(
            description=(
                "Mesmo formato de importar_pasta_vimeo_como_rascunho: faixa, "
                "modulo, submodulo e, opcionais, assunto e subassunto."
            )
        ),
    ],
) -> dict:
    """Mostra o que a importação faria, sem gravar nada.

    Devolve, vídeo por vídeo, o número lido do título (o acervo usa Q04,
    Q52...) e a confiança dessa leitura, para onde ele iria, quais números da
    faixa não acharam vídeo, o que já está naquele sub-módulo e os avisos —
    vídeo ainda processando, embed restrito a domínios, e assim por diante.

    Mostre este resumo ao professor antes de importar de fato.
    """
    ident = identidade_da_sessao()
    plano = await _ler_plano(pasta)
    return await _em_thread(_avaliar_plano, ident, plano, turma, destinos)


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
    enunciado: Annotated[str, Field(description="Texto da questão")],
    alternativas: Annotated[
        dict[str, str], Field(description='As cinco alternativas: {"A": "...", "B": "...", ... "E": "..."}')
    ],
    gabarito: Annotated[str, Field(description="Letra correta: A, B, C, D ou E")],
    assunto: Annotated[
        str | None, Field(description="Assunto já cadastrado, ex.: 'Estequiometria'")
    ] = None,
    subassunto: Annotated[
        str | None, Field(description="Sub-assunto, ex.: 'Reagente limitante'")
    ] = None,
    dificuldade: Annotated[
        str | None, Field(description="FACIL, MEDIA ou DIFICIL (padrão MEDIA)")
    ] = None,
    vimeo_id: Annotated[
        str | None, Field(description="Id do vídeo do Vimeo com a resolução, se houver")
    ] = None,
) -> dict:
    """Cadastra UMA questão de simulado como RASCUNHO.

    Sem turma: questão não pertence a turma nenhuma — quem pertence é o
    simulado onde ela entra. O `assunto` é o que liga o erro do aluno aos
    vídeos que explicam aquilo, então vale a pena preencher.

    A questão NÃO fica visível para ninguém: nasce em rascunho e só entra no
    acervo depois que o professor aprovar. Apresente o retorno e espere a
    decisão dele antes de chamar publicar_rascunho.
    """
    with _sessao() as (db, ident):
        return rascunhos.criar_questao_rascunho(
            db, ident, enunciado, alternativas, gabarito,
            assunto, subassunto, dificuldade, vimeo_id,
        )


@mcp.tool(name="importar_videos_como_itens", annotations=ESCREVE_RASCUNHO)
def importar_videos_como_itens(
    turma: Annotated[str, Field(description="Turma que vai receber o conteúdo")],
    modulo: Annotated[str, Field(description="Módulo onde os vídeos entram, ex.: 'K01 - ...'")],
    submodulo: Annotated[str, Field(description="Sub-módulo, ex.: 'Questões da apostila'")],
    videos: Annotated[
        list[dict],
        Field(
            description=(
                "Um item por vídeo. Obrigatório: vimeo_id. Opcionais: titulo, url, "
                "embed_url (repasse o que listar_videos_vimeo devolveu), nome, "
                "assunto, subassunto."
            )
        ),
    ],
) -> dict:
    """Cria, em RASCUNHO, um item por vídeo dentro de um sub-módulo.

    É o CRUD de vídeo do curso: liste com listar_videos_vimeo, confirme com o
    professor quais entram e onde, e chame esta tool. O `nome` do item, sem
    ser informado, vem do título do Vimeo.

    O módulo e o sub-módulo precisam existir — use criar_modulo antes. Nada é
    publicado: o retorno é um rascunho para o professor revisar.
    """
    with _sessao() as (db, ident):
        return rascunhos.importar_videos_como_itens(db, ident, turma, modulo, submodulo, videos)


@mcp.tool(
    name="importar_pasta_vimeo_como_rascunho",
    annotations={"read_only_hint": False, "destructive_hint": False, "open_world_hint": True},
)
async def importar_pasta_vimeo_como_rascunho(
    pasta: Annotated[str, Field(description="Id da pasta no Vimeo, vindo de listar_pastas_vimeo")],
    turma: Annotated[str, Field(description="Turma que recebe o conteúdo, ex.: 'Extensivo 2026'")],
    destinos: Annotated[
        list[dict],
        Field(
            description=(
                "Onde cada faixa de vídeos entra. Um item por destino, com: "
                "faixa ('1-14' ou '15,18,22'; vazio recolhe o resto), modulo, "
                "submodulo e, opcionais, assunto e subassunto."
            )
        ),
    ],
) -> dict:
    """Importa uma pasta do Vimeo distribuindo os vídeos pelos módulos, em RASCUNHO.

    É o gesto que o professor faz em voz alta: "da 1 até a 14 é o K01, sub
    Questões da apostila; 15, 18, 22 e 25 são o K02". A faixa fala dos números
    da **apostila**, lidos do título do vídeo (Q04, Q52) — não da posição na
    lista. Vídeo cujo título não traz número legível só entra num destino sem
    faixa; do contrário sobra, e a tool avisa em vez de chutar.

    Módulos e sub-módulos precisam existir antes (use criar_modulo). Cada
    destino vira um rascunho próprio, para o professor poder liberar um
    módulo e segurar outro.

    Rode `simular_importacao_vimeo` antes: é o mesmo trabalho, sem gravar.
    """
    ident = identidade_da_sessao()
    plano = await _ler_plano(pasta)
    return await _em_thread(_aplicar_plano, ident, plano, turma, destinos)


@mcp.tool(name="criar_simulado_rascunho", annotations=ESCREVE_RASCUNHO)
def criar_simulado_rascunho(
    turma: Annotated[str, Field(description="Turma para a qual o simulado será publicado")],
    titulo: Annotated[str, Field(description="Nome do simulado, ex.: 'Revisão de Estequiometria'")],
    questoes: Annotated[
        list[int],
        Field(description="Ids das questões do acervo, vindos de buscar_questoes"),
    ],
) -> dict:
    """Monta um simulado em RASCUNHO com questões já publicadas no acervo.

    Só entram questões completas — com as cinco alternativas e gabarito. Uma
    prova pela metade não é uma prova.

    O simulado não aparece para os alunos até ser publicado.
    """
    with _sessao() as (db, ident):
        return rascunhos.criar_simulado_rascunho(db, ident, turma, titulo, questoes)


# --- publicação: exige aprovação humana --------------------------------------


def _publicar(ident: Identidade, rascunho_id: int, itens: list[int] | None = None) -> dict:
    with SessionLocal() as db:
        return publicacao.publicar_rascunho(db, ident, rascunho_id, itens)


def _resumo(ident: Identidade, rascunho_id: int) -> str:
    with SessionLocal() as db:
        return publicacao.resumo_para_confirmacao(db, ident, rascunho_id)


def _confirmar_e_publicar(
    ident: Identidade, rascunho_id: int, itens: list[int] | None = None
) -> dict:
    """Grava a aprovação vinda da confirmação do usuário e publica, numa transação."""
    with SessionLocal() as db:
        publicacao.registrar_confirmacao_do_cliente_mcp(db, ident, rascunho_id)
        return publicacao.publicar_rascunho(db, ident, rascunho_id, itens)


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
async def publicar_rascunho(
    rascunho_id: int,
    ctx: Context,
    itens: Annotated[
        list[int] | None,
        Field(
            description=(
                "Ids dos itens a liberar agora (de detalhar_rascunho). "
                "Vazio publica o rascunho inteiro."
            )
        ),
    ] = None,
) -> dict | InputRequiredResult:
    """Publica um rascunho — depois que o professor aprovar, e só então.

    Com `itens`, libera só aqueles e deixa o resto pendente: é assim que se
    publica item a item sem abrir mão da aprovação, que fica gravada no
    rascunho. Sem `itens`, publica tudo de uma vez — o caso da pasta do Vimeo
    importada inteira.

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
            return await _em_thread(_confirmar_e_publicar, ident, alvo, itens)
        except ErroDominio as e:
            raise ToolError(str(e)) from e

    # Primeira rodada. Se um humano já aprovou no portal, publica direto.
    try:
        return await _em_thread(_publicar, ident, alvo, itens)
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
        return await _em_thread(_confirmar_e_publicar, ident, alvo, itens)
    except ErroDominio as e:
        raise ToolError(str(e)) from e
