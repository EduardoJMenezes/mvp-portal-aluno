"""Backend único: as mesmas regras atendem o portal (REST) e o agente (MCP).

    REST  ─┐
           ├─► Application Services ─► PostgreSQL / Vimeo
    MCP   ─┘

Os dois entram pelo mesmo processo e pelos mesmos services. O MCP não tem
atalho para o banco (seção 19).
"""

from __future__ import annotations

import logging
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sqlalchemy.exc import OperationalError
from starlette.exceptions import HTTPException

from app.api import admin_routes, aluno_routes, auth_routes
from app.config import get_settings
from app.db import engine
from app.errors import AprovacaoNecessaria, NaoAutorizado, NaoEncontrado, RegraDeNegocio

# Importar o módulo de tools registra todas elas na instância `mcp`.
from app.mcp_server import tools as _tools  # noqa: F401
from app.mcp_server.server import mcp

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("plataforma")

settings = get_settings()


class SpaEstatica(StaticFiles):
    """StaticFiles que devolve o index.html nas rotas do React Router.

    Sem isso, abrir /rascunhos direto (ou recarregar a página) dá 404: esses
    caminhos existem só no cliente.
    """

    async def get_response(self, path: str, scope):  # type: ignore[override]
        try:
            return await super().get_response(path, scope)
        except HTTPException as e:
            if e.status_code != 404:
                raise
            # Caminho que o React Router conhece e o disco não: entrega o app.
            return await super().get_response("index.html", scope)


class BarraFinalDoMcp:
    """Faz /mcp e /mcp/ apontarem para o mesmo lugar.

    O endpoint MCP é um sub-app montado em /mcp, e um Mount do Starlette só
    casa com o prefixo seguido de barra. Sem esta normalização, um cliente
    configurado com `http://host/mcp` (o jeito que todo mundo escreve) receberia
    405 do frontend estático em vez de falar com o servidor.
    """

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] == "http" and scope["path"] == "/mcp":
            scope = {**scope, "path": "/mcp/", "raw_path": b"/mcp/"}
        await self.app(scope, receive, send)



# O app ASGI do MCP tem lifespan próprio (gerencia as sessões do transporte
# HTTP). Ele precisa ser o lifespan do FastAPI, senão o servidor sobe sem
# session manager e toda chamada de tool falha.
#
# path="/" + mount em "/mcp": o Mount casa só o prefixo /mcp, deixando o resto
# das rotas livres. Montá-lo na raiz faria o Mount engolir tudo — inclusive o
# frontend estático, que é montado logo abaixo.
mcp_app = mcp.http_app(path="/")

app = FastAPI(
    title="Plataforma Educacional — POC MCP",
    version="0.1.0",
    lifespan=mcp_app.lifespan,
)

app.add_middleware(BarraFinalDoMcp)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.lista_cors,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(NaoEncontrado)
def _nao_encontrado(_: Request, exc: NaoEncontrado) -> JSONResponse:
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.exception_handler(NaoAutorizado)
def _nao_autorizado(_: Request, exc: NaoAutorizado) -> JSONResponse:
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@app.exception_handler(AprovacaoNecessaria)
def _aprovacao(_: Request, exc: AprovacaoNecessaria) -> JSONResponse:
    return JSONResponse(status_code=409, content={"detail": str(exc)})


@app.exception_handler(RegraDeNegocio)
def _regra(_: Request, exc: RegraDeNegocio) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": str(exc)})


# Os dois handlers abaixo existem porque o portal mostra `detail` ao usuário.
# Sem eles, banco fora do ar e bug respondem "Internal Server Error" em texto
# puro — e a tela de login fica sem uma frase para exibir.

MENSAGEM_BANCO_FORA = "Banco de dados indisponível no momento. Tente novamente em instantes."
MENSAGEM_ERRO_INTERNO = "Erro interno no servidor. Tente novamente em instantes."


@app.exception_handler(OperationalError)
def _banco_indisponivel(request: Request, exc: OperationalError) -> JSONResponse:
    # Conexão recusada, host errado, senha inválida: problema de infraestrutura,
    # não do usuário. Host e usuário do banco ficam só no log.
    logger.error("banco indisponível em %s %s: %s", request.method, request.url.path, exc)
    return JSONResponse(status_code=503, content={"detail": MENSAGEM_BANCO_FORA})


@app.exception_handler(Exception)
def _erro_inesperado(_: Request, __: Exception) -> JSONResponse:
    # O traceback continua indo para o log: o Starlette relança a exceção
    # depois de enviar esta resposta.
    return JSONResponse(status_code=500, content={"detail": MENSAGEM_ERRO_INTERNO})


@app.get("/api/saude", tags=["infra"])
def saude() -> JSONResponse:
    # O Railway usa este caminho para aprovar um deploy. Sem sondar o banco, um
    # DATABASE_URL errado passa no healthcheck e só aparece como erro na tela
    # de login — foi exatamente o que aconteceu no primeiro deploy.
    try:
        with engine.connect() as conexao:
            conexao.execute(text("SELECT 1"))
        banco = "ok"
    except OperationalError as exc:
        logger.error("healthcheck: banco indisponível: %s", exc)
        banco = "indisponivel"

    return JSONResponse(
        status_code=200 if banco == "ok" else 503,
        content={
            "ok": banco == "ok",
            "banco": banco,
            "vimeo": "api-real" if settings.vimeo_real else "acervo-de-demonstracao",
            "mcp": "/mcp",
        },
    )


app.include_router(auth_routes.router)
app.include_router(admin_routes.router)
app.include_router(aluno_routes.router)

app.mount("/mcp", mcp_app)

# O frontend compilado, quando existe, é servido pelo mesmo host — assim a demo
# roda em uma porta só. Em desenvolvimento usa-se o Vite (porta 5173).
# Fica por último: um mount em "/" casa com qualquer caminho e encerra a busca.
_dist = Path(__file__).resolve().parents[2] / "frontend" / "dist"
if _dist.is_dir():
    app.mount("/", SpaEstatica(directory=str(_dist), html=True), name="frontend")
    logger.info("frontend servido de %s", _dist)


def main() -> None:
    import uvicorn

    uvicorn.run(app, host=settings.app_host, port=settings.app_port)


if __name__ == "__main__":
    main()
