"""Configuração da POC. Tudo vem de variável de ambiente / .env."""

from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Postgres é a fonte de verdade dos dados (ver seção 3 do MVP).
    database_url: str = "postgresql+psycopg:///plataforma_mvp"

    # Sessão do portal (professor/aluno no navegador). Nada a ver com o token
    # do MCP: são dois canais de identidade diferentes, de propósito.
    jwt_secret: str = "dev-only-trocar-antes-de-qualquer-coisa-real"
    jwt_expira_horas: int = 12

    # A sessão viaja num cookie httpOnly, que o JavaScript não lê. Em produção
    # (https) ele é Secure; só o teste e o desenvolvimento em http desligam.
    sessao_cookie_seguro: bool = True

    # Modo demonstração: a tela de login oferece entrar como as contas de
    # exemplo do seed (e-mails .demo), sem senha. Ligado, qualquer visitante
    # vira ADMIN com um clique — nunca em ambiente com gente de verdade.
    modo_demo: bool = False

    # Vimeo: sem token o backend usa o acervo de demonstração embutido, para
    # que a POC rode ponta a ponta antes de existir credencial.
    vimeo_access_token: str | None = None
    vimeo_api_base: str = "https://api.vimeo.com"

    # OAuth do MCP. O Claude Code manda um header fixo e se contenta com o
    # token opaco; conector remoto (claude.ai) só fala OAuth. Preenchendo as
    # três variáveis abaixo, o servidor passa a aceitar os dois — ver
    # docs/MCP-OAUTH.md. Vazias, fica só o Bearer, como sempre foi.
    mcp_base_url: str | None = None
    mcp_oauth_github_client_id: str | None = None
    mcp_oauth_github_client_secret: str | None = None

    # Quem do GitHub corresponde a qual operador da plataforma:
    # "EduardoJMenezes=professor@escola.demo, outro@git.hub=chefe@escola.demo".
    # Sem entrada aqui, vale o e-mail público do GitHub, se ele existir como
    # ADMIN ou GERENCIADOR. Login do GitHub é case-insensitive.
    mcp_oauth_operadores: str = ""

    app_host: str = "127.0.0.1"
    app_port: int = 8000
    cors_origins: str = "http://localhost:3000,http://127.0.0.1:3000"

    @field_validator("database_url", mode="before")
    @classmethod
    def _driver_psycopg(cls, valor: object) -> object:
        """Aceita a URL como Railway e Heroku a entregam: `postgresql://` ou
        `postgres://`, sem driver. O SQLAlchemy mapearia esses esquemas para o
        psycopg2, que não está instalado — aqui só existe o psycopg 3."""
        if isinstance(valor, str):
            for prefixo in ("postgresql://", "postgres://"):
                if valor.startswith(prefixo):
                    return "postgresql+psycopg://" + valor[len(prefixo):]
        return valor

    @property
    def lista_cors(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def vimeo_real(self) -> bool:
        return bool(self.vimeo_access_token and self.vimeo_access_token.strip())

    @property
    def oauth_mcp_ativo(self) -> bool:
        """Só liga o OAuth quando as três peças existem — meio configurado não
        vale: o cliente descobriria o /authorize e bateria num 500."""
        return bool(
            self.mcp_base_url
            and self.mcp_oauth_github_client_id
            and self.mcp_oauth_github_client_secret
        )

    @property
    def mapa_operadores_oauth(self) -> dict[str, str]:
        """Identificador do GitHub (login ou e-mail) -> e-mail na plataforma."""
        mapa: dict[str, str] = {}
        for par in self.mcp_oauth_operadores.split(","):
            chave, _, valor = par.partition("=")
            if chave.strip() and valor.strip():
                mapa[chave.strip().lower()] = valor.strip().lower()
        return mapa


@lru_cache
def get_settings() -> Settings:
    return Settings()
