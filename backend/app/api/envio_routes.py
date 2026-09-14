"""A página de envio do .docx ou dos prints: o link é a credencial.

Quem abre o link não precisa estar logado — ele é de uso único, expira em 30
minutos e está preso a quem o pediu no chat (ver `services/importacoes.py`).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, UploadFile
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from app.db import get_db
from app.services import importacoes, vimeo_importacao

router = APIRouter(prefix="/api/importacoes", tags=["importacao"])


@router.get("/{token}")
def situacao(token: str, db: Session = Depends(get_db)) -> dict:
    return importacoes.situacao_do_link(db, token)


@router.post("/{token}/arquivo")
async def enviar_arquivo(
    token: str,
    arquivo: UploadFile = File(description="O .docx do simulado"),
    db: Session = Depends(get_db),
) -> dict:
    conteudo = await arquivo.read(importacoes.LIMITE_DO_ARQUIVO + 1)
    pasta = (await run_in_threadpool(importacoes.situacao_do_link, db, token)).get("pasta_resolucao")
    resolucoes = (
        vimeo_importacao.resolucoes_por_numero(await vimeo_importacao.ler_plano(pasta)) if pasta else None
    )
    return await run_in_threadpool(
        importacoes.receber_arquivo, db, token, arquivo.filename, conteudo, resolucoes
    )


@router.post("/{token}/prints")
async def enviar_prints(
    token: str,
    arquivos: list[UploadFile] = File(description="Os prints das questões, na ordem"),
    db: Session = Depends(get_db),
) -> dict:
    # Um a mais que o limite, de contagem e de tamanho, basta para o service recusar.
    lidos = [
        (arquivo.filename, await arquivo.read(importacoes.LIMITE_DO_PRINT + 1))
        for arquivo in arquivos[: importacoes.LIMITE_DOS_PRINTS + 1]
    ]
    return await run_in_threadpool(importacoes.receber_prints, db, token, lidos)
