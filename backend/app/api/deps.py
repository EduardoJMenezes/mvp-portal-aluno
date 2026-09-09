"""Identidade de quem chega pelo portal (navegador)."""

from __future__ import annotations

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session

from app.db import get_db
from app.identidade import Canal, Identidade
from app.models import Usuario
from app.security import le_jwt


def usuario_atual(
    authorization: str | None = Header(default=None),
    db: Session = Depends(get_db),
) -> Identidade:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Faça login para continuar.")

    dados = le_jwt(authorization.split(" ", 1)[1].strip())
    if not dados:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sessão expirada ou inválida.")

    usuario = db.get(Usuario, int(dados["sub"]))
    if usuario is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Usuário não existe mais.")

    return Identidade(
        usuario_id=usuario.id,
        nome=usuario.nome,
        email=usuario.email,
        papel=usuario.papel,
        canal=Canal.PORTAL,
    )


def operador_atual(ident: Identidade = Depends(usuario_atual)) -> Identidade:
    if not ident.e_operador:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Área restrita a ADMIN/GERENCIADOR.")
    return ident
