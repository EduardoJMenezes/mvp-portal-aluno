"""A URL do banco chega em formas diferentes conforme quem a fornece.

O Railway (e o Heroku) entregam `postgresql://` ou `postgres://`, sem driver;
o SQLAlchemy mapearia isso para o psycopg2, que não está instalado.
"""

import pytest

from app.config import Settings


@pytest.mark.parametrize(
    ("entrada", "esperado"),
    [
        ("postgresql://u:s@host:5432/db", "postgresql+psycopg://u:s@host:5432/db"),
        ("postgres://u:s@host:5432/db", "postgresql+psycopg://u:s@host:5432/db"),
        ("postgresql+psycopg://u:s@host:5432/db", "postgresql+psycopg://u:s@host:5432/db"),
        ("postgresql+psycopg:///plataforma_mvp", "postgresql+psycopg:///plataforma_mvp"),
    ],
)
def test_url_do_banco_usa_o_driver_instalado(entrada: str, esperado: str) -> None:
    assert Settings(database_url=entrada).database_url == esperado
