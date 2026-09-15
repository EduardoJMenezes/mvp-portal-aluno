"""Sessão do portal: entrar, sair, trocar a senha e o modo demonstração.

A senha nunca volta e o token da sessão nunca chega ao JavaScript: o login
responde só o perfil e grava a sessão num cookie httpOnly (ver `api/deps.py`).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy.orm import Session

from app.api.deps import COOKIE_SESSAO, usuario_atual
from app.config import get_settings
from app.db import get_db
from app.errors import MuitasTentativas
from app.identidade import Identidade
from app.models import Usuario
from app.security import cria_jwt
from app.services import contas

router = APIRouter(prefix="/api", tags=["auth"])
logger = logging.getLogger("plataforma")


class LoginIn(BaseModel):
    email: EmailStr
    senha: str = Field(max_length=200)


class TrocaDeSenhaIn(BaseModel):
    senha_atual: str = Field(max_length=200)
    nova_senha: str = Field(max_length=200)


class DemoIn(BaseModel):
    email: str = Field(max_length=180)


def _abrir_sessao(resposta: Response, usuario: Usuario) -> None:
    s = get_settings()
    resposta.set_cookie(
        COOKIE_SESSAO,
        cria_jwt(usuario.id, usuario.papel),
        max_age=s.jwt_expira_horas * 3600,
        httponly=True,
        secure=s.sessao_cookie_seguro,
        samesite="strict",
        path="/api",
    )


def _ip(request: Request) -> str:
    # Atrás do proxy do Railway, o uvicorn roda com --proxy-headers: este é o IP de quem pediu.
    # ponytail: com --forwarded-allow-ips '*' vale o primeiro IP do X-Forwarded-For, que o
    # cliente pode forjar — escapa só do limite por IP; o por conta segue. Fixar os IPs do
    # proxy se o limite por IP precisar ser à prova de forja.
    return request.client.host if request.client else "desconhecido"


@router.post("/login")
def login(dados: LoginIn, request: Request, resposta: Response, db: Session = Depends(get_db)) -> dict:
    try:
        usuario = contas.entrar(db, dados.email, dados.senha, _ip(request))
    except MuitasTentativas:
        logger.warning("login travado por excesso de tentativas (ip=%s)", _ip(request))
        raise
    _abrir_sessao(resposta, usuario)
    logger.info("login do usuário %s (%s)", usuario.id, usuario.papel)
    return {"usuario": contas.perfil(db, usuario)}


@router.post("/logout")
def logout(resposta: Response) -> dict:
    resposta.delete_cookie(
        COOKIE_SESSAO, path="/api", httponly=True, secure=get_settings().sessao_cookie_seguro,
        samesite="strict",
    )
    return {"saiu": True}


@router.get("/eu")
def eu(ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)) -> dict:
    return contas.perfil(db, db.get(Usuario, ident.usuario_id))


@router.post("/conta/senha")
def trocar_senha(
    dados: TrocaDeSenhaIn,
    resposta: Response,
    ident: Identidade = Depends(usuario_atual),
    db: Session = Depends(get_db),
) -> dict:
    """Troca a senha e renova a sessão: as outras sessões desta conta caem."""
    usuario = contas.trocar_senha(db, ident, dados.senha_atual, dados.nova_senha)
    _abrir_sessao(resposta, usuario)
    logger.info("senha trocada pelo usuário %s", usuario.id)
    return {"usuario": contas.perfil(db, usuario)}


@router.get("/sessao/config")
def configuracao(db: Session = Depends(get_db)) -> dict:
    """O que a tela de login precisa saber antes de alguém entrar."""
    demo = get_settings().modo_demo
    return {"modo_demo": demo, "contas_demo": contas.contas_demo(db) if demo else []}


@router.post("/demo/entrar")
def entrar_como_demo(
    dados: DemoIn, resposta: Response, request: Request, db: Session = Depends(get_db)
) -> dict:
    if not get_settings().modo_demo:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Não encontrado.")
    usuario = contas.entrar_como_demo(db, dados.email)
    _abrir_sessao(resposta, usuario)
    logger.warning("modo demonstração: entrada sem senha como %s (ip=%s)", usuario.email, _ip(request))
    return {"usuario": contas.perfil(db, usuario)}
