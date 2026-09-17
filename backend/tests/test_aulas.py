"""Aulas ao vivo: quem alcança, quando a porta abre e o que não se toca.

A conta do Zoom é dividida com outra plataforma que tem aula rodando. Metade
destes testes existe por isso: o que garante que o código só mexe no que é
nosso não é a minha atenção, é o teste que quebra se alguém afrouxar.
"""

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app import main
from app.integracoes.zoom import ZoomDeMentira, de_configuracao as cliente_zoom
from app.security import cria_jwt

AGORA = datetime.now(UTC)


class ZoomEspiao(ZoomDeMentira):
    """Zoom de mentira que conta o que foi chamado."""

    def __init__(self) -> None:
        super().__init__()
        self.inscricoes = 0
        self.canceladas: list[str] = []

    def inscrever(self, meeting_id: str, **kw) -> str:
        self.inscricoes += 1
        return super().inscrever(meeting_id, **kw)

    def cancelar_aula(self, meeting_id: str) -> None:
        self.canceladas.append(meeting_id)
        super().cancelar_aula(meeting_id)


@pytest.fixture
def zoom():
    espiao = ZoomEspiao()
    main.api.dependency_overrides[cliente_zoom] = lambda: espiao
    yield espiao
    main.api.dependency_overrides.clear()


def _portal(ident) -> dict:
    return {"authorization": f"Bearer {cria_jwt(ident.usuario_id, ident.papel)}"}


def _agendar(api: TestClient, professor: dict, **campos) -> dict:
    corpo = {
        "titulo": "Aula de estequiometria",
        "inicio_em": (AGORA + timedelta(days=1)).isoformat(),
        "minutos": 90,
        "turmas": ["Extensivo 2027"],
    }
    corpo.update(campos)
    r = api.post("/api/admin/aulas", headers=professor, json=corpo)
    assert r.status_code == 200, r.text
    return r.json()


def _publicar(api: TestClient, professor: dict, aula_id: int) -> dict:
    r = api.patch(f"/api/admin/aulas/{aula_id}", headers=professor, json={"status": "PUBLICADO"})
    assert r.status_code == 200, r.text
    return r.json()


def test_rascunho_nao_alcanca_ninguem_e_nao_abre_sala(db, mundo, zoom):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])

    aula = _agendar(api, professor)
    assert aula["status"] == "RASCUNHO"
    # Rascunho não gasta uma das cem criações diárias que dividimos com a outra
    # plataforma: a sala só nasce ao publicar.
    assert aula["tem_sala"] is False
    assert zoom.aulas == {}
    assert api.get("/api/aluno/aulas", headers=joao).json() == []

    publicada = _publicar(api, professor, aula["aula_id"])
    assert publicada["tem_sala"] is True
    assert len(zoom.aulas) == 1
    assert [a["titulo"] for a in api.get("/api/aluno/aulas", headers=joao).json()] == [
        "Aula de estequiometria"
    ]


def test_aula_sem_turma_nem_aluno_nao_publica(db, mundo, zoom):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    aula = _agendar(api, professor, turmas=[])

    r = api.patch(f"/api/admin/aulas/{aula['aula_id']}", headers=professor, json={"status": "PUBLICADO"})
    assert r.status_code == 422
    assert "não alcança ninguém" in r.json()["detail"]
    assert zoom.aulas == {}


def test_aluno_de_outra_turma_nao_entra(db, mundo, zoom):
    api = TestClient(main.app)
    professor, pedro = _portal(mundo["professor"]), _portal(mundo["pedro"])
    aula = _agendar(api, professor, inicio_em=(AGORA + timedelta(minutes=5)).isoformat())
    _publicar(api, professor, aula["aula_id"])

    assert api.get("/api/aluno/aulas", headers=pedro).json() == []
    r = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=pedro)
    assert r.status_code == 403
    assert zoom.inscricoes == 0


def test_a_porta_so_abre_perto_da_hora(db, mundo, zoom):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(api, professor, inicio_em=(AGORA + timedelta(hours=3)).isoformat())
    _publicar(api, professor, aula["aula_id"])

    cedo = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)
    assert cedo.status_code == 422
    assert "15 minutos antes" in cedo.json()["detail"]
    assert zoom.inscricoes == 0

    assert api.get("/api/aluno/aulas", headers=joao).json()[0]["estado"] == "AGENDADA"


def test_aula_que_ja_acabou_nao_deixa_entrar(db, mundo, zoom):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(
        api, professor, inicio_em=(AGORA - timedelta(hours=5)).isoformat(), minutos=60
    )
    _publicar(api, professor, aula["aula_id"])

    r = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)
    assert r.status_code == 422
    assert "já terminou" in r.json()["detail"]
    assert api.get("/api/aluno/aulas", headers=joao).json()[0]["estado"] == "ENCERRADA"


def test_cada_aluno_tem_o_link_dele_e_ele_nao_e_pedido_duas_vezes(db, mundo, zoom):
    """O Zoom só deixa inscrever o mesmo e-mail três vezes por dia na reunião."""
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(
        api,
        professor,
        inicio_em=(AGORA + timedelta(minutes=5)).isoformat(),
        turmas=["Extensivo 2027", "Extensivo 2026"],
    )
    _publicar(api, professor, aula["aula_id"])

    primeira = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)
    assert primeira.status_code == 200, primeira.text
    link = primeira.json()["url"]
    assert link.startswith("https://")

    segunda = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)
    assert segunda.json()["url"] == link
    assert zoom.inscricoes == 1  # a segunda saiu da nossa tabela, não do Zoom

    de_pedro = api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=_portal(mundo["pedro"]))
    assert de_pedro.status_code == 200
    assert de_pedro.json()["url"] != link
    assert zoom.inscricoes == 2


def test_o_link_de_entrar_nao_aparece_em_listagem(db, mundo, zoom):
    """Endereço de sala em listagem é endereço que vaza no grupo da turma."""
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(api, professor, inicio_em=(AGORA + timedelta(minutes=5)).isoformat())
    _publicar(api, professor, aula["aula_id"])
    api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)

    corpo = api.get("/api/aluno/aulas", headers=joao).text
    assert "zoom.example/j/" not in corpo
    assert "join_url" not in corpo


def test_tirar_do_ar_desmarca_a_sala_e_invalida_os_links(db, mundo, zoom):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(api, professor, inicio_em=(AGORA + timedelta(minutes=5)).isoformat())
    publicada = _publicar(api, professor, aula["aula_id"])
    api.post(f"/api/aluno/aulas/{aula['aula_id']}/entrar", headers=joao)
    sala = list(zoom.aulas)[0]

    r = api.patch(
        f"/api/admin/aulas/{aula['aula_id']}", headers=professor, json={"status": "RASCUNHO"}
    )
    assert r.status_code == 200, r.text
    assert zoom.canceladas == [sala]
    assert r.json()["tem_sala"] is False
    assert api.get("/api/aluno/aulas", headers=joao).json() == []
    assert publicada["tem_sala"] is True


def test_mudar_o_horario_avisa_o_zoom(db, mundo, zoom):
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    aula = _agendar(api, professor)
    _publicar(api, professor, aula["aula_id"])
    sala = list(zoom.aulas)[0]

    novo = (AGORA + timedelta(days=2)).replace(microsecond=0)
    r = api.patch(
        f"/api/admin/aulas/{aula['aula_id']}", headers=professor, json={"inicio_em": novo.isoformat()}
    )
    assert r.status_code == 200, r.text
    assert zoom.aulas[sala]["inicio"].replace(microsecond=0) == novo


def test_remover_cancela_a_sala(db, mundo, zoom):
    api = TestClient(main.app)
    professor, joao = _portal(mundo["professor"]), _portal(mundo["joao"])
    aula = _agendar(api, professor)
    _publicar(api, professor, aula["aula_id"])
    sala = list(zoom.aulas)[0]

    r = api.delete(f"/api/admin/aulas/{aula['aula_id']}", headers=professor)
    assert r.status_code == 200, r.text
    assert zoom.canceladas == [sala]
    assert api.get("/api/aluno/aulas", headers=joao).json() == []
    assert api.get("/api/admin/aulas", headers=professor).json() == []


def test_aluno_nao_agenda_nem_inicia_aula(db, mundo, zoom):
    api = TestClient(main.app)
    joao = _portal(mundo["joao"])
    assert api.get("/api/admin/aulas", headers=joao).status_code == 403
    assert api.post("/api/admin/aulas", headers=joao, json={
        "titulo": "x", "inicio_em": AGORA.isoformat()
    }).status_code == 403
    assert api.post("/api/admin/aulas/1/iniciar", headers=joao).status_code == 403


def test_o_link_de_iniciar_e_buscado_na_hora(db, mundo, zoom):
    """O start_url do Zoom expira em 2 h, então não tem coluna no banco."""
    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    aula = _agendar(api, professor)
    _publicar(api, professor, aula["aula_id"])

    r = api.post(f"/api/admin/aulas/{aula['aula_id']}/iniciar", headers=professor)
    assert r.status_code == 200, r.text
    assert r.json()["url"].startswith("https://")

    from app.models import Aula

    guardada = db.get(Aula, aula["aula_id"])
    assert not any("zak" in str(v) for v in vars(guardada).values())


def test_reuniao_de_fora_nao_e_nossa(db, mundo, zoom):
    """A busca por id do Zoom é o que protege as aulas da outra plataforma."""
    from app.services.aulas import por_meeting_id

    api = TestClient(main.app)
    professor = _portal(mundo["professor"])
    aula = _agendar(api, professor)
    _publicar(api, professor, aula["aula_id"])

    assert por_meeting_id(db, "99999999999") is None
    nossa = por_meeting_id(db, list(zoom.aulas)[0])
    assert nossa is not None and nossa.id == aula["aula_id"]
