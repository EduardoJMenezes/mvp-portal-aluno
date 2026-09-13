"""API do professor/gerenciador.

Mesmíssimos services que as tools do MCP chamam — o que muda é só a borda.
Toda operação do MCP tem aqui o endpoint equivalente: é a API que o portal
novo (Next.js) vai consumir, com o cliente tipado gerado do `openapi.json`
(docs/MODELO-SIMULADO.md). As referências aceitam id ou nome, como nas tools.

O portal atual só lê. Os endpoints de escrita existem para o front novo e para
sessões do Claude Code — que entram com o token do MCP, e por isso não aprovam
nem descartam rascunho (ver `deps._pelo_token_do_mcp`).
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, File, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
from sqlalchemy.orm import Session

from app.api.deps import operador_atual
from app.db import get_db
from app.identidade import Identidade
from app.services import (
    analytics,
    catalogo,
    estrutura,
    publicacao,
    questoes,
    rascunhos,
    simulados,
    taxonomia,
    vimeo_importacao,
)
from app.vimeo.client import get_cliente_vimeo

router = APIRouter(prefix="/api/admin", tags=["admin"], dependencies=[Depends(operador_atual)])

Operador = Depends(operador_atual)
Banco = Depends(get_db)

TURMA = "/turmas/{turma}"
MODULO = TURMA + "/modulos/{modulo}"
SUBMODULO = MODULO + "/submodulos/{submodulo}"


# --- consulta ----------------------------------------------------------------


@router.get("/turmas")
def turmas(ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    return catalogo.listar_turmas(db, ident)


@router.get("/modulos")
def modulos(turma: str | None = None, ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    """A árvore do curso: módulos, sub-módulos e itens de cada turma."""
    return catalogo.listar_modulos(db, ident, turma)


@router.get("/assuntos")
def assuntos(db: Session = Banco) -> list[dict]:
    return taxonomia.listar_assuntos(db)


@router.get("/questoes")
def lista_questoes(
    assunto: str | None = None,
    status: str | None = None,
    dificuldade: str | None = None,
    limite: int = 200,
    ident: Identidade = Operador,
    db: Session = Banco,
) -> list[dict]:
    """Acervo de questões de simulado — não as questões da apostila, que são
    vídeos e aparecem em /modulos."""
    return catalogo.buscar_questoes(db, ident, assunto, status, dificuldade, limite)


@router.get("/alunos/{aluno}/desempenho")
def desempenho(
    aluno: str, simulado: str | None = None, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return analytics.desempenho_aluno(db, ident, aluno, simulado)


# --- curso: módulo, sub-módulo e item ----------------------------------------


class ModuloIn(BaseModel):
    nome: str
    submodulos: list[str] | None = None


class EdicaoModuloIn(BaseModel):
    nome: str | None = None
    ordem: int | None = None


class SubModuloIn(BaseModel):
    nome: str


class EdicaoItemIn(BaseModel):
    nome: str | None = None
    ordem: int | None = None
    mover_para_submodulo: str | None = None


class ItensIn(BaseModel):
    videos: list[dict] = Field(
        description="Um por vídeo: vimeo_id e, opcionais, titulo, embed_url, nome, assunto."
    )


class ClassificacaoIn(BaseModel):
    assunto: str
    subassunto: str | None = None
    itens: str | None = Field(None, description="'Q01-Q03' ou 'Q04,Q07'; vazio = todos")


@router.post(TURMA + "/modulos")
def criar_modulo(turma: str, dados: ModuloIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return estrutura.criar_modulo_na_turma(db, ident, turma, dados.nome, dados.submodulos)


@router.patch(MODULO)
def editar_modulo(
    turma: str, modulo: str, dados: EdicaoModuloIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return estrutura.editar_modulo_da_turma(db, ident, turma, modulo, dados.nome, dados.ordem)


@router.delete(MODULO)
def remover_modulo(turma: str, modulo: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return estrutura.remover_do_curso(db, ident, turma, modulo)


@router.post(MODULO + "/submodulos")
def criar_submodulo(
    turma: str, modulo: str, dados: SubModuloIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return estrutura.criar_submodulo_no_modulo(db, ident, turma, modulo, dados.nome)


@router.delete(SUBMODULO)
def remover_submodulo(
    turma: str, modulo: str, submodulo: str, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return estrutura.remover_do_curso(db, ident, turma, modulo, submodulo)


@router.post(SUBMODULO + "/itens")
def importar_itens(
    turma: str,
    modulo: str,
    submodulo: str,
    dados: ItensIn,
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """Vídeos entrando no sub-módulo — em rascunho, como pela tool."""
    return rascunhos.importar_videos_como_itens(db, ident, turma, modulo, submodulo, dados.videos)


@router.patch(SUBMODULO + "/itens/{item}")
def editar_item(
    turma: str,
    modulo: str,
    submodulo: str,
    item: str,
    dados: EdicaoItemIn,
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    return estrutura.editar_item_do_curso(
        db, ident, turma, modulo, submodulo, item, dados.nome, dados.ordem,
        dados.mover_para_submodulo,
    )


@router.delete(SUBMODULO + "/itens/{item}")
def remover_item(
    turma: str, modulo: str, submodulo: str, item: str, ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    return estrutura.remover_do_curso(db, ident, turma, modulo, submodulo, item)


@router.post(SUBMODULO + "/classificacao")
def classificar_videos(
    turma: str,
    modulo: str,
    submodulo: str,
    dados: ClassificacaoIn,
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    return estrutura.classificar_itens(
        db, ident, turma, modulo, submodulo, dados.assunto, dados.subassunto, dados.itens
    )


class AssuntoIn(BaseModel):
    nome: str
    subassuntos: list[str] | None = None


@router.post("/assuntos")
def cadastrar_assunto(dados: AssuntoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return taxonomia.cadastrar_assunto(db, ident, dados.nome, dados.subassuntos)


# --- Vimeo -------------------------------------------------------------------


class ImportacaoIn(BaseModel):
    pasta: str
    turma: str
    destinos: list[dict] = Field(
        description="Onde cada faixa entra: faixa ('1-14'), modulo, submodulo, assunto, subassunto."
    )


@router.get("/vimeo/pastas")
async def vimeo_pastas(busca: str | None = None, limite: int = 60) -> dict:
    return await vimeo_importacao.listar_pastas(busca, limite)


@router.get("/vimeo/videos")
async def vimeo_videos(pasta: str | None = None, busca: str | None = None, limite: int = 25) -> list[dict]:
    cliente = get_cliente_vimeo()
    return [v.__dict__ for v in await cliente.listar_videos(pasta, busca, limite)]


@router.post("/vimeo/importacoes/simulacao")
async def simular_importacao(dados: ImportacaoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """O que a importação faria, sem gravar nada."""
    plano = await vimeo_importacao.ler_plano(dados.pasta)
    return await run_in_threadpool(
        vimeo_importacao.avaliar, db, ident, plano, dados.turma, dados.destinos
    )


@router.post("/vimeo/importacoes")
async def importar_pasta(dados: ImportacaoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """A pasta distribuída pelos módulos, em rascunho — um por destino."""
    plano = await vimeo_importacao.ler_plano(dados.pasta)
    return await run_in_threadpool(
        vimeo_importacao.aplicar, db, ident, plano, dados.turma, dados.destinos
    )


# --- rascunhos ---------------------------------------------------------------


class PublicacaoIn(BaseModel):
    itens: list[int] | None = Field(None, description="Itens a liberar agora; vazio publica tudo")


@router.get("/rascunhos")
def lista_rascunhos(status: str | None = None, ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    return rascunhos.listar_rascunhos(db, ident, status)


@router.get("/rascunhos/{rascunho_id}")
def detalhe_rascunho(rascunho_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return rascunhos.detalhar_rascunho(db, ident, rascunho_id)


@router.post("/rascunhos/{rascunho_id}/publicar")
def publicar(
    rascunho_id: int,
    dados: PublicacaoIn | None = None,
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """Revisar e publicar: é aqui que a aprovação humana é carimbada."""
    publicacao.aprovar_rascunho(db, ident, rascunho_id)
    return publicacao.publicar_rascunho(db, ident, rascunho_id, dados.itens if dados else None)


@router.delete("/rascunhos/{rascunho_id}")
def descartar(rascunho_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return publicacao.descartar_rascunho(db, ident, rascunho_id)


# --- questões ----------------------------------------------------------------


class QuestaoIn(BaseModel):
    enunciado: str = Field(description="Markdown, com fórmula em LaTeX entre $...$")
    alternativas: dict[str, str]
    gabarito: str
    assunto: str | None = None
    subassunto: str | None = None
    dificuldade: str | None = None
    vimeo_id: str | None = Field(None, description="Vídeo da resolução")
    imagem_pendente: bool = False


class EdicaoQuestaoIn(BaseModel):
    enunciado: str | None = None
    alternativas: dict[str, str] | None = Field(None, description="Só as letras que mudam")
    gabarito: str | None = None
    dificuldade: str | None = None
    imagem_pendente: bool | None = None
    assunto: str | None = Field(None, description="Troca a classificação; '' tira")
    subassunto: str | None = None
    vimeo_id: str | None = Field(None, description="Vídeo da resolução; '' tira")


@router.post("/questoes")
async def criar_questao(dados: QuestaoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """Uma questão avulsa, em rascunho."""
    resolucao = await vimeo_importacao.resolucao(dados.vimeo_id)
    return await run_in_threadpool(
        rascunhos.criar_questao_rascunho, db, ident, dados.enunciado, dados.alternativas,
        dados.gabarito, dados.assunto, dados.subassunto, dados.dificuldade, resolucao,
        dados.imagem_pendente,
    )


@router.get("/questoes/{questao_id}")
def detalhar_questao(questao_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return questoes.detalhar_questao(db, ident, questao_id)


@router.patch("/questoes/{questao_id}")
async def editar_questao(
    questao_id: int, dados: EdicaoQuestaoIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    campos: dict[str, Any] = dados.model_dump(exclude_unset=True, exclude={"vimeo_id"})
    campos["resolucao"] = await vimeo_importacao.resolucao(dados.vimeo_id)
    return await run_in_threadpool(
        lambda: questoes.editar_questao(db, ident, questao_id, **campos)
    )


@router.delete("/questoes/{questao_id}")
def remover_questao(questao_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return questoes.remover_questao(db, ident, questao_id)


@router.put("/questoes/{questao_id}/imagem")
async def anexar_imagem(
    questao_id: int,
    arquivo: UploadFile = File(description="PNG, JPEG, WEBP ou GIF, até 2 MB"),
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """A figura que não deu para transcrever. Anexar tira a pendência."""
    # Lê um byte além do limite: basta para recusar sem carregar um arquivo gigante.
    conteudo = await arquivo.read(questoes.LIMITE_DA_IMAGEM + 1)
    return await run_in_threadpool(
        questoes.anexar_imagem, db, ident, questao_id, conteudo, arquivo.filename
    )


@router.get("/questoes/{questao_id}/imagem", response_class=Response)
def imagem(questao_id: int, ident: Identidade = Operador, db: Session = Banco) -> Response:
    figura = questoes.imagem_da_questao(db, ident, questao_id)
    return Response(
        figura.conteudo, media_type=figura.tipo, headers={"X-Content-Type-Options": "nosniff"}
    )


# --- simulados ---------------------------------------------------------------


class SimuladoIn(BaseModel):
    turmas: list[str]
    titulo: str
    questoes: list[int | dict] = Field(
        description="Na ordem: id de questão publicada, ou a questão nova inteira"
    )
    abre_em: str | None = Field(None, description="Horário de Brasília: '2026-10-10T14:00'")
    fecha_em: str | None = None
    duracao_minutos: int | None = Field(None, ge=1)
    pasta_resolucao: str | None = Field(None, description="Pasta do Vimeo; casa pelo número")


class EdicaoSimuladoIn(BaseModel):
    titulo: str | None = None
    abre_em: str | None = None
    fecha_em: str | None = None
    duracao_minutos: int | None = Field(None, ge=1)
    turmas: list[str] | None = None
    questoes: list[int | dict] | None = None


@router.get("/simulados")
def lista_simulados(turma: str | None = None, ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    return simulados.listar_simulados(db, ident, turma)


@router.post("/simulados")
async def criar_simulado(dados: SimuladoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """O simulado e as questões novas dele, num rascunho só."""
    resolucoes = None
    if dados.pasta_resolucao:
        plano = await vimeo_importacao.ler_plano(dados.pasta_resolucao)
        resolucoes = vimeo_importacao.resolucoes_por_numero(plano)
    questoes_ = await vimeo_importacao.questoes_com_resolucao(dados.questoes)
    return await run_in_threadpool(
        rascunhos.criar_simulado_rascunho, db, ident, dados.turmas, dados.titulo, questoes_,
        dados.abre_em, dados.fecha_em, dados.duracao_minutos, resolucoes,
    )


@router.get("/simulados/{simulado}")
def detalhar_simulado(simulado: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return simulados.abrir_simulado(db, ident, simulado)


@router.patch("/simulados/{simulado}")
async def editar_simulado(
    simulado: str, dados: EdicaoSimuladoIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    campos: dict[str, Any] = dados.model_dump(exclude_unset=True)
    if "questoes" in campos:
        campos["questoes"] = await vimeo_importacao.questoes_com_resolucao(campos["questoes"])
    return await run_in_threadpool(lambda: simulados.editar_simulado(db, ident, simulado, **campos))


@router.delete("/simulados/{simulado}")
def remover_simulado(simulado: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return simulados.remover_simulado(db, ident, simulado)


@router.get("/simulados/{simulado}/ranking")
def ranking(simulado: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return simulados.ranking(db, ident, simulado)


@router.get("/simulados/{simulado}/estatisticas")
def estatisticas(simulado: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return analytics.estatisticas_simulado(db, ident, simulado)
