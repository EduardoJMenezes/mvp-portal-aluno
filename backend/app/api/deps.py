"""Identidade de quem chega pela API REST.

Duas credenciais, a mesma porta:

* **Cookie `sessao`** — o portal no navegador. httpOnly (o JavaScript não lê),
  SameSite=Strict (outro site não o faz viajar) e só em `/api`.
* **Header `Authorization: Bearer`** — o token do MCP (Claude Code, scripts)
  ou uma sessão do portal em JWT, como os testes usam.
"""

from __future__ import annotations

from urllib.parse import urlsplit

from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import get_db
from app.identidade import Canal, Identidade
from app.models import Usuario
from app.security import le_jwt

COOKIE_SESSAO = "sessao"

# Com senha temporária, a sessão só alcança o que leva a trocá-la.
_LIVRES_COM_SENHA_TEMPORARIA = {"/api/eu", "/api/conta/senha", "/api/logout"}
_METODOS_SEGUROS = {"GET", "HEAD", "OPTIONS"}


def _origem_confiavel(request: Request) -> bool:
    """Escrita vinda do cookie precisa partir deste site.

    O SameSite=Strict já barra o cookie em pedido de outro site; conferir a
    origem é a segunda tranca, para navegador antigo ou cookie mal configurado.
    """
    origem = request.headers.get("origin")
    if not origem:
        return True  # navegador não manda Origin em todo pedido do mesmo site
    host = urlsplit(origem).netloc
    return host == request.headers.get("host") or origem in get_settings().lista_cors


def usuario_atual(
    request: Request,
    authorization: str | None = Header(default=None),
    db: Session = Depends(get_db),
) -> Identidade:
    cookie = request.cookies.get(COOKIE_SESSAO)
    if authorization and authorization.lower().startswith("bearer "):
        valor, pelo_cookie = authorization.split(" ", 1)[1].strip(), False
    elif cookie:
        valor, pelo_cookie = cookie, True
    else:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Faça login para continuar.")

    dados = le_jwt(valor)
    if not dados:
        if pelo_cookie:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sessão expirada. Entre de novo.")
        return _pelo_token_do_mcp(valor)

    if pelo_cookie and request.method not in _METODOS_SEGUROS and not _origem_confiavel(request):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Pedido de outra origem recusado.")

    usuario = db.get(Usuario, int(dados["sub"]))
    if usuario is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Esta conta não existe mais.")
    if usuario.senha_alterada_em and int(dados.get("iat", 0)) < int(
        usuario.senha_alterada_em.timestamp()
    ):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "A senha mudou. Entre de novo.")
    if usuario.senha_temporaria and request.url.path not in _LIVRES_COM_SENHA_TEMPORARIA:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Troque a senha temporária para continuar.")

    return Identidade(
        usuario_id=usuario.id,
        nome=usuario.nome,
        email=usuario.email,
        papel=usuario.papel,
        canal=Canal.PORTAL,
    )


def _pelo_token_do_mcp(valor: str) -> Identidade:
    """O token do MCP também abre a API — e com o canal MCP, não o do portal.

    É por ele que uma sessão do Claude Code envia a figura recortada de uma
    questão (docs/MODELO-SIMULADO.md). O token só existe para operador, e o
    canal continua barrado onde a regra pede um humano no navegador: aprovar e
    descartar rascunho, cadastrar aluno, redefinir senha, emitir token.
    """
    from app.mcp_server.auth import _consultar

    acesso = _consultar(valor)
    if acesso is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sessão expirada ou inválida.")
    c = acesso.claims
    return Identidade(
        usuario_id=c["usuario_id"], nome=c["nome"], email=c["email"], papel=c["papel"],
        canal=Canal.MCP,
    )


def operador_atual(ident: Identidade = Depends(usuario_atual)) -> Identidade:
    if not ident.e_operador:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Área restrita a ADMIN/GERENCIADOR.")
    return ident
