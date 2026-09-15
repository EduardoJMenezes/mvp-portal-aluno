"""Portal do aluno.

Nada aqui filtra por turma no cliente: as rotas devolvem exatamente o que os
services deixam a identidade ver (seção 11).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import Response
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import usuario_atual
from app.db import get_db
from app.identidade import Identidade
from app.services import catalogo, questoes, simulados

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


@router.post("/simulados/{simulado_id}/entregar")
def entregar(
    simulado_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    """Entrega a prova. O recibo diz quando sai o resultado — não o resultado."""
    return simulados.entregar(db, ident, simulado_id)


@router.get("/simulados/{simulado_id}/resultado")
def resultado(
    simulado_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    """Nota, posição, gabarito, resolução e análise — só depois do fechamento."""
    return simulados.resultado(db, ident, simulado_id)


@router.get("/desempenho")
def desempenho(ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)) -> dict:
    """Os simulados encerrados que fez, com nota e posição, e onde mais errou."""
    return simulados.historico_do_aluno(db, ident)


@router.get("/figuras/{figura_id}", response_class=Response)
def figura(
    figura_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> Response:
    """A figura de uma questão, para quem pode vê-la — operador ou aluno.

    Vem por aqui, com o token, e não por URL pública: antes de a prova abrir, a
    figura adiantaria a questão; a da resolução, o gabarito.
    """
    imagem = questoes.figura(db, ident, figura_id)
    return Response(
        imagem.conteudo,
        media_type=imagem.tipo,
        headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "private, max-age=3600"},
    )
