"""Portal do aluno.

Nada aqui filtra por turma no cliente: as rotas devolvem exatamente o que os
services deixam a identidade ver (seção 11).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import usuario_atual
from app.db import get_db
from app.identidade import Identidade
from app.services import analytics, catalogo, simulados

router = APIRouter(prefix="/api/aluno", tags=["aluno"])


@router.get("/conteudo")
def conteudo(ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)) -> list[dict]:
    return catalogo.conteudo_do_aluno(db, ident)


@router.get("/simulados")
def lista(ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)) -> list[dict]:
    return simulados.listar_simulados(db, ident)


@router.get("/simulados/{simulado_id}")
def abrir(
    simulado_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    return simulados.abrir_simulado(db, ident, simulado_id)


class RespostaIn(BaseModel):
    questao_id: int
    alternativa: str


@router.post("/simulados/{simulado_id}/responder")
def responder(
    simulado_id: int,
    dados: RespostaIn,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    return simulados.responder(db, ident, simulado_id, dados.questao_id, dados.alternativa)


@router.post("/simulados/{simulado_id}/finalizar")
def finalizar(
    simulado_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    return simulados.finalizar(db, ident, simulado_id)


@router.get("/desempenho")
def desempenho(
    simulado: str | None = None,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    return analytics.desempenho_aluno(db, ident, ident.usuario_id, simulado)
