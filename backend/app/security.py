"""Senha, sessão do portal e token do MCP."""

from __future__ import annotations

import hashlib
import secrets
from datetime import UTC, datetime, timedelta

import bcrypt
import jwt

from app.config import get_settings

_ALGORITMO = "HS256"


# --- senha -------------------------------------------------------------------


def hash_senha(senha: str) -> str:
    return bcrypt.hashpw(senha.encode(), bcrypt.gensalt()).decode()


def confere_senha(senha: str, senha_hash: str) -> bool:
    try:
        return bcrypt.checkpw(senha.encode(), senha_hash.encode())
    except ValueError:
        return False


# --- sessão do portal (JWT) --------------------------------------------------


def cria_jwt(usuario_id: int, papel: str) -> str:
    s = get_settings()
    payload = {
        "sub": str(usuario_id),
        "papel": papel,
        "exp": datetime.now(UTC) + timedelta(hours=s.jwt_expira_horas),
        "iat": datetime.now(UTC),
    }
    return jwt.encode(payload, s.jwt_secret, algorithm=_ALGORITMO)


def le_jwt(token: str) -> dict | None:
    try:
        return jwt.decode(token, get_settings().jwt_secret, algorithms=[_ALGORITMO])
    except jwt.PyJWTError:
        return None


# --- token do MCP ------------------------------------------------------------


def novo_token_mcp() -> str:
    """Valor em claro do token. Aparece uma vez; o banco guarda só o hash."""
    return f"pvm_{secrets.token_urlsafe(32)}"


def hash_token(token: str) -> str:
    """SHA-256 do token.

    Diferente da senha, aqui não usamos bcrypt: o token é aleatório de 256
    bits (não há dicionário para atacar) e o hash é consultado a cada chamada
    de tool, então precisa ser barato e determinístico para virar índice.
    """
    return hashlib.sha256(token.encode()).hexdigest()
