"""Tools MCP da plataforma (seção 17 do MVP).

Poucas tools, orientadas ao domínio, com nomes que o professor reconheceria.
Nenhuma delas fala com o banco: todas chamam os mesmos application services
que o portal REST chama (seção 19).
"""

from __future__ import annotations

import functools
import logging
from contextlib import contextmanager
from typing import Annotated, Any

import anyio
from fastmcp import Context
from fastmcp.exceptions import ToolError
from fastmcp.server.elicitation import AcceptedElicitation
from mcp_types import (
    ClientCapabilities,
    ElicitationCapability,
    ElicitRequest,
    ElicitRequestFormParams,
    InputRequiredResult,
)
from pydantic import Field
from sqlalchemy.orm import Session

from app.config import get_settings
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


def _no_banco(ident: Identidade, funcao, *args, **kwargs) -> Any:
    with SessionLocal() as db:
        try:
            return funcao(db, ident, *args, **kwargs)
        except ErroDominio as e:
            db.rollback()
            raise ToolError(str(e)) from e


async def _service(ident: Identidade, funcao, *args, **kwargs) -> Any:
    """Chama um service de dentro de uma tool async: sessão própria, fora do loop."""
    return await anyio.to_thread.run_sync(
        functools.partial(_no_banco, ident, funcao, *args, **kwargs)
    )


async def _resolucao_do_vimeo(vimeo_id: str | None) -> dict | None:
    try:
        return await vimeo_importacao.resolucao(vimeo_id)
    except ErroDominio as e:
        raise ToolError(str(e)) from e


async def _questoes_com_resolucao(questoes: list | None) -> list | None:
    try:
        return await vimeo_importacao.questoes_com_resolucao(questoes)
    except ErroDominio as e:
        raise ToolError(str(e)) from e


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
        return await vimeo_importacao.listar_pastas(busca, limite)
    except (ErroDominio, VimeoErro) as e:
        raise ToolError(str(e)) from e


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
    """Desempenho de quem fez o simulado, somando todas as turmas dele.

    Traz média, resultado por aluno, percentual de acerto por questão com a
    distribuição das alternativas marcadas, e `maior_dificuldade` — a questão
    com pior aproveitamento e o tópico dela. Em branco conta como erro. Com o
    simulado ainda aberto, `parcial: true`: os números mudam até fechar.
    """
    with _sessao() as (db, ident):
        return analytics.estatisticas_simulado(db, ident, simulado)


@mcp.tool(name="listar_simulados", annotations=SOMENTE_LEITURA)
def listar_simulados(
    turma: Annotated[str | None, Field(description="Nome ou id da turma")] = None,
) -> list[dict]:
    """Lista simulados: turmas, agenda, situação e quantos alunos começaram.

    `situacao` é RASCUNHO, AGENDADO (publicado, ainda não abriu), ABERTO ou
    ENCERRADO. Datas saem no horário de Brasília. `tentativas` conta quem
    começou a prova — é quem entra no ranking.
    """
    with _sessao() as (db, ident):
        return simulados.listar_simulados(db, ident, turma)


# --- escrita: sempre em rascunho ---------------------------------------------


@mcp.tool(name="criar_questao_rascunho", annotations=ESCREVE_RASCUNHO)
async def criar_questao_rascunho(
    enunciado: Annotated[str, Field(description="Texto da questão, em Markdown com LaTeX")],
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
    imagem_pendente: Annotated[
        bool, Field(description="true quando há figura que não deu para transcrever")
    ] = False,
) -> dict:
    """Cadastra UMA questão de simulado, avulsa, como RASCUNHO.

    Para montar uma prova, prefira criar_simulado_rascunho com as questões
    novas dentro: é um rascunho só, em vez de um por questão.

    Sem turma: questão não pertence a turma nenhuma — quem pertence é o
    simulado onde ela entra.

    A questão NÃO fica visível para ninguém: nasce em rascunho e só entra no
    acervo depois que o professor aprovar. Apresente o retorno e espere a
    decisão dele antes de chamar publicar_rascunho.
    """
    ident = identidade_da_sessao()
    return await _service(
        ident, rascunhos.criar_questao_rascunho, enunciado, alternativas, gabarito,
        assunto, subassunto, dificuldade, await _resolucao_do_vimeo(vimeo_id), imagem_pendente,
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


@mcp.tool(
    name="criar_simulado_rascunho",
    annotations={"read_only_hint": False, "destructive_hint": False, "open_world_hint": True},
)
async def criar_simulado_rascunho(
    turmas: Annotated[
        list[str], Field(description="Turmas que fazem a prova, ex.: ['Extensivo 2026']")
    ],
    titulo: Annotated[str, Field(description="Nome do simulado, ex.: 'Simulado 30'")],
    questoes: Annotated[
        list[int | dict],
        Field(
            description=(
                "Na ordem da prova. Cada uma é o id de uma questão publicada (buscar_questoes) "
                "ou a questão nova inteira: {enunciado, alternativas: {A..E}, gabarito, "
                "assunto, subassunto, dificuldade, imagem_pendente, numero, vimeo_id}. "
                "`numero` é o da prova, quando não for a posição (ex.: 91 no ENEM)."
            )
        ),
    ],
    abre_em: Annotated[
        str | None, Field(description="Abertura, no horário de Brasília: '2026-10-10T14:00'")
    ] = None,
    fecha_em: Annotated[
        str | None, Field(description="Fechamento, no horário de Brasília: '2026-10-10T18:00'")
    ] = None,
    duracao_minutos: Annotated[
        int | None, Field(ge=1, description="Tempo de prova, contado de quando o aluno começa")
    ] = None,
    pasta_resolucao: Annotated[
        str | None,
        Field(
            description=(
                "Id da pasta do Vimeo com os vídeos de resolução (listar_pastas_vimeo). "
                "Casa pelo número do título: o vídeo Q07 vai para a questão 7."
            )
        ),
    ] = None,
) -> dict:
    """Monta um simulado em RASCUNHO — e as questões novas dele, no mesmo rascunho.

    É o fluxo do print ou do PDF que o professor manda no chat: transcreva as
    questões e crie tudo numa chamada só. Um simulado de 15 questões é um
    preview e um ok, não dezesseis rascunhos.

    Transcreva tudo o que der para replicar em texto: enunciado e alternativas
    em Markdown, tabela como tabela Markdown, fórmula em LaTeX entre $...$.
    Figura sem letras nem números que importem não se transcreve: marque
    `imagem_pendente: true` — o professor anexa pela plataforma, e o simulado
    não publica antes disso. Proponha assunto e sub-assunto de cada questão
    (listar_assuntos): é o que liga o erro do aluno ao vídeo que explica.

    A resolução vem do Vimeo, da pasta que o professor disser — não adivinhe a
    pasta. Agenda é uma janela só (abre_em → fecha_em, horário de Brasília), e o
    tempo de prova conta de quando cada aluno começa; pode ficar para
    editar_simulado, mas sem ela o simulado não publica.

    Nada aparece para os alunos até publicar_rascunho. Mostre o detalhe
    devolvido — questões, gabaritos, resoluções casadas e
    `pendencias_para_publicar` — e espere o ok do professor.
    """
    ident = identidade_da_sessao()
    resolucoes = None
    if pasta_resolucao:
        resolucoes = vimeo_importacao.resolucoes_por_numero(await _ler_plano(pasta_resolucao))
    return await _service(
        ident, rascunhos.criar_simulado_rascunho, turmas, titulo,
        await _questoes_com_resolucao(questoes), abre_em, fecha_em, duracao_minutos, resolucoes,
    )


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


def _cliente_pergunta_ao_professor(ctx: Context) -> bool:
    """Se o cliente declarou que mostra formulário ao usuário (capability `elicitation`).

    Sem ela, nenhum dos dois canais de confirmação chega ao professor: no
    claude.ai o pedido devolvido vira "Error occurred during tool execution", e
    o modelo conclui que o servidor quebrou.
    """
    try:
        sessao = ctx.session
    except RuntimeError:
        return False
    if sessao.check_client_capability(ClientCapabilities(elicitation=ElicitationCapability())):
        return True
    cliente = getattr(getattr(sessao.client_params, "client_info", None), "name", None)
    logger.info("cliente %s não mostra formulário; a aprovação vai para o portal", cliente)
    return False


def _aprovar_no_portal(rascunho_id: int) -> dict:
    base = (get_settings().mcp_base_url or "").rstrip("/")
    onde = f"{base}/rascunhos" if base else "Admin › Rascunhos"
    return {
        "rascunho_id": rascunho_id,
        "publicado": False,
        "aprovar_em": onde,
        "mensagem": (
            "Nada foi publicado. Este aplicativo não mostra o pedido de confirmação ao "
            f"professor, então a aprovação é no portal: {onde}, rascunho #{rascunho_id}, "
            "botão 'Aprovar e publicar'. Repasse isso ao professor em vez de tentar de novo."
        ),
    }


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
    ele aceitar. Em cliente que não mostra esse pedido (o claude.ai, hoje), ela
    devolve onde aprovar no portal: repasse ao professor, sem insistir.

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

    # Cliente que não sabe perguntar nada ao usuário: os dois canais abaixo não
    # chegariam ao professor. A aprovação continua humana — só muda de lugar.
    if not _cliente_pergunta_ao_professor(ctx):
        return _aprovar_no_portal(alvo)

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
