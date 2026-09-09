"""Conteúdo é da turma, e a turma é decidida no backend (seções 11 e 19)."""

import pytest

from app.errors import NaoAutorizado
from app.services import catalogo, publicacao, rascunhos, simulados


def test_aluno_so_ve_a_propria_turma(db, mundo):
    do_joao = catalogo.buscar_questoes(db, mundo["joao"])
    do_pedro = catalogo.buscar_questoes(db, mundo["pedro"])

    assert {q["turma"] for q in do_joao} == {"Extensivo 2027"}
    assert do_pedro == [], "Pedro é de 2026 e não tem conteúdo de 2027"


def test_aluno_nao_alcanca_turma_alheia_nem_pedindo(db, mundo):
    with pytest.raises(NaoAutorizado):
        catalogo.buscar_questoes(db, mundo["pedro"], turma="Extensivo 2027")


def test_aluno_nao_ve_gabarito(db, mundo):
    questao = catalogo.buscar_questoes(db, mundo["joao"])[0]
    assert "gabarito" not in questao

    do_professor = catalogo.buscar_questoes(db, mundo["professor"])[0]
    assert do_professor["gabarito"] == "B"


def test_listar_turmas_filtra_por_matricula(db, mundo):
    assert [t["nome"] for t in catalogo.listar_turmas(db, mundo["joao"])] == ["Extensivo 2027"]
    assert [t["nome"] for t in catalogo.listar_turmas(db, mundo["pedro"])] == ["Extensivo 2026"]
    assert len(catalogo.listar_turmas(db, mundo["professor"])) == 2


def test_simulado_publicado_so_aparece_para_a_turma_certa(db, mundo):
    rascunho = rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], "Extensivo 2027", "Prova 1", ["1", "2"],
        capitulo="Estequiometria",
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    assert len(simulados.listar_simulados(db, mundo["joao"])) == 1
    assert simulados.listar_simulados(db, mundo["pedro"]) == []


def test_aluno_de_outra_turma_nao_abre_o_simulado(db, mundo):
    rascunho = rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], "Extensivo 2027", "Prova 1", ["1"], capitulo="Estequiometria"
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])
    simulado_id = simulados.listar_simulados(db, mundo["joao"])[0]["simulado_id"]

    with pytest.raises(NaoAutorizado):
        simulados.abrir_simulado(db, mundo["pedro"], simulado_id)


def test_simulado_em_rascunho_e_invisivel_para_o_aluno(db, mundo):
    rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], "Extensivo 2027", "Ainda não", ["1"], capitulo="Estequiometria"
    )
    assert simulados.listar_simulados(db, mundo["joao"]) == []
