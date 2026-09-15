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
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sqlalchemy.exc import OperationalError
from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.routing import Mount

from app.api import admin_routes, aluno_routes, auth_routes, envio_routes
from app.config import Settings, get_settings
from app.db import engine
from app.errors import (
    AprovacaoNecessaria,
    CredenciaisInvalidas,
    MuitasTentativas,
    NaoAutorizado,
    NaoEncontrado,
    RegraDeNegocio,
)
from app.integracoes.vimeo import VimeoErro

# Importar os módulos de tools registra todas elas na instância `mcp`.
from app.mcp_server import tools as _tools  # noqa: F401
from app.mcp_server import tools_estrutura as _tools_estrutura  # noqa: F401
from app.mcp_server import tools_importacao as _tools_importacao  # noqa: F401
from app.mcp_server import tools_simulado as _tools_simulado  # noqa: F401
from app.mcp_server.server import mcp

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("plataforma")

settings = get_settings()

CAMINHO_MCP = "/mcp"

if settings.jwt_secret == Settings.model_fields["jwt_secret"].default:
    logger.warning("JWT_SECRET é o valor de desenvolvimento: defina um segredo aleatório fora da máquina local")
if settings.modo_demo:
    logger.warning("MODO_DEMO ligado: a tela de login entra nas contas .demo sem senha")

# O export estático do Next injeta scripts inline e não há servidor para dar
# nonce a eles. ponytail: 'unsafe-inline' em script-src; o XSS fica contido
# pelo texto da questão escapado e pela sessão httpOnly. Nonce exige o Next
# rodando como servidor.
CSP_DO_PORTAL = "; ".join(
    [
        "default-src 'self'",
        "script-src 'self' 'unsafe-inline'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob: https://i.vimeocdn.com",
        "font-src 'self' data:",
        "connect-src 'self'",
        "frame-src https://player.vimeo.com",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'",
    ]
)


class PortalEstatico(StaticFiles):
    """O portal exportado pelo Next: cada rota é um index.html na pasta dela.

    Três casos fogem do arquivo em disco:

    * `/enviar/<token>` cai na página `/enviar/`, que lê o token do endereço —
      o link chega pelo chat e não existe como arquivo;
    * `/api/...` que não é rota devolve JSON, não a página 404;
    * o resto que não existe recebe a 404 do portal, com status 404.
    """

    async def get_response(self, path: str, scope):  # type: ignore[override]
        caminho = path.replace("\\", "/")  # o Starlette normaliza com o separador do sistema
        if caminho.startswith("api/"):
            return JSONResponse(status_code=404, content={"detail": "Não encontrado."})
        try:
            resposta = await super().get_response(path, scope)
        except HTTPException as e:
            if e.status_code != 404:
                raise
            resposta = JSONResponse(status_code=404, content={"detail": "Não encontrado."})
        if resposta.status_code == 404 and caminho.startswith("enviar/"):
            resposta = await super().get_response("enviar/index.html", scope)
        resposta.headers["Content-Security-Policy"] = CSP_DO_PORTAL
        return resposta


class CabecalhosDeSeguranca:
    """Headers que valem para toda resposta: portal, API, MCP e OAuth."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def enviar(mensagem) -> None:
            if mensagem["type"] == "http.response.start":
                headers = MutableHeaders(scope=mensagem)
                headers.setdefault("X-Content-Type-Options", "nosniff")
                headers.setdefault("X-Frame-Options", "DENY")
                headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
                headers.setdefault(
                    "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"
                )
                if settings.sessao_cookie_seguro:
                    headers.setdefault("Strict-Transport-Security", "max-age=31536000")
            await send(mensagem)

        await self.app(scope, receive, enviar)


class BarraFinalDoMcp:
    """Faz /mcp e /mcp/ apontarem para o mesmo lugar.

    O endpoint MCP é uma rota exata em /mcp. Sem esta normalização, um cliente
    configurado com a barra no fim cairia no portal estático e receberia HTML
    onde espera JSON-RPC.
    """

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] == "http" and scope["path"] == f"{CAMINHO_MCP}/":
            scope = {**scope, "path": CAMINHO_MCP, "raw_path": CAMINHO_MCP.encode()}
        await self.app(scope, receive, send)


# O portal e a API REST: um FastAPI comum, que vira o "resto do mundo" do app
# ASGI montado mais abaixo.
api = FastAPI(title="Plataforma Educacional — POC MCP", version="0.1.0")


@api.exception_handler(NaoEncontrado)
def _nao_encontrado(_: Request, exc: NaoEncontrado) -> JSONResponse:
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@api.exception_handler(NaoAutorizado)
def _nao_autorizado(_: Request, exc: NaoAutorizado) -> JSONResponse:
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@api.exception_handler(CredenciaisInvalidas)
def _credenciais(_: Request, exc: CredenciaisInvalidas) -> JSONResponse:
    return JSONResponse(status_code=401, content={"detail": str(exc)})


@api.exception_handler(MuitasTentativas)
def _tentativas(_: Request, exc: MuitasTentativas) -> JSONResponse:
    return JSONResponse(
        status_code=429, content={"detail": str(exc)}, headers={"Retry-After": str(exc.segundos)}
    )


@api.exception_handler(AprovacaoNecessaria)
def _aprovacao(_: Request, exc: AprovacaoNecessaria) -> JSONResponse:
    return JSONResponse(status_code=409, content={"detail": str(exc)})


@api.exception_handler(RegraDeNegocio)
def _regra(_: Request, exc: RegraDeNegocio) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": str(exc)})


@api.exception_handler(VimeoErro)
def _vimeo(_: Request, exc: VimeoErro) -> JSONResponse:
    # Falha do lado do Vimeo (fora do ar, token sem escopo, pasta que não existe):
    # a mensagem já é escrita para gente, e o problema não é deste servidor.
    return JSONResponse(status_code=502, content={"detail": str(exc)})


# Os dois handlers abaixo existem porque o portal mostra `detail` ao usuário.
# Sem eles, banco fora do ar e bug respondem "Internal Server Error" em texto
# puro — e a tela de login fica sem uma frase para exibir.

MENSAGEM_BANCO_FORA = "Banco de dados indisponível no momento. Tente novamente em instantes."
MENSAGEM_ERRO_INTERNO = "Erro interno no servidor. Tente novamente em instantes."


@api.exception_handler(OperationalError)
def _banco_indisponivel(request: Request, exc: OperationalError) -> JSONResponse:
    # Conexão recusada, host errado, senha inválida: problema de infraestrutura,
    # não do usuário. Host e usuário do banco ficam só no log.
    logger.error("banco indisponível em %s %s: %s", request.method, request.url.path, exc)
    return JSONResponse(status_code=503, content={"detail": MENSAGEM_BANCO_FORA})


@api.exception_handler(Exception)
def _erro_inesperado(_: Request, __: Exception) -> JSONResponse:
    # O traceback continua indo para o log: o Starlette relança a exceção
    # depois de enviar esta resposta.
    return JSONResponse(status_code=500, content={"detail": MENSAGEM_ERRO_INTERNO})


@api.get("/api/saude", tags=["infra"])
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
            "mcp": CAMINHO_MCP,
            "mcp_oauth": "github" if settings.oauth_mcp_ativo else "token-bearer",
            "modo_demo": get_settings().modo_demo,
        },
    )


api.include_router(auth_routes.router)
api.include_router(admin_routes.router)
api.include_router(aluno_routes.router)
api.include_router(envio_routes.router)

# O portal exportado, quando existe, é servido pelo mesmo host: mesma origem
# para o cookie da sessão e uma porta só. Em desenvolvimento, `npm run dev` na
# porta 3000 repassa /api para cá.
_portal = Path(__file__).resolve().parents[2] / "frontend" / "out"
if _portal.is_dir():
    api.mount("/", PortalEstatico(directory=str(_portal), html=True), name="frontend")
    logger.info("portal servido de %s", _portal)


# Quem hospeda é o app do MCP, não o FastAPI — e a ordem importa muito.
#
# O OAuth do MCP precisa de rotas na RAIZ do domínio (/authorize, /token,
# /register, /consent e os /.well-known/...): é lá que o cliente procura, e é
# o próprio FastMCP que as cria, a partir do `base_url`. Como um mount em "/"
# casa com qualquer caminho e encerra o roteamento, o portal estático não pode
# estar na frente delas — foi assim que a versão anterior deixou o claude.ai
# sem conseguir descobrir o servidor, recebendo o index.html no lugar do JSON.
#
# Então o app do MCP fica por fora (rotas de OAuth + /mcp) e o FastAPI entra
# como último recurso, cobrindo /api/... e o portal.
app = mcp.http_app(
    path=CAMINHO_MCP,
    middleware=[
        Middleware(
            CORSMiddleware,
            allow_origins=settings.lista_cors,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        ),
        Middleware(BarraFinalDoMcp),
        Middleware(CabecalhosDeSeguranca),
    ],
)
app.router.routes.append(Mount("/", app=api))


def main() -> None:
    import uvicorn

    uvicorn.run(app, host=settings.app_host, port=settings.app_port)


if __name__ == "__main__":
    main()
