"""A API REST do simulado: o que o front novo e o Claude Code vão chamar."""

from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient

from app import main
from app.models import TokenMCP
from app.security import cria_jwt, hash_token, novo_token_mcp

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32


def _portal(ident) -> dict:
    return {"authorization": f"Bearer {cria_jwt(ident.usuario_id, ident.papel)}"}


def _token_mcp(db, ident) -> dict:
    valor = novo_token_mcp()
    db.add(TokenMCP(usuario_id=ident.usuario_id, nome="claude code", token_hash=hash_token(valor)))
    db.commit()
    return {"authorization": f"Bearer {valor}"}


def test_claude_code_monta_o_simulado_e_anexa_a_figura_mas_nao_publica(db, mundo):
    api = TestClient(main.app)
    claude_code = _token_mcp(db, mundo["professor"])
    agora = datetime.now(UTC)

    r = api.post("/api/admin/simulados", headers=claude_code, json={
        "turmas": ["Extensivo 2027"],
        "titulo": "Simulado 30",
        "questoes": [
            mundo["questoes"][0].id,
            {"enunciado": "Observe a figura.", "alternativas": {l: l for l in "ABCDE"},
             "gabarito": "D", "imagem_pendente": True},
        ],
        "abre_em": (agora + timedelta(hours=1)).isoformat(),
        "fecha_em": (agora + timedelta(hours=2)).isoformat(),
        "duracao_minutos": 60,
    })
    assert r.status_code == 200, r.text
    rascunho = r.json()
    nova = rascunho["simulado"]["questoes"][1]["questao_id"]

    svg = api.post(f"/api/admin/questoes/{nova}/figuras", headers=claude_code,
                   files={"arquivo": ("a.svg", b"<svg onload='x()'/>", "image/svg+xml")})
    assert svg.status_code == 400

    png = api.post(f"/api/admin/questoes/{nova}/figuras", headers=claude_code,
                   files={"arquivo": ("figura.png", PNG, "image/png")}, data={"parte": "ENUNCIADO"})
    assert png.status_code == 200 and png.json()["imagem_pendente"] is False

    figura = api.get(f"/api/aluno/figuras/{png.json()['figura_id']}",
                     headers=_portal(mundo["professor"]))
    assert figura.content == PNG and figura.headers["content-type"] == "image/png"

    # O token do MCP abre a API, mas não faz as vezes do professor no navegador.
    barrado = api.post(f"/api/admin/rascunhos/{rascunho['rascunho_id']}/publicar",
                       headers=claude_code)
    assert barrado.status_code == 403

    publicado = api.post(f"/api/admin/rascunhos/{rascunho['rascunho_id']}/publicar",
                         headers=_portal(mundo["professor"]))
    assert publicado.status_code == 200, publicado.text


def test_aluno_nao_alcanca_a_api_do_professor_nem_a_figura_antes_da_prova(db, mundo):
    api = TestClient(main.app)
    joao = _portal(mundo["joao"])
    questao = mundo["questoes"][0].id
    anexo = api.post(f"/api/admin/questoes/{questao}/figuras", headers=_portal(mundo["professor"]),
                     files={"arquivo": ("f.png", PNG, "image/png")}).json()

    assert api.get("/api/admin/simulados", headers=joao).status_code == 403
    assert api.get(f"/api/aluno/figuras/{anexo['figura_id']}", headers=joao).status_code == 403


def test_toda_tool_do_simulado_tem_endpoint(schema):
    caminhos = TestClient(main.app).get("/openapi.json").json()["paths"]

    for caminho, metodos in {
        "/api/admin/simulados": {"get", "post"},
        "/api/admin/simulados/{simulado}": {"get", "patch", "delete"},
        "/api/admin/simulados/{simulado}/ranking": {"get"},
        "/api/admin/questoes/{questao_id}": {"get", "patch", "delete"},
        "/api/admin/questoes/{questao_id}/figuras": {"post"},
        "/api/aluno/figuras/{figura_id}": {"get"},
        "/api/admin/importacoes": {"post"},
        "/api/admin/importacoes/{importacao_id}": {"get"},
        "/api/importacoes/{token}/arquivo": {"post"},
        "/api/admin/turmas/{turma}/modulos": {"post"},
        "/api/admin/vimeo/importacoes": {"post"},
        "/api/aluno/simulados/{simulado_id}/entregar": {"post"},
        "/api/aluno/simulados/{simulado_id}/resultado": {"get"},
    }.items():
        assert metodos <= set(caminhos.get(caminho, {})), caminho
