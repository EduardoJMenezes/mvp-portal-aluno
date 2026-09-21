"""O cliente do Zoom, com a rede trocada por um transporte de mentira.

O que estes testes protegem é menos a feliz e mais a trava: a conta do Zoom é
dividida com outra plataforma que tem aula rodando, e o que não pode acontecer
é este código alcançar o que não é nosso.
"""

from __future__ import annotations

import hashlib
import hmac
import json
from datetime import UTC, datetime

import httpx
import pytest

from app.integracoes.zoom import (
    ClienteZoom,
    ZoomCredencialInvalida,
    ZoomDeMentira,
    ZoomLimiteDeRequisicoes,
    ZoomNaoEncontrado,
    ZoomRotaBloqueada,
    assinatura_confere,
    resposta_do_desafio,
)

INICIO = datetime(2026, 10, 1, 19, 0, tzinfo=UTC)


def cliente(responder, **kw) -> ClienteZoom:
    return ClienteZoom(
        account_id="conta",
        client_id="id",
        client_secret="segredo",
        host="professor@escola.com",
        transporte=httpx.MockTransport(responder),
        **kw,
    )


def _token_ok(pedido: httpx.Request) -> httpx.Response | None:
    if pedido.url.host == "zoom.us":
        return httpx.Response(200, json={"access_token": "t-1", "expires_in": 3600})
    return None


# --- credencial --------------------------------------------------------------


def test_o_token_e_pedido_uma_vez_e_reaproveitado():
    contagem = {"token": 0, "chamadas": 0}

    def responder(pedido: httpx.Request) -> httpx.Response:
        if pedido.url.host == "zoom.us":
            contagem["token"] += 1
            return httpx.Response(200, json={"access_token": "t-1", "expires_in": 3600})
        contagem["chamadas"] += 1
        assert pedido.headers["authorization"] == "Bearer t-1"
        return httpx.Response(200, json={"id": 123, "join_url": "https://z/j/123"})

    z = cliente(responder)
    z.criar_aula(titulo="Aula 1", inicio=INICIO, minutos=90)
    z.criar_aula(titulo="Aula 2", inicio=INICIO, minutos=90)
    assert contagem == {"token": 1, "chamadas": 2}


def test_app_desativado_vira_erro_que_diz_o_que_fazer():
    def responder(pedido: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"reason": "The app has been disabled by the developer"})

    with pytest.raises(ZoomCredencialInvalida, match="ativado"):
        cliente(responder).criar_aula(titulo="x", inicio=INICIO, minutos=60)


# --- a trava -----------------------------------------------------------------


def test_rota_fora_da_lista_nao_vira_requisicao():
    """Listar reuniões da conta é justamente o que não pode existir aqui."""
    def responder(pedido: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("não devia ter saído da máquina")

    z = cliente(responder)
    with pytest.raises(ZoomRotaBloqueada):
        z._chamar("GET", "/users/professor@escola.com/meetings")
    with pytest.raises(ZoomRotaBloqueada):
        z._chamar("DELETE", "/users/professor@escola.com")


def test_o_cliente_nao_tem_como_listar():
    """Sem método de listagem não há engano possível — nem por autocompletar."""
    for proibido in ("listar_reunioes", "listar_aulas", "listar_usuarios", "reunioes"):
        assert not hasattr(ClienteZoom, proibido)


# --- aula --------------------------------------------------------------------


def test_criar_aula_manda_o_que_a_aula_precisa():
    visto = {}

    def responder(pedido: httpx.Request) -> httpx.Response:
        if (r := _token_ok(pedido)) is not None:
            return r
        visto["caminho"] = pedido.url.path
        visto["corpo"] = json.loads(pedido.content)
        return httpx.Response(201, json={"id": 987654321, "join_url": "https://z/j/9", "password": "abc"})

    aula = cliente(responder).criar_aula(titulo="Estequiometria", inicio=INICIO, minutos=90)

    assert aula == {"id": "987654321", "join_url": "https://z/j/9", "senha": "abc"}
    assert visto["caminho"] == "/v2/users/professor@escola.com/meetings"
    corpo = visto["corpo"]
    assert corpo["type"] == 2
    assert corpo["start_time"] == "2026-10-01T19:00:00Z"
    assert corpo["settings"]["waiting_room"] is False  # 600 alunos não se admite na mão
    assert corpo["settings"]["approval_type"] == 0  # inscrição obrigatória
    assert corpo["settings"]["auto_recording"] == "cloud"
    assert corpo["settings"]["registrants_email_notification"] is False


def test_sem_gravar_a_sala_nasce_sem_gravacao():
    visto = {}

    def responder(pedido: httpx.Request) -> httpx.Response:
        if (r := _token_ok(pedido)) is not None:
            return r
        visto["corpo"] = json.loads(pedido.content)
        return httpx.Response(201, json={"id": 1})

    cliente(responder).criar_aula(titulo="x", inicio=INICIO, minutos=60, gravar=False)
    assert visto["corpo"]["settings"]["auto_recording"] == "none"


def test_link_de_inicio_e_sempre_buscado():
    def responder(pedido: httpx.Request) -> httpx.Response:
        if (r := _token_ok(pedido)) is not None:
            return r
        assert pedido.method == "GET"
        return httpx.Response(200, json={"start_url": "https://z/s/1?zak=novo"})

    assert cliente(responder).link_de_inicio("123") == "https://z/s/1?zak=novo"


def test_aula_que_nao_existe_mais():
    def responder(pedido: httpx.Request) -> httpx.Response:
        if (r := _token_ok(pedido)) is not None:
            return r
        return httpx.Response(404, json={"message": "Meeting does not exist"})

    with pytest.raises(ZoomNaoEncontrado):
        cliente(responder).cancelar_aula("123")


def test_limite_do_zoom_tem_erro_proprio():
    def responder(pedido: httpx.Request) -> httpx.Response:
        if (r := _token_ok(pedido)) is not None:
            return r
        return httpx.Response(429, json={"message": "You have exceeded the daily rate limit"})

    with pytest.raises(ZoomLimiteDeRequisicoes):
        cliente(responder).inscrever("123", nome="Ana", sobrenome="Silva", email="ana@escola.com")


# --- webhook -----------------------------------------------------------------


def corpo_e_assinatura(segredo: str, timestamp: str, dados: dict) -> tuple[bytes, str]:
    corpo = json.dumps(dados).encode()
    base = b"v0:" + timestamp.encode() + b":" + corpo
    return corpo, "v0=" + hmac.new(segredo.encode(), base, hashlib.sha256).hexdigest()


def test_assinatura_boa_passa_e_ruim_nao():
    agora = 1_800_000_000.0
    ts = str(int(agora * 1000))
    corpo, assinatura = corpo_e_assinatura("sg", ts, {"event": "meeting.ended"})

    assert assinatura_confere(corpo, ts, assinatura, "sg", agora=agora)
    assert not assinatura_confere(corpo, ts, assinatura, "outro-segredo", agora=agora)
    assert not assinatura_confere(corpo + b" ", ts, assinatura, "sg", agora=agora)
    assert not assinatura_confere(corpo, ts, None, "sg", agora=agora)


def test_assinatura_velha_nao_passa():
    """Pedido antigo repetido é ataque, não atraso de rede."""
    agora = 1_800_000_000.0
    ts = str(int((agora - 3600) * 1000))
    corpo, assinatura = corpo_e_assinatura("sg", ts, {"event": "meeting.ended"})
    assert not assinatura_confere(corpo, ts, assinatura, "sg", agora=agora)


def test_desafio_da_url_responde_o_que_o_zoom_espera():
    r = resposta_do_desafio("abc", "sg")
    assert r["plainToken"] == "abc"
    assert r["encryptedToken"] == hmac.new(b"sg", b"abc", hashlib.sha256).hexdigest()


# --- zoom de mentira ---------------------------------------------------------


def test_o_zoom_de_mentira_serve_a_poc_inteira():
    z = ZoomDeMentira()
    aula = z.criar_aula(titulo="Aula", inicio=INICIO, minutos=90)

    assert aula["id"] and aula["join_url"]
    assert z.link_de_inicio(aula["id"]).startswith("https://")
    # Cada aluno tem o link dele — é o que o portal guarda por pessoa.
    de_ana = z.inscrever(aula["id"], nome="Ana", sobrenome="S", email="ana@escola.com")
    de_bruno = z.inscrever(aula["id"], nome="Bruno", sobrenome="S", email="bruno@escola.com")
    assert de_ana != de_bruno

    z.cancelar_aula(aula["id"])
    with pytest.raises(ZoomNaoEncontrado):
        z.link_de_inicio(aula["id"])
