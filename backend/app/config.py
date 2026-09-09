"""Configuração da POC. Tudo vem de variável de ambiente / .env."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Postgres é a fonte de verdade dos dados (ver seção 3 do MVP).
    database_url: str = "postgresql+psycopg:///plataforma_mvp"

    # Sessão do portal (professor/aluno no navegador). Nada a ver com o token
    # do MCP: são dois canais de identidade diferentes, de propósito.
    jwt_secret: str = "dev-only-trocar-antes-de-qualquer-coisa-real"
    jwt_expira_horas: int = 12

    # Vimeo: sem token o backend usa o acervo de demonstração embutido, para
    # que a POC rode ponta a ponta antes de existir credencial.
    vimeo_access_token: str | None = None
    vimeo_api_base: str = "https://api.vimeo.com"

    app_host: str = "127.0.0.1"
    app_port: int = 8000
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"

    @property
    def lista_cors(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def vimeo_real(self) -> bool:
        return bool(self.vimeo_access_token and self.vimeo_access_token.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
