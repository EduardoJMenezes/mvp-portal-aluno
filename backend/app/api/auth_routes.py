"""Login do portal."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, EmailStr
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import usuario_atual
from app.db import get_db
from app.identidade import Identidade
from app.models import Matricula, Turma, Usuario
from app.security import confere_senha, cria_jwt

router = APIRouter(prefix="/api", tags=["auth"])


class LoginIn(BaseModel):
    email: EmailStr
    senha: str


def _perfil(db: Session, usuario_id: int, nome: str, email: str, papel: str) -> dict:
    turmas = db.scalars(
        select(Turma.nome).join(Matricula, Matricula.turma_id == Turma.id).where(
            Matricula.usuario_id == usuario_id
        )
    ).all()
    return {"id": usuario_id, "nome": nome, "email": email, "papel": papel, "turmas": list(turmas)}


@router.post("/login")
def login(dados: LoginIn, db: Session = Depends(get_db)) -> dict:
    usuario = db.scalar(select(Usuario).where(Usuario.email == dados.email.lower()))
    if usuario is None or not confere_senha(dados.senha, usuario.senha_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "E-mail ou senha incorretos.")

    return {
        "token": cria_jwt(usuario.id, usuario.papel),
        "usuario": _perfil(db, usuario.id, usuario.nome, usuario.email, usuario.papel),
    }


@router.get("/eu")
def eu(ident: Identidade = Depends(usuario_atual), db: Session = Depends(get_db)) -> dict:
    return _perfil(db, ident.usuario_id, ident.nome, ident.email, ident.papel)
