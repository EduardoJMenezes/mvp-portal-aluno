"""Autenticação do MCP.

Simplificação assumida da POC (permitida pela seção 5): em vez de um servidor
de autorização OAuth, cada operador recebe um token Bearer opaco, emitido pela
plataforma e guardado como hash. O token não carrega permissão nenhuma — ele
só diz QUEM é. O que essa pessoa pode fazer continua sendo decidido pelo papel
dela no backend, a cada chamada.

Na arquitetura final isto vira OAuth: o que muda é só a origem da identidade;
os services e a autorização ficam onde estão.
"""

from __future__ import annotations

from datetime import UTC, datetime

import anyio
from fastmcp.server.auth import AccessToken, TokenVerifier
from sqlalchemy import select

from app.db import SessionLocal
from app.identidade import Canal, Identidade
from app.models import Papel, TokenMCP
from app.security import hash_token


def _consultar(token: str) -> AccessToken | None:
    with SessionLocal() as db:
        registro = db.scalar(
            select(TokenMCP).where(
                TokenMCP.token_hash == hash_token(token), TokenMCP.revogado.is_(False)
            )
        )
        if registro is None:
            return None

        usuario = registro.usuario
        # Aluno não opera o MCP administrativo (seção 4). Barrar já aqui evita
        # que uma credencial de aluno sequer abra sessão.
        if usuario.papel not in Papel.OPERADORES:
            return None

        registro.ultimo_uso_em = datetime.now(UTC)
        db.commit()

        return AccessToken(
            token=token,
            client_id=f"usuario-{usuario.id}",
            scopes=[],
            subject=str(usuario.id),
            claims={
                "usuario_id": usuario.id,
                "nome": usuario.nome,
                "email": usuario.email,
                "papel": usuario.papel,
            },
        )


class TokenDaPlataforma(TokenVerifier):
    async def verify_token(self, token: str) -> AccessToken | None:
        # O SQLAlchemy aqui é síncrono; sai do event loop para não travá-lo.
        return await anyio.to_thread.run_sync(_consultar, token)


def identidade_da_sessao() -> Identidade:
    """Traduz o token da chamada atual na Identidade que os services esperam."""
    from fastmcp.exceptions import ToolError
    from fastmcp.server.dependencies import get_access_token

    token = get_access_token()
    if token is None or not token.claims:
        raise ToolError("Sessão MCP sem identidade. Reconecte o servidor com um token válido.")

    c = token.claims
    return Identidade(
        usuario_id=c["usuario_id"],
        nome=c["nome"],
        email=c["email"],
        papel=c["papel"],
        canal=Canal.MCP,
    )
