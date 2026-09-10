"""O portal mostra `detail` ao usuário: toda falha da API precisa vir em JSON,
em português e sem vazar infraestrutura — inclusive quando o banco está fora."""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import main
from app.db import get_db

# Porta 1 em loopback: conexão recusada na hora, sem depender de rede nem de
# tempo limite.
_SEM_BANCO = create_engine(
    "postgresql+psycopg://x:x@127.0.0.1:1/x", connect_args={"connect_timeout": 2}
)

CREDENCIAIS = {"email": "professor@escola.demo", "senha": "demo1234"}


@pytest.fixture
def cliente_sem_banco(monkeypatch):
    def _sessao():
        db = sessionmaker(bind=_SEM_BANCO)()
        try:
            yield db
        finally:
            db.close()

    main.app.dependency_overrides[get_db] = _sessao
    monkeypatch.setattr(main, "engine", _SEM_BANCO)
    try:
        yield TestClient(main.app, raise_server_exceptions=False)
    finally:
        main.app.dependency_overrides.pop(get_db, None)


def test_login_com_banco_fora_responde_503_em_json(cliente_sem_banco) -> None:
    r = cliente_sem_banco.post("/api/login", json=CREDENCIAIS)
    assert r.status_code == 503
    assert r.json() == {"detail": main.MENSAGEM_BANCO_FORA}


def test_saude_reprova_o_deploy_quando_o_banco_esta_fora(cliente_sem_banco) -> None:
    r = cliente_sem_banco.get("/api/saude")
    assert r.status_code == 503
    assert r.json()["ok"] is False
    assert r.json()["banco"] == "indisponivel"


def test_saude_com_banco_no_ar() -> None:
    r = TestClient(main.app).get("/api/saude")
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["banco"] == "ok"


def test_erro_inesperado_responde_500_em_json() -> None:
    def _explode():
        raise RuntimeError("bug simulado")

    main.app.dependency_overrides[get_db] = _explode
    try:
        r = TestClient(main.app, raise_server_exceptions=False).post("/api/login", json=CREDENCIAIS)
    finally:
        main.app.dependency_overrides.pop(get_db, None)
    assert r.status_code == 500
    assert r.json() == {"detail": main.MENSAGEM_ERRO_INTERNO}


def test_email_invalido_vem_como_lista_de_campos() -> None:
    # É o formato que o frontend traduz em "Campos inválidos: email.".
    r = TestClient(main.app).post("/api/login", json={"email": "professor", "senha": "x"})
    assert r.status_code == 422
    assert [e["loc"][-1] for e in r.json()["detail"]] == ["email"]
