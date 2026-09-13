"""A borda do MCP: quem entra, e com que identidade."""

import asyncio

import pytest
from fastmcp.server.auth import AuthProvider

from app.mcp_server.auth import TokenDaPlataforma, _consultar
from app.mcp_server.server import mcp

# Importar os módulos de tools é o que dispara os decorators que registram tudo.
import app.mcp_server.tools  # noqa: F401  isort:skip
import app.mcp_server.tools_estrutura  # noqa: F401  isort:skip
from app.models import Papel, TokenMCP, Usuario
from app.security import hash_senha, hash_token, novo_token_mcp

# Alteram o curso sem rascunho no meio: a confirmação é o preview no chat.
ALTERAM_NA_HORA = {
    "criar_modulo", "criar_submodulo", "editar_modulo", "editar_item",
    "remover_do_curso", "cadastrar_assunto", "classificar_videos",
}


def _token_para(db, usuario: Usuario, revogado: bool = False) -> str:
    valor = novo_token_mcp()
    db.add(
        TokenMCP(usuario_id=usuario.id, nome="teste", token_hash=hash_token(valor),
                 revogado=revogado)
    )
    db.commit()
    return valor


def _usuario(db, papel: str, email: str) -> Usuario:
    u = Usuario(nome=f"U {papel}", email=email, senha_hash=hash_senha("x"), papel=papel)
    db.add(u)
    db.commit()
    return u


def test_token_de_operador_abre_sessao_com_a_identidade_certa(db):
    professor = _usuario(db, Papel.ADMIN, "adm@x.demo")
    valor = _token_para(db, professor)

    acesso = _consultar(valor)

    assert acesso is not None
    assert acesso.claims["usuario_id"] == professor.id
    assert acesso.claims["papel"] == Papel.ADMIN


def test_token_de_aluno_nao_abre_o_mcp(db):
    """Seção 4: aluno não acessa o MCP administrativo."""
    aluno = _usuario(db, Papel.ALUNO, "aluno@x.demo")
    valor = _token_para(db, aluno)

    assert _consultar(valor) is None


def test_token_revogado_nao_vale(db):
    gerente = _usuario(db, Papel.GERENCIADOR, "ger@x.demo")
    valor = _token_para(db, gerente, revogado=True)

    assert _consultar(valor) is None


def test_token_inventado_nao_vale(db):
    assert _consultar("pvm_nao_existe") is None


def test_token_nao_fica_em_claro_no_banco(db):
    professor = _usuario(db, Papel.ADMIN, "adm2@x.demo")
    valor = _token_para(db, professor)

    guardado = db.query(TokenMCP).filter_by(usuario_id=professor.id).one()
    assert valor not in guardado.token_hash
    assert guardado.token_hash == hash_token(valor)


def test_servidor_exige_autenticacao():
    # Com OAuth configurado o tipo muda para MultiAuth (ver test_mcp_oauth.py);
    # o que nenhuma configuração pode produzir é um servidor sem autenticação.
    assert mcp.auth is not None, "o MCP não pode subir aberto"
    assert isinstance(mcp.auth, AuthProvider)


def test_tools_registradas_e_anotadas():
    tools = {t.name: t for t in asyncio.run(mcp._list_tools())}

    esperadas = {
        # consulta
        "listar_turmas", "listar_modulos", "listar_assuntos", "buscar_questoes",
        "listar_videos_vimeo", "listar_pastas_vimeo", "simular_importacao_vimeo",
        "listar_rascunhos", "detalhar_rascunho", "buscar_desempenho_aluno",
        "buscar_estatisticas_simulado", "listar_simulados",
        # proposta, que nasce em rascunho
        "criar_questao_rascunho", "importar_videos_como_itens", "criar_simulado_rascunho",
        "importar_pasta_vimeo_como_rascunho", "publicar_rascunho",
        # CRUD do curso, que altera direto e confirma com o professor
        *ALTERAM_NA_HORA,
    }
    assert esperadas <= set(tools)

    # Toda tool precisa de descrição: é o que o modelo lê para decidir usá-la.
    sem_descricao = [n for n, t in tools.items() if not (t.description or "").strip()]
    assert sem_descricao == []

    escritas = {"criar_questao_rascunho", "importar_videos_como_itens",
                "criar_simulado_rascunho", "publicar_rascunho",
                "importar_pasta_vimeo_como_rascunho", *ALTERAM_NA_HORA}
    for nome, tool in tools.items():
        esperado = nome not in escritas
        assert tool.annotations.read_only_hint is esperado, f"{nome} com read_only_hint errado"


def test_instrucoes_do_servidor_reforcam_a_regra():
    assert "nunca publique" in (mcp.instructions or "").lower()


def test_tool_que_altera_na_hora_pede_preview_e_ok_no_chat():
    """Sem rascunho entre a decisão e o efeito, a confirmação é esta instrução.

    Foi escolha do professor: o formulário de confirmação era respondido pelo
    próprio app, sem chegar a ele. Tirar a instrução daqui deixaria a tool
    alterando o curso sem ninguém ver antes.
    """
    tools = {t.name: t for t in asyncio.run(mcp._list_tools())}

    def corrido(texto: str | None) -> str:
        return " ".join((texto or "").split())  # a quebra de linha não vale como mudança

    sem_regra = [
        nome for nome in ALTERAM_NA_HORA
        if "mostre ao professor no chat" not in corrido(tools[nome].description)
    ]
    assert sem_regra == []
    assert "só chame depois do ok do professor" in corrido(mcp.instructions)


async def test_cliente_com_lista_de_ferramentas_velha_e_avisado():
    """O conector do claude.ai guarda o catálogo de quando foi ligado.

    Quando uma tool some ou muda de assinatura num deploy, o modelo chama o
    formato antigo — e sem este aviso inventa uma explicação para o professor.
    """
    from fastmcp import Client, FastMCP

    from app.mcp_server.server import CatalogoDesatualizado

    assert any(isinstance(m, CatalogoDesatualizado) for m in mcp.middleware)

    servidor = FastMCP("teste", middleware=[CatalogoDesatualizado()])

    @servidor.tool(name="importar")
    def importar(pasta: str, destinos: list[dict]) -> str:
        return "ok"

    async with Client(servidor) as cliente:
        with pytest.raises(Exception) as sumiu:
            await cliente.call_tool("listar_capitulos", {})
        with pytest.raises(Exception) as mudou:
            await cliente.call_tool("importar", {"pasta": "1", "capitulo": "K01"})

    assert "desatualizada" in str(sumiu.value)
    assert "Ferramentas atuais: importar" in str(sumiu.value)
    assert "que hoje recebe: pasta, destinos" in str(mudou.value)
    assert "desatualizada" in str(mudou.value)


def test_cliente_vimeo_le_o_embed_da_api():
    """O campo player_embed_url da API vira o embed_url do nosso domínio."""
    from app.vimeo.client import _para_video

    bruto = {
        "uri": "/videos/76979871",
        "name": "Aula",
        "link": "https://vimeo.com/76979871/8272103f6e",
        "player_embed_url": "https://player.vimeo.com/video/76979871?h=8272103f6e",
        "duration": 300,
    }
    video = _para_video(bruto)

    assert video.id == "76979871"
    assert video.embed_url == "https://player.vimeo.com/video/76979871?h=8272103f6e"
