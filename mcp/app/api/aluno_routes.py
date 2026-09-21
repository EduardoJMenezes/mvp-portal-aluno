"""Portal do aluno.

Nada aqui filtra por turma no cliente: as rotas devolvem exatamente o que os
services deixam a identidade ver (seção 11).
"""

from __future__ import annotations

import hashlib
import json

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.deps import usuario_atual
from app.db import get_db
from app.identidade import Identidade
from app.integracoes.zoom import de_configuracao as cliente_zoom
from app.services import aulas, catalogo, materiais, questoes, simulados

router = APIRouter(prefix="/api/aluno", tags=["aluno"])


def _com_etag(request: Request, dados) -> Response:
    """Devolve 304 quando o navegador já tem esta versão.

    É a rota mais chamada do portal e a segunda mais cara (48,7 KB, 29,5 ms):
    toda aba aberta pede a árvore inteira do curso. Com o ETag, quem volta
    recebe um "não mudou" de 200 bytes (docs/CARGA.md).
    """
    corpo = json.dumps(dados, ensure_ascii=False, default=str).encode()
    etiqueta = f'"{hashlib.sha256(corpo).hexdigest()[:32]}"'
    # `no-cache` aqui não é "não guarde": é "guarde e pergunte antes de usar".
    cabecalhos = {"etag": etiqueta, "cache-control": "private, no-cache"}
    if request.headers.get("if-none-match") == etiqueta:
        return Response(status_code=304, headers=cabecalhos)
    return Response(corpo, media_type="application/json", headers=cabecalhos)


@router.get("/conteudo")
def conteudo(
    request: Request, ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)
) -> Response:
    return _com_etag(request, catalogo.conteudo_do_aluno(db, ident))


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


# --- materiais ---------------------------------------------------------------


CABECALHOS_DO_ARQUIVO = {
    "accept-ranges": "bytes",
    # Sem download e sem cópia no disco do navegador: o material sai do portal
    # só enquanto a sessão está aberta.
    "cache-control": "private, no-store",
    "content-disposition": "inline",
    "x-content-type-options": "nosniff",
}


def _faixa(cabecalho: str | None, total: int) -> tuple[int, int] | None:
    """Traduz o `Range` em (início, fim). Uma faixa só — é o que o leitor pede."""
    if not cabecalho or not cabecalho.strip().startswith("bytes="):
        return None
    de, _, ate = cabecalho.strip().removeprefix("bytes=").split(",")[0].strip().partition("-")
    try:
        if de:
            inicio, fim = int(de), (int(ate) if ate else total - 1)
        elif ate:  # "bytes=-500": os últimos 500
            inicio, fim = max(0, total - int(ate)), total - 1
        else:
            return None
    except ValueError:
        return None
    return inicio, min(fim, total - 1)


@router.get("/materiais")
def materiais_do_aluno(
    ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)
) -> list[dict]:
    """Os materiais publicados que alcançam quem pergunta — pela turma ou pelo nome."""
    return materiais.listar_materiais(db, ident)


@router.get("/materiais/{material_id}/arquivo", response_class=Response)
def arquivo_do_material(
    material_id: int,
    request: Request,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> Response:
    """O PDF, em faixas de bytes.

    Cada faixa passa pela sessão: o endereço não é link que se repassa. É assim
    que o leitor abre a página 180 de uma apostila de 323 sem baixar o resto.
    """
    material = materiais.abrir_arquivo(db, ident, material_id)
    total = material.tamanho
    faixa = _faixa(request.headers.get("range"), total)

    if faixa is None:
        # O `content-length` é o que faz o leitor passar a pedir faixas em vez
        # de arrastar o arquivo inteiro para ver uma página.
        return StreamingResponse(
            materiais.pedacos(material.id, total),
            media_type=materiais.TIPO,
            headers={**CABECALHOS_DO_ARQUIVO, "content-length": str(total)},
        )

    inicio, fim = faixa
    if inicio > fim or inicio >= total:
        return Response(
            status_code=416,
            headers={**CABECALHOS_DO_ARQUIVO, "content-range": f"bytes */{total}"},
        )
    return Response(
        materiais.fatia(db, material, inicio, fim - inicio + 1),
        status_code=206,
        media_type=materiais.TIPO,
        headers={**CABECALHOS_DO_ARQUIVO, "content-range": f"bytes {inicio}-{fim}/{total}"},
    )


@router.get("/materiais/{material_id}/anotacoes")
def anotacoes_do_material(
    material_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    """O que **quem pergunta** riscou neste material, página a página."""
    return materiais.anotacoes(db, ident, material_id)


class AnotacaoIn(BaseModel):
    tracos: list[dict] = Field(
        default_factory=list, description="Traços da página, em coordenadas relativas (0 a 1)"
    )


@router.put("/materiais/{material_id}/anotacoes/{pagina}")
def salvar_anotacao(
    material_id: int,
    pagina: int,
    dados: AnotacaoIn,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    """Grava uma página. É o que o salvamento automático chama."""
    return materiais.salvar_anotacao(db, ident, material_id, pagina, dados.model_dump())


# --- aulas ao vivo -----------------------------------------------------------


@router.get("/aulas")
def aulas_do_aluno(
    ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)
) -> list[dict]:
    """As aulas ao vivo que alcançam quem pergunta — pela turma ou pelo nome."""
    return aulas.listar_aulas(db, ident)


@router.post("/aulas/{aula_id}/entrar")
def entrar_na_aula(
    aula_id: int,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
    zoom=Depends(cliente_zoom),
) -> dict:
    """O link **daquele** aluno, conferindo acesso e horário.

    Não existe endereço de entrada em listagem: ele nasce aqui, no clique, e
    fica guardado porque o Zoom só deixa inscrever o mesmo e-mail três vezes
    por dia na mesma reunião.
    """
    return aulas.entrar(db, ident, aula_id, zoom)
