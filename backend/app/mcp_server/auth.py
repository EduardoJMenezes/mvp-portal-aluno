"""Autenticação do MCP — duas portas, uma identidade.

O servidor aceita credencial de duas origens, porque os clientes MCP não
concordam entre si:

* **Token Bearer opaco**, emitido por `scripts/token_mcp.py`. É o que o Claude
  Code e os scripts usam: um header fixo no arquivo de configuração.
* **OAuth com login no GitHub**, para conector remoto (claude.ai). Lá não
  existe campo para header: ou o servidor fala OAuth, ou não conecta.

As duas terminam no mesmo lugar — um `AccessToken` cujos claims dizem QUEM é a
pessoa. O token não carrega permissão nenhuma; o que ela pode fazer continua
sendo decidido pelo papel dela no backend, a cada chamada. O GitHub é só a
porta de entrada: quem não estiver cadastrado como ADMIN ou GERENCIADOR na
plataforma não abre sessão, por mais válido que seja o login dele.

Ver [docs/MCP-OAUTH.md](../../../docs/MCP-OAUTH.md). A simplificação que a
seção 5 do MVP permitiu (token opaco em vez de servidor de autorização)
continua valendo para o Claude Code; o caminho do OAuth é o que a arquitetura
final pede.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime

import anyio
from fastmcp.server.auth import AccessToken, AuthProvider, MultiAuth, TokenVerifier
from fastmcp.server.auth.providers.github import GitHubProvider
from sqlalchemy import select

from app.config import get_settings
from app.db import SessionLocal
from app.identidade import Canal, Identidade
from app.models import Papel, TokenMCP, Usuario
from app.security import hash_token

logger = logging.getLogger("plataforma.mcp.auth")


def _claims_do_usuario(usuario: Usuario) -> dict:
    return {
        "usuario_id": usuario.id,
        "nome": usuario.nome,
        "email": usuario.email,
        "papel": usuario.papel,
    }


# --- porta 1: token opaco da plataforma --------------------------------------


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
            claims=_claims_do_usuario(usuario),
        )


class TokenDaPlataforma(TokenVerifier):
    async def verify_token(self, token: str) -> AccessToken | None:
        # O SQLAlchemy aqui é síncrono; sai do event loop para não travá-lo.
        return await anyio.to_thread.run_sync(_consultar, token)


# --- porta 2: login no GitHub ------------------------------------------------


def _operador_do_github(identificadores: list[str]) -> dict | None:
    """Traduz quem logou no GitHub no operador cadastrado aqui.

    Procura nesta ordem: o que o mapa `MCP_OAUTH_OPERADORES` disser para o
    login ou para o e-mail, e depois o próprio e-mail do GitHub. O e-mail
    costuma vir vazio (a API só devolve o público), então na prática quem
    resolve é o mapa, pelo login.
    """
    mapa = get_settings().mapa_operadores_oauth
    candidatos = [mapa[i] for i in identificadores if i in mapa]
    candidatos += [i for i in identificadores if "@" in i]

    with SessionLocal() as db:
        for email in candidatos:
            usuario = db.scalar(select(Usuario).where(Usuario.email == email))
            if usuario is not None and usuario.papel in Papel.OPERADORES:
                return _claims_do_usuario(usuario)
    return None


class GitHubDaPlataforma(GitHubProvider):
    """O GitHub diz quem entrou; o cadastro daqui diz se essa pessoa opera.

    Sem esta checagem, qualquer conta do GitHub abriria sessão no servidor e só
    esbarraria no papel ao chamar uma tool. Recusar já na verificação do token é
    mais honesto: o cliente recebe 401 em vez de um catálogo de ferramentas que
    ele não pode usar.
    """

    async def verify_token(self, token: str) -> AccessToken | None:
        acesso = await super().verify_token(token)
        if acesso is None:
            return None

        claims = acesso.claims or {}
        identificadores = [
            str(claims[campo]).lower() for campo in ("login", "email") if claims.get(campo)
        ]
        operador = await anyio.to_thread.run_sync(_operador_do_github, identificadores)
        if operador is None:
            logger.warning(
                "login do GitHub %s não corresponde a nenhum operador; sessão recusada",
                identificadores or ["(sem identificador)"],
            )
            return None

        return acesso.model_copy(update={"claims": {**claims, **operador}})


def _armazenamento_oauth():
    """Onde o proxy OAuth guarda registro de cliente e tokens.

    O padrão do FastMCP é um arquivo em disco — e no Railway o disco morre a
    cada deploy, o que derrubaria o conector do claude.ai toda vez que a gente
    sobe uma versão. Guardando no Postgres, a conexão sobrevive ao deploy.
    """
    url = get_settings().database_url
    if "postgresql" not in url:
        return None

    from key_value.aio.stores.postgresql import PostgreSQLStore

    # O store fala asyncpg; a URL do resto do projeto carrega o driver psycopg.
    # A tabela é criada sozinha na primeira gravação.
    return PostgreSQLStore(url=url.replace("+psycopg", ""), table_name="oauth_mcp_kv")


def construir_auth() -> AuthProvider:
    """Monta a autenticação conforme o que estiver configurado.

    Sem as variáveis do GitHub, o servidor fica como sempre foi: só o token
    opaco. É assim que a POC roda na máquina de quem desenvolve, sem precisar
    de app OAuth nenhum.
    """
    settings = get_settings()
    plataforma = TokenDaPlataforma()

    if not settings.oauth_mcp_ativo:
        logger.info("MCP sem OAuth: só o token Bearer da plataforma")
        return plataforma

    github = GitHubDaPlataforma(
        client_id=settings.mcp_oauth_github_client_id,  # type: ignore[arg-type]
        client_secret=settings.mcp_oauth_github_client_secret,  # type: ignore[arg-type]
        # A URL pública da raiz: é dela que saem /authorize, /token e os
        # .well-known, que o cliente procura na raiz do domínio.
        base_url=settings.mcp_base_url,  # type: ignore[arg-type]
        # A POC só lê o perfil, para saber quem entrou.
        required_scopes=["read:user"],
        client_storage=_armazenamento_oauth(),
    )
    logger.info("MCP com OAuth do GitHub em %s", settings.mcp_base_url)

    # O OAuth responde pelas rotas e pela metadata; o token opaco continua
    # valendo como segunda credencial, para o Claude Code e os scripts.
    return MultiAuth(server=github, verifiers=[plataforma])


# --- identidade da chamada ---------------------------------------------------


def identidade_da_sessao() -> Identidade:
    """Traduz o token da chamada atual na Identidade que os services esperam.

    Vale para as duas portas: quando a sessão veio do GitHub, os claims já
    foram completados com o operador correspondente, na verificação do token.
    """
    from fastmcp.exceptions import ToolError
    from fastmcp.server.dependencies import get_access_token

    token = get_access_token()
    if token is None or not token.claims:
        raise ToolError("Sessão MCP sem identidade. Reconecte o servidor com um token válido.")

    c = token.claims
    if "usuario_id" not in c:
        raise ToolError(
            "Sessão autenticada, mas sem operador correspondente na plataforma. "
            "Peça para vincular este login a um ADMIN ou GERENCIADOR."
        )

    return Identidade(
        usuario_id=c["usuario_id"],
        nome=c["nome"],
        email=c["email"],
        papel=c["papel"],
        canal=Canal.MCP,
    )
