"""API do professor/gerenciador.

Mesmíssimos services que as tools do MCP chamam — o que muda é só a borda.
Toda operação do MCP tem aqui o endpoint equivalente, e o portal em Next.js
consome esta API. As referências aceitam id ou nome, como nas tools.

Além do que o MCP faz, moram aqui o que só o portal faz: turmas, alunos e
matrículas, senha de aluno e tokens do MCP. Sessões do Claude Code entram com
o token do MCP e por isso não aprovam nem descartam rascunho, não cadastram
aluno e não emitem token (ver `deps._pelo_token_do_mcp`).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, File, Form, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, EmailStr, Field
from starlette.concurrency import run_in_threadpool
from sqlalchemy.orm import Session

from app.api.deps import operador_atual
from app.db import get_db
from app.identidade import Identidade
from app.integracoes.zoom import de_configuracao as cliente_zoom
from app.services import (
    analytics,
    aulas,
    catalogo,
    contas,
    estrutura,
    importacoes,
    materiais,
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
Zoom = Depends(cliente_zoom)

TURMA = "/turmas/{turma}"
MODULO = TURMA + "/modulos/{modulo}"
SUBMODULO = MODULO + "/submodulos/{submodulo}"


# --- consulta ----------------------------------------------------------------


@router.get("/turmas")
def turmas(ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    return catalogo.listar_turmas(db, ident)


class TurmaIn(BaseModel):
    nome: str = Field(min_length=1, max_length=120)
    ano: int = Field(ge=2000, le=2100)


class EdicaoTurmaIn(BaseModel):
    nome: str | None = Field(None, min_length=1, max_length=120)
    ano: int | None = Field(None, ge=2000, le=2100)


@router.post("/turmas")
def criar_turma(dados: TurmaIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return catalogo.criar_turma(db, ident, dados.nome, dados.ano)


@router.patch(TURMA)
def editar_turma(turma: str, dados: EdicaoTurmaIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return catalogo.editar_turma(db, ident, turma, dados.nome, dados.ano)


# --- alunos e matrículas -----------------------------------------------------


class MatriculaIn(BaseModel):
    email: EmailStr
    nome: str = Field("", max_length=120, description="Obrigatório quando o aluno ainda não tem conta")


@router.get(TURMA + "/alunos")
def alunos_da_turma(turma: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return contas.alunos_da_turma(db, ident, turma)


@router.post(TURMA + "/alunos")
def matricular(turma: str, dados: MatriculaIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """Matricula; se o e-mail não tem conta, cria com senha temporária (mostrada uma vez)."""
    return contas.matricular(db, ident, turma, dados.nome, str(dados.email))


@router.delete(TURMA + "/alunos/{aluno}")
def desmatricular(turma: str, aluno: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return contas.desmatricular(db, ident, turma, aluno)


@router.post("/alunos/{aluno}/senha")
def redefinir_senha(aluno: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """Senha temporária nova, mostrada uma vez. As sessões do aluno caem."""
    return contas.redefinir_senha(db, ident, aluno)


# --- tokens do MCP -----------------------------------------------------------


class TokenIn(BaseModel):
    nome: str = Field("Claude", max_length=120)


@router.get("/tokens")
def tokens(ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    return contas.tokens_do_operador(db, ident)


@router.post("/tokens")
def emitir_token(dados: TokenIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """O valor do token aparece só nesta resposta."""
    return contas.emitir_token(db, ident, dados.nome)


@router.delete("/tokens/{token_id}")
def revogar_token(token_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return contas.revogar_token(db, ident, token_id)


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
    busca: str | None = Query(None, max_length=200, description="Trecho do enunciado"),
    limite: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    ident: Identidade = Operador,
    db: Session = Banco,
) -> list[dict]:
    """Acervo de questões de simulado — não as questões da apostila, que são
    vídeos e aparecem em /modulos. Página seguinte: `offset` += `limite`."""
    return catalogo.buscar_questoes(db, ident, assunto, status, dificuldade, limite, busca, offset)


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
    """Cria o assunto e os sub-assuntos. Repetir o nome acrescenta sub-assuntos, não duplica."""
    return taxonomia.cadastrar_assunto(db, ident, dados.nome, dados.subassuntos)


class NomeIn(BaseModel):
    nome: str = Field(min_length=1, max_length=120)


@router.patch("/assuntos/{assunto}")
def editar_assunto(assunto: str, dados: NomeIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return taxonomia.editar_assunto(db, ident, assunto, dados.nome)


@router.delete("/assuntos/{assunto}")
def excluir_assunto(assunto: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """Remoção lógica: vídeos e questões perdem a etiqueta até ela ser restaurada."""
    return taxonomia.excluir_assunto(db, ident, assunto)


@router.patch("/assuntos/{assunto}/subassuntos/{subassunto}")
def editar_subassunto(
    assunto: str, subassunto: str, dados: NomeIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return taxonomia.editar_subassunto(db, ident, assunto, subassunto, dados.nome)


@router.delete("/assuntos/{assunto}/subassuntos/{subassunto}")
def excluir_subassunto(assunto: str, subassunto: str, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return taxonomia.excluir_subassunto(db, ident, assunto, subassunto)


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
    return publicacao.aprovar_e_publicar(db, ident, rascunho_id, dados.itens if dados else None)


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
    resolucao_comentada: str | None = Field(None, description="Markdown; o aluno vê depois do fechamento")


class EdicaoQuestaoIn(BaseModel):
    enunciado: str | None = None
    alternativas: dict[str, str] | None = Field(None, description="Só as letras que mudam")
    gabarito: str | None = None
    dificuldade: str | None = None
    imagem_pendente: bool | None = None
    assunto: str | None = Field(None, description="Troca a classificação; '' tira")
    subassunto: str | None = None
    vimeo_id: str | None = Field(None, description="Vídeo da resolução; '' tira")
    resolucao_comentada: str | None = Field(None, description="'' tira")


@router.post("/questoes")
async def criar_questao(dados: QuestaoIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """Uma questão avulsa, em rascunho."""
    resolucao = await vimeo_importacao.resolucao(dados.vimeo_id)
    return await run_in_threadpool(
        rascunhos.criar_questao_rascunho, db, ident, dados.enunciado, dados.alternativas,
        dados.gabarito, dados.assunto, dados.subassunto, dados.dificuldade, resolucao,
        dados.imagem_pendente, dados.resolucao_comentada,
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


@router.post("/questoes/{questao_id}/figuras")
async def anexar_figura(
    questao_id: int,
    arquivo: UploadFile = File(description="PNG, JPEG, WEBP ou GIF, até 2 MB"),
    parte: str = Form("ENUNCIADO", description="ENUNCIADO, ALTERNATIVA ou RESOLUCAO"),
    alternativa: str | None = Form(None, max_length=1, description="A letra, quando a parte é ALTERNATIVA"),
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """A figura entra na primeira marca `figura:pendente` da parte; sem marca, no fim."""
    # Lê um byte além do limite: basta para recusar sem carregar um arquivo gigante.
    conteudo = await arquivo.read(questoes.LIMITE_DA_IMAGEM + 1)
    return await run_in_threadpool(
        questoes.anexar_figura, db, ident, questao_id, conteudo, arquivo.filename, parte, alternativa
    )


# --- importação de .docx -----------------------------------------------------


class ImportacaoDocxIn(BaseModel):
    turmas: list[str]
    titulo: str | None = None
    abre_em: str | None = None
    fecha_em: str | None = None
    duracao_minutos: int | None = Field(None, ge=1)
    pasta_resolucao: str | None = None


class CompletarQuestaoIn(BaseModel):
    numero: int
    enunciado: str = Field(description="Faixa de blocos, ex.: '12-18'")
    alternativas: str | dict[str, str]
    gabarito: str
    resolucao: str | None = None


@router.post("/importacoes")
def criar_link_de_envio(dados: ImportacaoDocxIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """O link de uso único pelo qual o .docx chega (ver /api/importacoes/{token})."""
    return importacoes.criar_link(db, ident, **dados.model_dump())


@router.get("/importacoes/{importacao_id}")
def revisar_importacao(
    importacao_id: int, de: int = 1, ate: int | None = None, ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """O que foi lido. As figuras vêm pelo id, em /api/aluno/figuras/{id}."""
    dados, _figuras = importacoes.revisar(db, ident, importacao_id, de, ate)
    return dados


@router.post("/importacoes/{importacao_id}/questoes")
def completar_questao_importada(
    importacao_id: int, dados: CompletarQuestaoIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return importacoes.completar_questao(db, ident, importacao_id, **dados.model_dump())


# --- importação de prints ----------------------------------------------------


class RecorteIn(BaseModel):
    print: int = Field(ge=1, description="Número do print, na ordem do envio")
    questao: int
    retangulo: list[float] = Field(
        min_length=4, max_length=4, description="[x0, y0, x1, y1] na escala da vista do print"
    )
    parte: str = "ENUNCIADO"
    alternativa: str | None = None
    substituir: int | None = Field(None, description="figura_id de um recorte que saiu errado")
    estender: bool = Field(True, description="Estende as bordas até o desenho acabar")


@router.post("/importacoes/prints")
def criar_link_de_prints(ident: Identidade = Operador, db: Session = Banco) -> dict:
    """O link de uso único pelo qual os prints chegam (ver /api/importacoes/{token}/prints)."""
    return importacoes.criar_link_de_prints(db, ident)


@router.get("/importacoes/{importacao_id}/prints")
def prints_da_importacao(importacao_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return importacoes.total_de_prints(db, ident, importacao_id)


@router.get("/importacoes/{importacao_id}/prints/{numero}", response_class=Response)
def ver_print(importacao_id: int, numero: int, ident: Identidade = Operador, db: Session = Banco) -> Response:
    """O print na escala em que o retângulo do recorte vale."""
    _dados, [vista] = importacoes.ver_prints(db, ident, importacao_id, numero, numero)
    return Response(vista, media_type="image/jpeg")


@router.post("/importacoes/{importacao_id}/recortes")
def recortar_figura(importacao_id: int, dados: RecorteIn, ident: Identidade = Operador, db: Session = Banco) -> dict:
    """A figura sai do print e entra na questão; o recorte fica em /api/aluno/figuras/{figura_id}."""
    saida, _png = importacoes.recortar_figura(
        db, ident, importacao_id, dados.print, dados.questao, dados.retangulo,
        dados.parte, dados.alternativa, dados.substituir, dados.estender,
    )
    return saida


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


# --- materiais ---------------------------------------------------------------


class EdicaoMaterialIn(BaseModel):
    titulo: str | None = Field(None, min_length=1, max_length=200)
    status: str | None = Field(None, description="RASCUNHO tira do ar; PUBLICADO libera")
    turmas: list[str] | None = Field(None, description="Turmas que passam a alcançar; troca a lista")
    alunos: list[str] | None = Field(None, description="Alunos avulsos; troca a lista")


@router.get("/materiais")
def lista_materiais(ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    """Os materiais, com quem cada um alcança. Inclui os que ainda são rascunho."""
    return materiais.listar_materiais(db, ident)


@router.post("/materiais")
async def enviar_material(
    arquivo: UploadFile = File(description="PDF, até 60 MB"),
    titulo: str = Form("", max_length=200),
    turmas: list[str] = Form([], description="Nomes ou ids das turmas que alcançam"),
    alunos: list[str] = Form([], description="Nomes, e-mails ou ids de alunos avulsos"),
    ident: Identidade = Operador,
    db: Session = Banco,
) -> dict:
    """Envia o PDF. Nasce em rascunho: publicar é um PATCH depois de conferir."""
    conteudo = await arquivo.read(materiais.LIMITE_DO_ARQUIVO + 1)
    nome = (arquivo.filename or "").strip()
    return await run_in_threadpool(
        materiais.criar_material,
        db,
        ident,
        titulo.strip() or nome.removesuffix(".pdf"),
        conteudo,
        nome,
        turmas,
        alunos,
    )


@router.get("/materiais/{material_id}")
def detalhar_material(material_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return materiais.detalhar_material(db, ident, material_id)


@router.patch("/materiais/{material_id}")
def editar_material(
    material_id: int, dados: EdicaoMaterialIn, ident: Identidade = Operador, db: Session = Banco
) -> dict:
    return materiais.editar_material(db, ident, material_id, **dados.model_dump(exclude_unset=True))


@router.delete("/materiais/{material_id}")
def remover_material(material_id: int, ident: Identidade = Operador, db: Session = Banco) -> dict:
    return materiais.remover_material(db, ident, material_id)


# --- aulas ao vivo -----------------------------------------------------------
#
# A sala é do Zoom, a porta é nossa. A conta do Zoom é dividida com outra
# plataforma que tem aula rodando: nenhuma rota daqui alcança reunião que não
# tenha nascido nesta tabela (ver docs/AULAS-AO-VIVO.md).


class AulaIn(BaseModel):
    titulo: str = Field(min_length=1, max_length=200)
    inicio_em: datetime = Field(description="Começo da aula, com fuso (ISO 8601)")
    minutos: int = Field(60, ge=5, le=480)
    descricao: str = Field("", max_length=2000)
    gravar: bool = Field(True, description="Grava na nuvem do Zoom para virar aula gravada")
    turmas: list[str] = Field(default_factory=list, description="Turmas que alcançam")
    alunos: list[str] = Field(default_factory=list, description="Alunos avulsos")
    submodulo_id: int | None = Field(None, description="Onde a gravação deve entrar no curso")
    publicar_gravacao: bool = True


class EdicaoAulaIn(BaseModel):
    titulo: str | None = Field(None, min_length=1, max_length=200)
    inicio_em: datetime | None = None
    minutos: int | None = Field(None, ge=5, le=480)
    status: str | None = Field(None, description="PUBLICADO abre a sala no Zoom; RASCUNHO desmarca")
    turmas: list[str] | None = None
    alunos: list[str] | None = None
    gravar: bool | None = None
    submodulo_id: int | None = None
    publicar_gravacao: bool | None = None


@router.get("/aulas")
def lista_aulas(ident: Identidade = Operador, db: Session = Banco) -> list[dict]:
    """As aulas ao vivo, com quem cada uma alcança. Inclui rascunho."""
    return aulas.listar_aulas(db, ident)


@router.post("/aulas")
def criar_aula(dados: AulaIn, ident: Identidade = Operador, db: Session = Banco, zoom=Zoom) -> dict:
    """Agenda a aula. Nasce em rascunho, e a sala do Zoom só abre ao publicar."""
    return aulas.criar_aula(db, ident, zoom, **dados.model_dump())


@router.patch("/aulas/{aula_id}")
def editar_aula(
    aula_id: int, dados: EdicaoAulaIn, ident: Identidade = Operador, db: Session = Banco, zoom=Zoom
) -> dict:
    return aulas.editar_aula(db, ident, zoom, aula_id, **dados.model_dump(exclude_unset=True))


@router.delete("/aulas/{aula_id}")
def remover_aula(aula_id: int, ident: Identidade = Operador, db: Session = Banco, zoom=Zoom) -> dict:
    """Some do portal e desmarca a sala — a nossa, pelo id que guardamos."""
    return aulas.remover_aula(db, ident, zoom, aula_id)


@router.post("/aulas/{aula_id}/iniciar")
def iniciar_aula(aula_id: int, ident: Identidade = Operador, db: Session = Banco, zoom=Zoom) -> dict:
    """O link de iniciar, buscado na hora: o do Zoom expira em duas horas."""
    return aulas.link_do_professor(db, ident, aula_id, zoom)
