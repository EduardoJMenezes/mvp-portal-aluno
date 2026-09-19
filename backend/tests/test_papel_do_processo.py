"""O papel do processo: o que cada serviço serve, e o que ele não serve.

Isto existe porque a separação é o que libera rodar o portal em vários
processos (docs/CARGA.md), e um erro aqui é silencioso: o portal continuaria
respondendo, mas o conector do professor cairia no processo errado.
"""

from fastapi.testclient import TestClient

from app.main import montar


def _caminhos(app) -> list[str]:
    alvo = app
    while not hasattr(alvo, "router"):  # desembrulha os middlewares
        alvo = alvo.app
    return [getattr(r, "path", "") for r in alvo.router.routes]


def test_papel_portal_nao_serve_o_mcp():
    """Se servisse, com quatro processos a sessão do conector cairia no errado."""
    app = montar("portal")
    caminhos = _caminhos(app)
    assert "/mcp" not in caminhos
    assert "/api/saude" in caminhos  # a sonda do Railway continua de pé


def test_papel_portal_mantem_os_cabecalhos_de_seguranca():
    """Eles eram aplicados pelo app do MCP; sem ele, precisam continuar vindo."""
    r = TestClient(montar("portal")).get("/api/saude")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
    assert "strict-origin-when-cross-origin" in r.headers["referrer-policy"]


def test_papel_mcp_nao_serve_o_portal_e_tem_saude_propria():
    app = montar("mcp")
    caminhos = _caminhos(app)
    assert "/mcp" in caminhos
    assert "/saude" in caminhos  # o Railway aprova o deploy por aqui
    r = TestClient(app).get("/saude")
    assert r.status_code == 200 and r.json()["papel"] == "mcp"
    assert TestClient(app).get("/api/saude").status_code == 404


def test_papel_padrao_continua_servindo_os_dois():
    """O padrão é o de hoje: trocar o valor é uma decisão, nunca um efeito."""
    app = montar("tudo")
    assert "/mcp" in _caminhos(app)
    # O portal entra como último recurso, num mount em "/".
    assert TestClient(app).get("/api/naoexiste").status_code == 404
