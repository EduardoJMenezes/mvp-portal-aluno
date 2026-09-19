"""Sobe o processo no papel pedido — é o comando de partida de cada serviço.

    python -m app.servir portal --workers 4    # portal e API, sem /mcp
    python -m app.servir mcp                   # só o conector, um processo
    python -m app.servir                       # os dois juntos, como sempre foi

Existe para o papel do serviço aparecer no painel da Railway, em vez de ficar
escondido numa variável de ambiente — é o que a documentação deles recomenda
para vários serviços saindo do mesmo repositório (docs/RAILWAY-PASSO-A-PASSO.md).

E carrega a regra que antes só existia por escrito: **mais de um processo só
com o papel `portal`**. A sessão do conector MCP mora na memória do processo;
duplicá-la faria o pedido do professor cair no processo errado, e a falha
apareceria como "o Claude perdeu o servidor", sem dizer o motivo.
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass

PAPEIS = ("tudo", "portal", "mcp")


@dataclass(frozen=True)
class Plano:
    papel: str
    workers: int
    porta: int
    migrar: bool


def plano(argv: list[str] | None = None, ambiente: dict[str, str] | None = None) -> Plano:
    """Lê a linha de comando (com o ambiente como padrão) e confere a regra."""
    env = os.environ if ambiente is None else ambiente
    analisador = argparse.ArgumentParser(prog="app.servir", description=__doc__)
    analisador.add_argument("papel", nargs="?", default=env.get("PAPEL", "tudo"), choices=PAPEIS)
    analisador.add_argument("--workers", type=int, default=int(env.get("WEB_CONCURRENCY", "1")))
    analisador.add_argument("--porta", type=int, default=int(env.get("PORT", "8000")))
    analisador.add_argument(
        "--sem-migrar", action="store_true", help="não aplica as migrações antes de subir"
    )
    args = analisador.parse_args(argv)

    if args.workers > 1 and args.papel != "portal":
        analisador.error(
            f"--workers {args.workers} só vale com o papel 'portal'. Com '{args.papel}' o /mcp "
            "entra no processo, e a sessão do conector não se duplica: o pedido do professor "
            "cairia no processo errado. Separe o MCP num serviço próprio antes de multiplicar."
        )
    if args.workers < 1:
        analisador.error("--workers precisa ser pelo menos 1.")
    return Plano(papel=args.papel, workers=args.workers, porta=args.porta, migrar=not args.sem_migrar)


def main(argv: list[str] | None = None) -> None:
    import uvicorn

    escolha = plano(argv)
    # Precisa estar no ambiente antes de `app.main` ser importado — inclusive
    # pelos processos-filhos que o uvicorn cria com --workers.
    os.environ["PAPEL"] = escolha.papel

    if escolha.migrar:
        from app import migracoes
        from app.db import engine

        migracoes.aplicar(engine)

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",  # noqa: S104 — o contêiner só é alcançado pelo proxy da Railway
        port=escolha.porta,
        workers=escolha.workers,
        proxy_headers=True,
        forwarded_allow_ips="*",
    )


if __name__ == "__main__":
    main()
