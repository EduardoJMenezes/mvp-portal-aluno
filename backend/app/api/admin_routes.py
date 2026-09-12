"""Portal do professor/gerenciador.

Mesmíssimos services que as tools do MCP chamam — o que muda é só a borda.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import operador_atual
from app.db import get_db
from app.identidade import Identidade
from app.services import analytics, catalogo, publicacao, rascunhos, simulados, taxonomia
from app.vimeo.client import get_cliente_vimeo

router = APIRouter(prefix="/api/admin", tags=["admin"], dependencies=[Depends(operador_atual)])


@router.get("/turmas")
def turmas(ident: Identidade = Depends(operador_atual), db: Session = Depends(get_db)) -> list[dict]:
    return catalogo.listar_turmas(db, ident)


@router.get("/modulos")
def modulos(
    turma: str | None = None,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> list[dict]:
    """A árvore do curso: módulos, sub-módulos e itens de cada turma."""
    return catalogo.listar_modulos(db, ident, turma)


@router.get("/assuntos")
def assuntos(db: Session = Depends(get_db)) -> list[dict]:
    return taxonomia.listar_assuntos(db)


@router.get("/questoes")
def questoes(
    assunto: str | None = None,
    status: str | None = None,
    dificuldade: str | None = None,
    limite: int = 200,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> list[dict]:
    """Acervo de questões de simulado — não as questões da apostila, que são
    vídeos e aparecem em /api/modulos."""
    return catalogo.buscar_questoes(db, ident, assunto, status, dificuldade, limite)


@router.get("/rascunhos")
def lista_rascunhos(
    status: str | None = None,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> list[dict]:
    return rascunhos.listar_rascunhos(db, ident, status)


@router.get("/rascunhos/{rascunho_id}")
def detalhe_rascunho(
    rascunho_id: int,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> dict:
    return rascunhos.detalhar_rascunho(db, ident, rascunho_id)


@router.post("/rascunhos/{rascunho_id}/publicar")
def publicar(
    rascunho_id: int,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> dict:
    """Revisar e publicar: é aqui que a aprovação humana é carimbada."""
    return publicacao.aprovar_e_publicar(db, ident, rascunho_id)


@router.delete("/rascunhos/{rascunho_id}")
def descartar(
    rascunho_id: int,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> dict:
    return publicacao.descartar_rascunho(db, ident, rascunho_id)


@router.get("/simulados")
def lista_simulados(
    turma: str | None = None,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> list[dict]:
    return simulados.listar_simulados(db, ident, turma)


@router.get("/simulados/{simulado_id}/estatisticas")
def estatisticas(
    simulado_id: int,
    ident: Identidade = Depends(operador_atual),
    db: Session = Depends(get_db),
) -> dict:
    return analytics.estatisticas_simulado(db, ident, simulado_id)


class VimeoImportIn(BaseModel):
    turma: str
    modulo: str
    submodulo: str
    videos: list[dict]


@router.get("/vimeo/pastas")
async def vimeo_pastas() -> list[dict]:
    cliente = get_cliente_vimeo()
    return [p.__dict__ for p in await cliente.listar_pastas()]


@router.get("/vimeo/videos")
async def vimeo_videos(pasta: str | None = None, busca: str | None = None, limite: int = 25) -> list[dict]:
    cliente = get_cliente_vimeo()
    return [v.__dict__ for v in await cliente.listar_videos(pasta, busca, limite)]
