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

    valor = authorization.split(" ", 1)[1].strip()
    dados = le_jwt(valor)
    if not dados:
        return _pelo_token_do_mcp(valor)

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


def _pelo_token_do_mcp(valor: str) -> Identidade:
    """O token do MCP também abre a API — e com o canal MCP, não o do portal.

    É por ele que uma sessão do Claude Code envia a figura recortada de uma
    questão (docs/MODELO-SIMULADO.md). O token só existe para operador, e o
    canal continua barrado onde a regra pede um humano no navegador: aprovar e
    descartar rascunho.
    """
    from app.mcp_server.auth import _consultar

    acesso = _consultar(valor)
    if acesso is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sessão expirada ou inválida.")
    c = acesso.claims
    return Identidade(
        usuario_id=c["usuario_id"], nome=c["nome"], email=c["email"], papel=c["papel"],
        canal=Canal.MCP,
    )


def operador_atual(ident: Identidade = Depends(usuario_atual)) -> Identidade:
    if not ident.e_operador:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Área restrita a ADMIN/GERENCIADOR.")
    return ident
