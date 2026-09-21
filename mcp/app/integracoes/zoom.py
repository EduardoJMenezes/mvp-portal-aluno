"""Cliente do Zoom: abre a sala da aula, e nada além disso.

A conta do Zoom é dividida com outra plataforma, que tem aulas rodando hoje
(ver [docs/AULAS-AO-VIVO.md](../../../docs/AULAS-AO-VIVO.md)). Por isso este
módulo é escrito de fora para dentro como uma trava:

* **não existe método de listagem.** Nem `listar_reunioes`, nem `listar
  usuários`. O app também não tem escopo para isso — conferido contra a conta
  real: a chamada volta 400 dizendo que falta `meeting:read:list_meetings`. O
  que o código não enxerga, ele não quebra.
* **allowlist de rotas**: toda chamada passa por `_ROTAS`, e caminho que não
  casa levanta erro *antes* de virar HTTP. É a mesma ideia do
  `ROTAS_PROIBIDAS` do transporte do Vimeo, invertida — aqui só o que está na
  lista passa.
* **todo id vem da nossa tabela.** Quem chama sempre traz o `zoom_meeting_id`
  que gravamos ao criar a aula; não há caminho no código para um id que a
  plataforma não criou.

O cliente é síncrono de propósito: as rotas do aluno e do professor são `def`,
rodam na pool de threads, e uma chamada de 300 ms segura uma thread em vez do
laço de eventos inteiro.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import time
from datetime import UTC, datetime
from functools import lru_cache
from typing import Any

import httpx

BASE = "https://api.zoom.us/v2"
TOKEN_URL = "https://zoom.us/oauth/token"
FUSO_PADRAO = "America/Sao_Paulo"
# Uma assinatura de webhook mais velha que isto é repetição de pedido antigo.
JANELA_DO_WEBHOOK = 5 * 60


class ZoomErro(Exception):
    """Falha falando com o Zoom. A mensagem é a que o Zoom deu."""


class ZoomCredencialInvalida(ZoomErro):
    pass


class ZoomNaoEncontrado(ZoomErro):
    pass


class ZoomLimiteDeRequisicoes(ZoomErro):
    pass


class ZoomIndisponivel(ZoomErro):
    pass


class ZoomRotaBloqueada(ZoomErro):
    """Caminho fora da allowlist. Erro de programação, não do Zoom."""


_POR_STATUS = {
    400: ZoomErro,
    401: ZoomCredencialInvalida,
    403: ZoomCredencialInvalida,
    404: ZoomNaoEncontrado,
    429: ZoomLimiteDeRequisicoes,
}

# O que este cliente pode chamar. Qualquer outra coisa nem sai da máquina.
_ROTAS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("POST", re.compile(r"^/users/[^/]+/meetings$")),
    ("GET", re.compile(r"^/meetings/\d+$")),
    ("PATCH", re.compile(r"^/meetings/\d+$")),
    ("DELETE", re.compile(r"^/meetings/\d+$")),
    ("POST", re.compile(r"^/meetings/\d+/registrants$")),
    ("GET", re.compile(r"^/meetings/\d+/recordings$")),
    ("DELETE", re.compile(r"^/meetings/\d+/recordings$")),
)


def _instante(momento: datetime) -> str:
    """O Zoom aceita UTC com Z; é o formato que não depende de fuso do servidor."""
    return momento.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class ClienteZoom:
    def __init__(
        self,
        *,
        account_id: str,
        client_id: str,
        client_secret: str,
        host: str,
        base_url: str = BASE,
        timeout: float = 20.0,
        transporte: httpx.BaseTransport | None = None,
    ) -> None:
        self._conta = account_id
        self._id = client_id
        self._segredo = client_secret
        self.host = host
        self._base = base_url.rstrip("/")
        self._http = httpx.Client(timeout=timeout, transport=transporte)
        self._token: str | None = None
        self._token_ate = 0.0

    # --- token ---------------------------------------------------------------

    def _autorizacao(self) -> str:
        """Token de 1 h, guardado até faltar um minuto. O Zoom não dá refresh:
        pedir de novo é o jeito, e tokens antigos continuam valendo."""
        if self._token and time.time() < self._token_ate:
            return self._token
        basico = base64.b64encode(f"{self._id}:{self._segredo}".encode()).decode()
        resposta = self._http.post(
            TOKEN_URL,
            headers={"Authorization": f"Basic {basico}"},
            data={"grant_type": "account_credentials", "account_id": self._conta},
        )
        if resposta.status_code != 200:
            raise ZoomCredencialInvalida(
                f"O Zoom recusou a credencial ({resposta.status_code}): {_mensagem(resposta)}. "
                "Confira se o app Server-to-Server OAuth está ativado no marketplace."
            )
        dados = resposta.json()
        self._token = dados["access_token"]
        self._token_ate = time.time() + max(60, int(dados.get("expires_in", 3600)) - 60)
        return self._token

    # --- transporte ----------------------------------------------------------

    def _chamar(self, metodo: str, caminho: str, **kw: Any) -> dict:
        if not any(m == metodo and p.match(caminho) for m, p in _ROTAS):
            raise ZoomRotaBloqueada(
                f"{metodo} {caminho} não está na lista do cliente. A conta é dividida com "
                "outra plataforma: chamada nova entra na allowlist, com o porquê."
            )
        resposta = self._http.request(
            metodo,
            f"{self._base}{caminho}",
            headers={"Authorization": f"Bearer {self._autorizacao()}"},
            **kw,
        )
        if resposta.status_code >= 400:
            classe = _POR_STATUS.get(
                resposta.status_code, ZoomIndisponivel if resposta.status_code >= 500 else ZoomErro
            )
            raise classe(f"{metodo} {caminho} → {resposta.status_code}: {_mensagem(resposta)}")
        if not resposta.content:
            return {}
        return resposta.json()

    # --- aula ----------------------------------------------------------------

    def criar_aula(
        self, *, titulo: str, inicio: datetime, minutos: int, descricao: str = "", gravar: bool = True
    ) -> dict:
        """Cria a sala. Devolve o que a nossa tabela guarda: id e link de entrada."""
        corpo = {
            "topic": titulo[:200],
            "type": 2,  # data marcada
            "start_time": _instante(inicio),
            "duration": minutos,
            "timezone": FUSO_PADRAO,
            "agenda": descricao[:2000],
            "settings": {
                # Sem sala de espera: admitir aluno por aluno não escala. A porta
                # é a inscrição, e quem a libera é o portal.
                "waiting_room": False,
                "join_before_host": False,
                "approval_type": 0,  # inscrição obrigatória, aprovada na hora
                "registration_type": 1,
                "auto_recording": "cloud" if gravar else "none",
                "meeting_authentication": False,
                # Quem avisa o aluno é o portal; e-mail do Zoom só confundiria.
                "registrants_email_notification": False,
            },
        }
        d = self._chamar("POST", f"/users/{self.host}/meetings", json=corpo)
        return {"id": str(d["id"]), "join_url": d.get("join_url", ""), "senha": d.get("password", "")}

    def editar_aula(self, meeting_id: str, *, titulo: str, inicio: datetime, minutos: int) -> None:
        self._chamar(
            "PATCH",
            f"/meetings/{meeting_id}",
            json={"topic": titulo[:200], "start_time": _instante(inicio), "duration": minutos},
        )

    def cancelar_aula(self, meeting_id: str) -> None:
        self._chamar("DELETE", f"/meetings/{meeting_id}")

    def link_de_inicio(self, meeting_id: str) -> str:
        """O link do professor expira em 2 h, então é sempre buscado na hora."""
        return self._chamar("GET", f"/meetings/{meeting_id}").get("start_url", "")

    def inscrever(self, meeting_id: str, *, nome: str, sobrenome: str, email: str) -> str:
        """Inscreve o aluno e devolve o link **dele**.

        O Zoom só deixa inscrever o mesmo e-mail três vezes por dia na mesma
        reunião: quem chama guarda o link e não pede de novo.
        """
        d = self._chamar(
            "POST",
            f"/meetings/{meeting_id}/registrants",
            json={"email": email, "first_name": nome[:64], "last_name": sobrenome[:64] or "."},
        )
        return d.get("join_url", "")

    # --- gravação ------------------------------------------------------------

    def gravacao(self, meeting_id: str) -> dict:
        """O arquivo da aula: endereço e tamanho do MP4, se já estiver pronto."""
        d = self._chamar("GET", f"/meetings/{meeting_id}/recordings")
        for arquivo in d.get("recording_files", []):
            if arquivo.get("file_type") == "MP4":
                return {
                    "download_url": arquivo.get("download_url", ""),
                    "bytes": arquivo.get("file_size", 0),
                    "uuid": d.get("uuid", ""),
                }
        return {}

    def apagar_gravacao(self, meeting_id: str) -> None:
        """Só depois de a gravação estar no Vimeo, e só com a chave ligada."""
        self._chamar("DELETE", f"/meetings/{meeting_id}/recordings", params={"action": "trash"})


class ZoomDeMentira:
    """Zoom que não existe, para a POC rodar e os testes não tocarem a rede.

    Mesmo truque do acervo de demonstração do Vimeo: sem credencial, o portal
    funciona inteiro — agenda, entra, grava — só que a sala é de brincadeira.
    """

    host = "professor@exemplo.local"

    def __init__(self) -> None:
        self._proximo = 8_000_000_001
        self.aulas: dict[str, dict] = {}

    def criar_aula(self, *, titulo: str, inicio: datetime, minutos: int, descricao: str = "", gravar: bool = True) -> dict:
        meeting_id = str(self._proximo)
        self._proximo += 1
        self.aulas[meeting_id] = {"titulo": titulo, "inicio": inicio, "minutos": minutos, "gravar": gravar}
        return {
            "id": meeting_id,
            "join_url": f"https://zoom.example/j/{meeting_id}",
            "senha": "123456",
        }

    def editar_aula(self, meeting_id: str, *, titulo: str, inicio: datetime, minutos: int) -> None:
        self._exigir(meeting_id).update({"titulo": titulo, "inicio": inicio, "minutos": minutos})

    def cancelar_aula(self, meeting_id: str) -> None:
        self._exigir(meeting_id)
        del self.aulas[meeting_id]

    def link_de_inicio(self, meeting_id: str) -> str:
        self._exigir(meeting_id)
        return f"https://zoom.example/s/{meeting_id}?zak=de-mentira"

    def inscrever(self, meeting_id: str, *, nome: str, sobrenome: str, email: str) -> str:
        self._exigir(meeting_id)
        marca = hashlib.sha256(email.encode()).hexdigest()[:12]
        return f"https://zoom.example/j/{meeting_id}?tk={marca}"

    def gravacao(self, meeting_id: str) -> dict:
        self._exigir(meeting_id)
        return {"download_url": f"https://zoom.example/rec/{meeting_id}.mp4", "bytes": 1_234_567, "uuid": "de-mentira"}

    def apagar_gravacao(self, meeting_id: str) -> None:
        self._exigir(meeting_id)

    def _exigir(self, meeting_id: str) -> dict:
        if meeting_id not in self.aulas:
            raise ZoomNaoEncontrado(f"Aula {meeting_id} não existe neste Zoom de mentira.")
        return self.aulas[meeting_id]


@lru_cache(maxsize=1)
def de_configuracao() -> ClienteZoom | ZoomDeMentira:
    """O cliente que a configuração pedir — e o de mentira quando não há chave."""
    from app.config import get_settings

    s = get_settings()
    if not (s.zoom_account_id and s.zoom_client_id and s.zoom_client_secret and s.zoom_host):
        return ZoomDeMentira()
    return ClienteZoom(
        account_id=s.zoom_account_id,
        client_id=s.zoom_client_id,
        client_secret=s.zoom_client_secret,
        host=s.zoom_host,
    )


def _mensagem(resposta: httpx.Response) -> str:
    try:
        return str(resposta.json().get("message", resposta.text[:200]))
    except ValueError:
        return resposta.text[:200]


# --- webhook -----------------------------------------------------------------


def _assinar(corpo: bytes, timestamp: str, segredo: str) -> str:
    base = b"v0:" + timestamp.encode() + b":" + corpo
    return "v0=" + hmac.new(segredo.encode(), base, hashlib.sha256).hexdigest()


def assinatura_confere(corpo: bytes, timestamp: str | None, assinatura: str | None, segredo: str, *, agora: float | None = None) -> bool:
    """Confere o `x-zm-signature`. Sem isso, qualquer um manda 'a aula acabou'."""
    if not timestamp or not assinatura:
        return False
    try:
        idade = (agora if agora is not None else time.time()) - int(timestamp) / 1000
    except ValueError:
        return False
    if abs(idade) > JANELA_DO_WEBHOOK:
        return False
    return hmac.compare_digest(_assinar(corpo, timestamp, segredo), assinatura)


def resposta_do_desafio(plain_token: str, segredo: str) -> dict[str, str]:
    """O Zoom só aceita a URL do webhook depois de receber isto em 3 segundos."""
    return {
        "plainToken": plain_token,
        "encryptedToken": hmac.new(segredo.encode(), plain_token.encode(), hashlib.sha256).hexdigest(),
    }
