"""Emite (ou revoga) um token de MCP para um operador.

    python scripts/token_mcp.py professor@escola.demo
    python scripts/token_mcp.py professor@escola.demo --revogar-anteriores

O valor em claro aparece uma única vez: o banco guarda só o hash.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))

from sqlalchemy import select

from app.db import SessionLocal
from app.models import Papel, TokenMCP, Usuario
from app.security import hash_token, novo_token_mcp


def main() -> int:
    parser = argparse.ArgumentParser(description="Emite um token de MCP para um operador.")
    parser.add_argument("email", help="e-mail do ADMIN ou GERENCIADOR")
    parser.add_argument("--nome", default="Claude", help="rótulo do token (padrão: Claude)")
    parser.add_argument("--revogar-anteriores", action="store_true",
                        help="revoga os tokens já emitidos para esta pessoa")
    args = parser.parse_args()

    with SessionLocal() as db:
        usuario = db.scalar(select(Usuario).where(Usuario.email == args.email.lower()))
        if usuario is None:
            print(f"Usuário '{args.email}' não existe.")
            return 1
        if usuario.papel not in Papel.OPERADORES:
            print(f"{usuario.nome} é {usuario.papel}; só ADMIN e GERENCIADOR operam o MCP.")
            return 1

        if args.revogar_anteriores:
            anteriores = db.scalars(
                select(TokenMCP).where(
                    TokenMCP.usuario_id == usuario.id, TokenMCP.revogado.is_(False)
                )
            ).all()
            for t in anteriores:
                t.revogado = True
            print(f"{len(anteriores)} token(s) anterior(es) revogado(s).")

        valor = novo_token_mcp()
        db.add(
            TokenMCP(usuario_id=usuario.id, nome=f"{args.nome} — {usuario.nome}",
                     token_hash=hash_token(valor))
        )
        db.commit()

    print(f"\nToken de {usuario.nome} ({usuario.papel}):\n\n  {valor}\n")
    print("Guarde agora — ele não aparece de novo. Use como Bearer em http://127.0.0.1:8000/mcp")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
