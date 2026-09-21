"""Sessão do portal e contas: o que protege o login e o que só o portal faz.

Cada teste de segurança começa pelo abuso — senha errada, conta inexistente,
agente com token do MCP tentando criar conta — e só depois olha o caminho feliz.
"""

from datetime import UTC, datetime, timedelta

import jwt
import pytest
from fastapi.testclient import TestClient
from starlette.applications import Starlette
from starlette.routing import Mount

from app import main
from app.config import get_settings
from app.errors import RegraDeNegocio
from app.models import Papel, TokenMCP, Usuario
from app.security import cria_jwt, hash_senha, hash_token, novo_token_mcp
from app.services import contas

SENHA = "cavalo-bateria-grampo"
NOVA = "girassol-no-telhado"


def _portal(ident) -> dict:
    return {"authorization": f"Bearer {cria_jwt(ident.usuario_id, ident.papel)}"}


def _conta(db, email="ana@x.demo", papel=Papel.ALUNO, nome="Ana Clara") -> Usuario:
    usuario = Usuario(nome=nome, email=email, senha_hash=hash_senha(SENHA), papel=papel)
    db.add(usuario)
    db.commit()
    return usuario


@pytest.fixture
def modo_demo(monkeypatch):
    monkeypatch.setenv("MODO_DEMO", "true")
    get_settings.cache_clear()
    yield
    monkeypatch.delenv("MODO_DEMO")
    get_settings.cache_clear()


# --- entrar ------------------------------------------------------------------


def test_login_grava_a_sessao_em_cookie_httponly_e_nao_devolve_token(db, mundo):
    _conta(db)
    api = TestClient(main.app)

    r = api.post("/api/login", json={"email": "ana@x.demo", "senha": SENHA})
    assert r.status_code == 200, r.text
    assert "token" not in r.json()
    assert r.json()["usuario"] == {
        "id": r.json()["usuario"]["id"], "nome": "Ana Clara", "email": "ana@x.demo",
        "papel": "ALUNO", "turmas": [], "trocar_senha": False,
    }
    cookie = r.headers["set-cookie"].lower()
    for trecho in ("sessao=", "httponly", "samesite=strict", "path=/api"):
        assert trecho in cookie
    assert api.get("/api/eu").json()["email"] == "ana@x.demo"

    api.post("/api/logout")
    assert api.get("/api/eu").status_code == 401


def test_login_errado_responde_igual_exista_ou_nao_a_conta(db, mundo):
    _conta(db)
    api = TestClient(main.app)

    errada = api.post("/api/login", json={"email": "ana@x.demo", "senha": "outra-senha-qualquer"})
    inexistente = api.post("/api/login", json={"email": "ninguem@x.demo", "senha": SENHA})

    assert errada.status_code == inexistente.status_code == 401
    assert errada.json() == inexistente.json() == {"detail": "E-mail ou senha incorretos."}
    assert "set-cookie" not in errada.headers


def test_falhas_seguidas_travam_a_conta_mesmo_com_a_senha_certa(db, mundo):
    _conta(db)
    api = TestClient(main.app)
    for _ in range(contas.FALHAS_POR_CONTA):
        assert api.post("/api/login", json={"email": "ana@x.demo", "senha": "errada-errada"}).status_code == 401

    travado = api.post("/api/login", json={"email": "ana@x.demo", "senha": SENHA})
    assert travado.status_code == 429
    assert int(travado.headers["retry-after"]) > 0
    assert travado.json()["detail"].startswith("Muitas tentativas de entrar.")


def test_a_trava_passa_quando_a_janela_anda(db):
    """A contagem é do banco: vale para os quatro processos do portal, não só para este."""
    agora = datetime.now(UTC)
    contas._registrar_falha(db, ["ana@x.demo"] * contas.FALHAS_POR_CONTA, agora)

    assert contas._espera(db, "ana@x.demo", contas.FALHAS_POR_CONTA, agora) > 0
    depois = agora + contas.JANELA + timedelta(seconds=1)
    assert contas._espera(db, "ana@x.demo", contas.FALHAS_POR_CONTA, depois) == 0


def test_cookie_de_outra_origem_nao_escreve(db, mundo):
    _conta(db)
    api = TestClient(main.app)
    api.post("/api/login", json={"email": "ana@x.demo", "senha": SENHA})

    atacante = {"origin": "https://site-do-atacante.example"}
    r = api.post("/api/conta/senha", headers=atacante, json={"senha_atual": SENHA, "nova_senha": NOVA})
    assert r.status_code == 403


# --- senha -------------------------------------------------------------------


def test_senha_curta_obvia_ou_com_o_nome_e_recusada():
    maria = Usuario(nome="Maria Souza", email="maria.souza@x.demo", senha_hash="", papel=Papel.ALUNO)
    for fraca in ("curta", "1234567890", "aaaaaaaaaaaa", "maria-2027-xyz", "souza123456", "ç" * 40):
        with pytest.raises(RegraDeNegocio):
            contas.validar_nova_senha(fraca, maria)
    contas.validar_nova_senha(NOVA, maria)


def test_aluno_cadastrado_pelo_professor_so_usa_o_portal_depois_de_trocar_a_senha(db, mundo):
    professor = _portal(mundo["professor"])
    r = TestClient(main.app).post(
        f"/api/admin/turmas/{mundo['turma_2027'].id}/alunos", headers=professor,
        json={"email": "bia@x.demo", "nome": "Beatriz Lima"},
    )
    assert r.status_code == 200, r.text
    temporaria = r.json()["senha_temporaria"]
    assert len(temporaria) == 12 and r.json()["aluno"]["senha_temporaria"] is True

    aluno = TestClient(main.app)
    entrou = aluno.post("/api/login", json={"email": "bia@x.demo", "senha": temporaria})
    assert entrou.json()["usuario"]["trocar_senha"] is True
    assert entrou.json()["usuario"]["turmas"] == ["Extensivo 2027"]
    assert aluno.get("/api/aluno/conteudo").status_code == 403

    com_o_nome = aluno.post("/api/conta/senha", json={"senha_atual": temporaria, "nova_senha": "beatriz12345"})
    assert com_o_nome.status_code == 400
    trocou = aluno.post("/api/conta/senha", json={"senha_atual": temporaria, "nova_senha": NOVA})
    assert trocou.status_code == 200, trocou.text
    assert trocou.json()["usuario"]["trocar_senha"] is False
    assert aluno.get("/api/aluno/conteudo").status_code == 200


def test_trocar_a_senha_derruba_a_sessao_antiga_e_mantem_a_nova(db, mundo):
    usuario = _conta(db)
    antes = datetime.now(UTC) - timedelta(minutes=5)
    antiga = jwt.encode(
        {"sub": str(usuario.id), "papel": usuario.papel, "iat": antes, "exp": antes + timedelta(hours=2)},
        get_settings().jwt_secret, algorithm="HS256",
    )
    velha = {"authorization": f"Bearer {antiga}"}
    api = TestClient(main.app)
    assert api.get("/api/eu", headers=velha).status_code == 200

    api.post("/api/login", json={"email": "ana@x.demo", "senha": SENHA})
    errada = api.post("/api/conta/senha", json={"senha_atual": "nao-e-essa-nao", "nova_senha": NOVA})
    assert errada.status_code == 400
    assert api.post("/api/conta/senha", json={"senha_atual": SENHA, "nova_senha": NOVA}).status_code == 200

    assert api.get("/api/eu", headers=velha).status_code == 401
    assert api.get("/api/eu").status_code == 200


# --- modo demonstração -------------------------------------------------------


def test_sem_modo_demo_nao_ha_entrada_sem_senha(db, mundo):
    _conta(db, email="professor@escola.demo", papel=Papel.ADMIN)
    api = TestClient(main.app)
    assert api.get("/api/sessao/config").json() == {"modo_demo": False, "contas_demo": []}
    assert api.post("/api/demo/entrar", json={"email": "professor@escola.demo"}).status_code == 404


def test_modo_demo_entra_so_nas_contas_de_exemplo(db, mundo, modo_demo):
    _conta(db, email="professor@escola.demo", papel=Papel.ADMIN, nome="Helena")
    api = TestClient(main.app)

    config = api.get("/api/sessao/config").json()
    assert config["modo_demo"] is True
    assert config["contas_demo"] == [
        {"nome": "Helena", "email": "professor@escola.demo", "papel": "ADMIN", "turmas": []}
    ]

    assert api.post("/api/demo/entrar", json={"email": "professor@escola.demo"}).status_code == 200
    assert api.get("/api/admin/turmas").status_code == 200
    assert TestClient(main.app).post("/api/demo/entrar", json={"email": "h@x.demo"}).status_code == 403


# --- alunos, turmas e assuntos -----------------------------------------------


def test_professor_matricula_tira_da_turma_e_redefine_a_senha(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    turma = mundo["turma_2027"].id
    pedro = mundo["pedro"]

    def nomes():
        return [a["nome"] for a in api.get(f"/api/admin/turmas/{turma}/alunos", headers=professor).json()["alunos"]]

    assert nomes() == ["João"]
    ja_tem_conta = api.post(f"/api/admin/turmas/{turma}/alunos", headers=professor, json={"email": "pedro@x.demo"})
    assert ja_tem_conta.status_code == 200 and ja_tem_conta.json()["senha_temporaria"] is None
    assert nomes() == ["João", "Pedro"]

    repetido = api.post(f"/api/admin/turmas/{turma}/alunos", headers=professor, json={"email": "pedro@x.demo"})
    assert repetido.status_code == 400
    operador = api.post(f"/api/admin/turmas/{turma}/alunos", headers=professor, json={"email": "h@x.demo", "nome": "H"})
    assert operador.status_code == 400

    nova = api.post(f"/api/admin/alunos/{pedro.usuario_id}/senha", headers=professor)
    assert nova.status_code == 200 and len(nova.json()["senha_temporaria"]) == 12

    assert api.delete(f"/api/admin/turmas/{turma}/alunos/{pedro.usuario_id}", headers=professor).status_code == 200
    assert nomes() == ["João"]
    assert api.get(f"/api/admin/turmas/{turma}/alunos", headers=_portal(mundo["joao"])).status_code == 403


def test_agente_com_token_do_mcp_le_mas_nao_cria_conta_nem_token(db, mundo):
    valor = novo_token_mcp()
    db.add(TokenMCP(usuario_id=mundo["professor"].usuario_id, nome="claude", token_hash=hash_token(valor)))
    db.commit()
    agente = {"authorization": f"Bearer {valor}"}
    api = TestClient(main.app)
    turma = mundo["turma_2027"].id

    assert api.get(f"/api/admin/turmas/{turma}/alunos", headers=agente).status_code == 200
    novo = api.post(f"/api/admin/turmas/{turma}/alunos", headers=agente, json={"email": "novo@x.demo", "nome": "Novo"})
    assert novo.status_code == 403
    assert api.post(f"/api/admin/alunos/{mundo['joao'].usuario_id}/senha", headers=agente).status_code == 403
    assert api.post("/api/admin/tokens", headers=agente, json={"nome": "outro"}).status_code == 403


def test_token_do_mcp_emitido_listado_e_revogado_pelo_portal(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])

    emitido = api.post("/api/admin/tokens", headers=professor, json={"nome": "Claude Code"}).json()
    assert emitido["token"].startswith("pvm_")
    listados = api.get("/api/admin/tokens", headers=professor).json()
    assert [t["nome"] for t in listados] == ["Claude Code"] and "token" not in listados[0]

    agente = {"authorization": f"Bearer {emitido['token']}"}
    assert api.get("/api/admin/turmas", headers=agente).status_code == 200
    assert api.delete(f"/api/admin/tokens/{emitido['id']}", headers=professor).status_code == 200
    assert api.get("/api/admin/turmas", headers=agente).status_code == 401


def test_turma_criada_e_renomeada_sem_nome_repetido(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])

    criada = api.post("/api/admin/turmas", headers=professor, json={"nome": "Extensivo 2028", "ano": 2028})
    assert criada.status_code == 200, criada.text
    repetida = api.post("/api/admin/turmas", headers=professor, json={"nome": "extensivo 2027", "ano": 2027})
    assert repetida.status_code == 400

    turma = criada.json()["id"]
    assert api.patch(f"/api/admin/turmas/{turma}", headers=professor, json={"nome": "Semi 2028"}).json() == {
        "id": turma, "nome": "Semi 2028", "ano": 2028,
    }
    assert api.patch(f"/api/admin/turmas/{turma}", headers=_portal(mundo["joao"]), json={"nome": "X"}).status_code == 403


def test_assunto_e_subassunto_renomeados_e_removidos(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    assunto, sub = mundo["assunto"].id, mundo["subassunto"].id

    assert api.patch(f"/api/admin/assuntos/{assunto}", headers=professor, json={"nome": "Atomística"}).status_code == 400
    renomeado = api.patch(f"/api/admin/assuntos/{assunto}", headers=professor, json={"nome": "Cálculo estequiométrico"})
    assert renomeado.json()["assunto"] == "Cálculo estequiométrico"
    sub_renomeado = api.patch(
        f"/api/admin/assuntos/{assunto}/subassuntos/{sub}", headers=professor, json={"nome": "Rendimento"}
    )
    assert sub_renomeado.json()["subassunto"] == "Rendimento"

    assert api.delete(f"/api/admin/assuntos/{assunto}/subassuntos/{sub}", headers=professor).status_code == 200
    assert api.delete(f"/api/admin/assuntos/{mundo['atomistica'].id}", headers=professor).status_code == 200
    assert api.get("/api/admin/assuntos", headers=professor).json() == [
        {"id": assunto, "nome": "Cálculo estequiométrico", "subassuntos": []}
    ]


# --- questões e histórico ----------------------------------------------------


def test_questoes_por_trecho_do_enunciado_e_por_pagina(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])

    def enunciados(consulta: str) -> list[str]:
        return [q["enunciado"] for q in api.get(f"/api/admin/questoes?{consulta}", headers=professor).json()]

    assert enunciados("busca=nciado 2") == ["Enunciado 2"]
    assert enunciados("busca=%25") == []  # o % digitado é literal, não curinga
    assert enunciados("limite=1&offset=1") == ["Enunciado 2"]
    assert api.get("/api/admin/questoes?limite=500", headers=professor).status_code == 422


def test_resolucao_comentada_entra_e_muda_pela_api(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])

    criada = api.post("/api/admin/questoes", headers=professor, json={
        "enunciado": "Quanto é $2+2$?", "alternativas": {letra: letra for letra in "ABCDE"},
        "gabarito": "A", "resolucao_comentada": "Some os dois.",
    })
    assert criada.status_code == 200, criada.text
    questao = criada.json()["questoes"][0]
    assert questao["resolucao_comentada"] == "Some os dois."

    editada = api.patch(f"/api/admin/questoes/{questao['questao_id']}", headers=professor,
                        json={"resolucao_comentada": "Dois mais dois."})
    assert editada.status_code == 200, editada.text
    assert editada.json()["resolucao_comentada"] == "Dois mais dois."


def test_historico_do_aluno_so_mostra_simulado_encerrado(db, mundo):
    from app.services import simulados
    from tests.test_simulado import DEPOIS, DURANTE, _faz, _publicado

    sid = _publicado(db, mundo)
    primeira, segunda = (q.id for q in mundo["questoes"][:2])
    _faz(db, mundo["joao"], sid, {primeira: "B", segunda: "A"})  # o gabarito é B: acerta uma

    assert simulados.historico_do_aluno(db, mundo["joao"], agora=DURANTE)["simulados"] == []
    depois = simulados.historico_do_aluno(db, mundo["joao"], agora=DEPOIS)
    assert [(s["acertos"], s["total"], s["posicao"]) for s in depois["simulados"]] == [(1, 2, 1)]
    assert depois["topicos"][0]["topico"] == "Pureza e rendimento"
    assert TestClient(main.app).get("/api/aluno/desempenho", headers=_portal(mundo["professor"])).status_code == 400


# --- borda -------------------------------------------------------------------


def test_toda_resposta_leva_os_headers_de_seguranca():
    r = TestClient(main.app, raise_server_exceptions=False).get("/api/sessao/config")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
    assert r.headers["referrer-policy"] == "strict-origin-when-cross-origin"
    assert "camera=()" in r.headers["permissions-policy"]


def test_portal_estatico_leva_o_link_de_envio_para_a_pagina_dele(tmp_path):
    (tmp_path / "enviar").mkdir()
    (tmp_path / "enviar" / "index.html").write_text("pagina de envio")
    (tmp_path / "index.html").write_text("inicio")
    (tmp_path / "404.html").write_text("nao existe")
    portal = Starlette(routes=[Mount("/", app=main.PortalEstatico(directory=str(tmp_path), html=True))])
    cliente = TestClient(portal)

    envio = cliente.get("/enviar/abc123")
    assert envio.status_code == 200 and envio.text == "pagina de envio"
    assert "frame-ancestors 'none'" in envio.headers["content-security-policy"]

    faltando = cliente.get("/nao/existe")
    assert faltando.status_code == 404 and faltando.text == "nao existe"
    rota_da_api = cliente.get("/api/qualquer")
    assert rota_da_api.status_code == 404 and rota_da_api.json() == {"detail": "Não encontrado."}


def test_aprovacao_no_portal_aponta_para_a_revisao_do_rascunho(monkeypatch):
    from app.mcp_server import tools

    monkeypatch.setenv("MCP_BASE_URL", "https://portal.exemplo")
    get_settings.cache_clear()
    try:
        assert tools._aprovar_no_portal(7)["aprovar_em"] == "https://portal.exemplo/admin/rascunhos/revisar/?id=7"
    finally:
        monkeypatch.delenv("MCP_BASE_URL")
        get_settings.cache_clear()


def test_conteudo_devolve_304_quando_nada_mudou(db, mundo):
    """A rota mais chamada do portal: quem volta recebe "não mudou", não 49 KB.

    É o que segura o pico de entrada da aula (docs/CARGA.md).
    """
    api = TestClient(main.app)
    cabecalho = {"authorization": f"Bearer {cria_jwt(mundo['joao'].usuario_id, mundo['joao'].papel)}"}

    primeira = api.get("/api/aluno/conteudo", headers=cabecalho)
    assert primeira.status_code == 200
    etiqueta = primeira.headers["etag"]
    assert "no-cache" in primeira.headers["cache-control"]

    repetida = api.get("/api/aluno/conteudo", headers={**cabecalho, "if-none-match": etiqueta})
    assert repetida.status_code == 304
    assert repetida.content == b""

    velha = api.get("/api/aluno/conteudo", headers={**cabecalho, "if-none-match": '"outra-coisa"'})
    assert velha.status_code == 200
