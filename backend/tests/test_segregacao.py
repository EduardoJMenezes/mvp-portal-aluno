"""Segregação por turma (seção 11) — e o que o aluno vê do que não é dele.

A regra é do backend: o frontend não filtra nada por conta própria e o MCP não
tem caminho alternativo até os dados.

Com o conteúdo bloqueado, a segregação deixou de ser binária. São três
estados: o que o aluno assiste, o que ele fica sabendo que existe (vídeo de
outro curso sobre um assunto que ele errou), e o que ele nem vê. O segundo é o
mais delicado: o card aparece, mas **sem nada do Vimeo** — sem `embed_url` não
há devtools que libere o acervo.
"""

import pytest

from app.errors import NaoAutorizado
from app.services import acesso, catalogo, publicacao, rascunhos, simulados


def _turmas_visiveis(db, mundo, quem):
    return {t["turma"] for t in catalogo.conteudo_do_aluno(db, mundo[quem])}


def test_aluno_so_ve_a_propria_turma(db, mundo):
    assert _turmas_visiveis(db, mundo, "joao") == {"Extensivo 2027"}
    assert _turmas_visiveis(db, mundo, "pedro") == {"Extensivo 2026"}


def test_aluno_nao_alcanca_turma_alheia_nem_pedindo(db, mundo):
    with pytest.raises(NaoAutorizado):
        catalogo.listar_modulos(db, mundo["pedro"], turma="Extensivo 2027")


def test_listar_turmas_filtra_por_matricula(db, mundo):
    assert [t["nome"] for t in catalogo.listar_turmas(db, mundo["joao"])] == ["Extensivo 2027"]
    assert len(catalogo.listar_turmas(db, mundo["professor"])) == 2


def test_video_da_propria_turma_vem_com_o_embed(db, mundo):
    arvore = catalogo.conteudo_do_aluno(db, mundo["joao"])
    itens = [
        item
        for turma in arvore
        for modulo in turma["modulos"]
        for sub in modulo["submodulos"]
        for item in sub["itens"]
    ]

    assert itens, "João devia enxergar o módulo publicado da turma dele"
    for item in itens:
        assert item["video"]["bloqueado"] is False
        assert item["video"]["embed_url"], "sem o embed o player não toca"


def test_video_de_outra_turma_nao_leva_nada_do_vimeo(db, mundo):
    """O bloqueio não pode ser decoração de tela."""
    alheio = mundo["video_de_outra_turma"]

    assert acesso.pode_assistir(db, mundo["joao"], alheio.id) is False

    vitrine = acesso.descrever_video(alheio, liberado=False)
    assert vitrine["bloqueado"] is True
    assert vitrine["titulo"] == alheio.titulo
    assert "embed_url" not in vitrine, "o hash de privacidade não pode sair do backend"
    assert "thumbnail_url" not in vitrine
    assert "vimeo_id" not in vitrine


def test_operador_alcanca_o_acervo_inteiro(db, mundo):
    alheio = mundo["video_de_outra_turma"]

    assert acesso.pode_assistir(db, mundo["professor"], alheio.id) is True


def test_item_removido_some_da_tela_do_aluno(db, mundo):
    from app.services import estrutura

    antes = catalogo.conteudo_do_aluno(db, mundo["joao"])[0]["modulos"][0]["submodulos"]
    quantos = sum(len(s["itens"]) for s in antes)

    estrutura.remover_item(db, mundo["professor"], mundo["itens"][0])
    db.commit()

    depois = catalogo.conteudo_do_aluno(db, mundo["joao"])[0]["modulos"][0]["submodulos"]
    assert sum(len(s["itens"]) for s in depois) == quantos - 1


def _simulado(db, mundo, titulo="Prova 1"):
    ids = [q.id for q in mundo["questoes"][:2]]
    return rascunhos.criar_simulado_rascunho(db, mundo["professor_mcp"], "Extensivo 2027", titulo, ids)


def test_simulado_publicado_so_aparece_para_a_turma_certa(db, mundo):
    rascunho = _simulado(db, mundo)
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    assert len(simulados.listar_simulados(db, mundo["joao"])) == 1
    assert simulados.listar_simulados(db, mundo["pedro"]) == []


def test_aluno_de_outra_turma_nao_abre_o_simulado(db, mundo):
    rascunho = _simulado(db, mundo)
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])
    alvo = simulados.listar_simulados(db, mundo["joao"])[0]

    with pytest.raises(NaoAutorizado):
        simulados.abrir_simulado(db, mundo["pedro"], alvo["simulado_id"])


def test_simulado_em_rascunho_e_invisivel_para_o_aluno(db, mundo):
    _simulado(db, mundo, titulo="Ainda não")

    assert simulados.listar_simulados(db, mundo["joao"]) == []
