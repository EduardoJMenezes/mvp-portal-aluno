"""A borda do MCP: quem entra, e com que identidade."""

import asyncio

from fastmcp.server.auth import AuthProvider

from app.mcp_server.auth import TokenDaPlataforma, _consultar
from app.mcp_server.server import mcp

# Importar o módulo de tools é o que dispara os decorators que registram tudo.
import app.mcp_server.tools  # noqa: F401  isort:skip
from app.models import Papel, TokenMCP, Usuario
from app.security import hash_senha, hash_token, novo_token_mcp


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
        "criar_modulo", "criar_submodulo", "editar_modulo", "editar_item",
        "remover_do_curso", "cadastrar_assunto", "classificar_videos",
    }
    assert esperadas <= set(tools)

    # Toda tool precisa de descrição: é o que o modelo lê para decidir usá-la.
    sem_descricao = [n for n, t in tools.items() if not (t.description or "").strip()]
    assert sem_descricao == []

    escritas = {"criar_questao_rascunho", "importar_videos_como_itens",
                "criar_simulado_rascunho", "publicar_rascunho",
                "importar_pasta_vimeo_como_rascunho",
                "criar_modulo", "criar_submodulo", "editar_modulo", "editar_item",
                "remover_do_curso", "cadastrar_assunto", "classificar_videos"}
    for nome, tool in tools.items():
        esperado = nome not in escritas
        assert tool.annotations.read_only_hint is esperado, f"{nome} com read_only_hint errado"


def test_instrucoes_do_servidor_reforcam_a_regra():
    assert "nunca publique" in (mcp.instructions or "").lower()


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
