"""Materiais em PDF: quem alcança, como o arquivo sai e de quem é a anotação.

Cada teste começa pelo que não pode acontecer — o aluno de outra turma, o
rascunho que ainda não foi publicado, a anotação do colega — e só depois olha o
caminho feliz.
"""

from fastapi.testclient import TestClient

from app import main
from app.security import cria_jwt

# PDF de mentira: o backend só confere a assinatura e o tamanho.
PDF = b"%PDF-1.7\n" + bytes(range(256)) * 40 + b"\n%%EOF"


def _portal(ident) -> dict:
    return {"authorization": f"Bearer {cria_jwt(ident.usuario_id, ident.papel)}"}


def _enviar(api: TestClient, professor: dict, titulo="Apostila K01", **campos) -> dict:
    r = api.post(
        "/api/admin/materiais",
        headers=professor,
        files={"arquivo": ("apostila.pdf", PDF, "application/pdf")},
        data={"titulo": titulo, **campos},
    )
    assert r.status_code == 200, r.text
    return r.json()


def test_material_so_alcanca_o_aluno_depois_de_publicado_e_enderecado(db, mundo):
    api = TestClient(main.app)
    professor, joao, pedro = (_portal(mundo[q]) for q in ("professor", "joao", "pedro"))

    material = _enviar(api, professor, turmas=["Extensivo 2027"])
    assert material["status"] == "RASCUNHO"
    assert api.get("/api/aluno/materiais", headers=joao).json() == []
    assert api.get(f"/api/aluno/materiais/{material['material_id']}/arquivo", headers=joao).status_code == 403

    publicado = api.patch(
        f"/api/admin/materiais/{material['material_id']}",
        headers=professor,
        json={"status": "PUBLICADO"},
    )
    assert publicado.status_code == 200, publicado.text

    meus = api.get("/api/aluno/materiais", headers=joao).json()
    assert [m["titulo"] for m in meus] == ["Apostila K01"]
    assert meus[0]["paginas_anotadas"] == 0
    # Pedro é da 2026: o material da 2027 não existe para ele (seção 11).
    assert api.get("/api/aluno/materiais", headers=pedro).json() == []
    assert api.get(f"/api/aluno/materiais/{material['material_id']}/arquivo", headers=pedro).status_code == 403


def test_material_de_uma_pessoa_so_alcanca_ela(db, mundo):
    api = TestClient(main.app)
    professor, joao, pedro = (_portal(mundo[q]) for q in ("professor", "joao", "pedro"))

    material = _enviar(api, professor, titulo="Lista avulsa", alunos=[str(mundo["pedro"].usuario_id)])
    api.patch(
        f"/api/admin/materiais/{material['material_id']}",
        headers=professor,
        json={"status": "PUBLICADO"},
    )

    assert [m["titulo"] for m in api.get("/api/aluno/materiais", headers=pedro).json()] == ["Lista avulsa"]
    assert api.get("/api/aluno/materiais", headers=joao).json() == []


def test_publicar_sem_turma_nem_aluno_nao_passa(db, mundo):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    material = _enviar(api, professor)

    r = api.patch(
        f"/api/admin/materiais/{material['material_id']}", headers=professor, json={"status": "PUBLICADO"}
    )
    assert r.status_code == 400
    assert "não chegaria a ninguém" in r.json()["detail"]


def test_o_que_nao_e_pdf_nao_entra(db, mundo):
    api = TestClient(main.app)
    r = api.post(
        "/api/admin/materiais",
        headers=_portal(mundo["professor"]),
        files={"arquivo": ("planilha.pdf", b"PK\x03\x04 isto e um zip", "application/pdf")},
        data={"titulo": "Disfarçado"},
    )
    assert r.status_code == 400 and "PDF" in r.json()["detail"]


def test_o_arquivo_sai_em_faixas_de_bytes(db, mundo):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    material = _enviar(api, professor, turmas=["Extensivo 2027"])
    api.patch(
        f"/api/admin/materiais/{material['material_id']}", headers=professor, json={"status": "PUBLICADO"}
    )
    endereco = f"/api/aluno/materiais/{material['material_id']}/arquivo"

    inteiro = api.get(endereco, headers=joao)
    assert inteiro.status_code == 200
    assert inteiro.content == PDF
    # É o content-length com accept-ranges que faz o leitor pedir faixas em vez
    # de arrastar a apostila inteira para ver uma página.
    assert inteiro.headers["accept-ranges"] == "bytes"
    assert int(inteiro.headers["content-length"]) == len(PDF)
    assert inteiro.headers["content-type"] == "application/pdf"
    assert "no-store" in inteiro.headers["cache-control"]

    fatia = api.get(endereco, headers={**joao, "range": "bytes=10-19"})
    assert fatia.status_code == 206
    assert fatia.content == PDF[10:20]
    assert fatia.headers["content-range"] == f"bytes 10-19/{len(PDF)}"

    fim = api.get(endereco, headers={**joao, "range": "bytes=-5"})
    assert fim.status_code == 206 and fim.content == PDF[-5:]

    aberta = api.get(endereco, headers={**joao, "range": "bytes=20-"})
    assert aberta.status_code == 206 and aberta.content == PDF[20:]

    fora = api.get(endereco, headers={**joao, "range": f"bytes={len(PDF) + 10}-"})
    assert fora.status_code == 416 and fora.headers["content-range"] == f"bytes */{len(PDF)}"


def test_anotacao_e_de_quem_riscou(db, mundo):
    api = TestClient(main.app)
    professor, joao, pedro = (_portal(mundo[q]) for q in ("professor", "joao", "pedro"))
    material = _enviar(api, professor, turmas=["Extensivo 2027", "Extensivo 2026"])
    mid = material["material_id"]
    api.patch(f"/api/admin/materiais/{mid}", headers=professor, json={"status": "PUBLICADO"})

    traco = {"t": "caneta", "cor": "#111827", "larg": 0.004, "p": [[0.1, 0.2, 0.5], [0.2, 0.3, 0.9]]}
    gravou = api.put(f"/api/aluno/materiais/{mid}/anotacoes/7", headers=joao, json={"tracos": [traco]})
    assert gravou.status_code == 200, gravou.text
    assert gravou.json() == {"material_id": mid, "pagina": 7, "tracos": 1}

    dele = api.get(f"/api/aluno/materiais/{mid}/anotacoes", headers=joao).json()
    assert dele["paginas"]["7"]["tracos"] == [traco]

    # O colega tem acesso ao mesmo material e não enxerga o caderno do outro.
    assert api.get(f"/api/aluno/materiais/{mid}/anotacoes", headers=pedro).json()["paginas"] == {}
    # Nem o professor.
    assert api.get(f"/api/aluno/materiais/{mid}/anotacoes", headers=professor).json()["paginas"] == {}

    assert api.get("/api/aluno/materiais", headers=joao).json()[0]["paginas_anotadas"] == 1

    # Salvar de novo troca a página inteira, e apagar tudo devolve a página limpa.
    api.put(f"/api/aluno/materiais/{mid}/anotacoes/7", headers=joao, json={"tracos": []})
    assert api.get(f"/api/aluno/materiais/{mid}/anotacoes", headers=joao).json()["paginas"] == {}


def test_anotacao_gigante_nao_passa(db, mundo):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    material = _enviar(api, professor, turmas=["Extensivo 2027"])
    mid = material["material_id"]
    api.patch(f"/api/admin/materiais/{mid}", headers=professor, json={"status": "PUBLICADO"})

    enorme = [{"t": "caneta", "cor": "#000", "larg": 0.004, "p": [[0.1, 0.2, 0.5]] * 2000}] * 20
    r = api.put(f"/api/aluno/materiais/{mid}/anotacoes/1", headers=joao, json={"tracos": enorme})
    assert r.status_code == 400 and "KB" in r.json()["detail"]


def test_material_removido_some_da_tela_do_aluno(db, mundo):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    material = _enviar(api, professor, turmas=["Extensivo 2027"])
    mid = material["material_id"]
    api.patch(f"/api/admin/materiais/{mid}", headers=professor, json={"status": "PUBLICADO"})
    assert len(api.get("/api/aluno/materiais", headers=joao).json()) == 1

    assert api.delete(f"/api/admin/materiais/{mid}", headers=professor).status_code == 200
    assert api.get("/api/aluno/materiais", headers=joao).json() == []
    assert api.get(f"/api/aluno/materiais/{mid}/arquivo", headers=joao).status_code == 404


def test_aluno_nao_publica_material(db, mundo):
    api = TestClient(main.app)
    joao = _portal(mundo["joao"])
    assert api.get("/api/admin/materiais", headers=joao).status_code == 403
    r = api.post(
        "/api/admin/materiais",
        headers=joao,
        files={"arquivo": ("apostila.pdf", PDF, "application/pdf")},
        data={"titulo": "Minha apostila"},
    )
    assert r.status_code == 403
