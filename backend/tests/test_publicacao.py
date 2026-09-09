"""A regra que sustenta a POC: a IA propõe, o humano aprova, o backend publica.

Se algum destes testes cair, a demonstração perde o sentido — o professor
deixaria de ser a autoridade sobre o que chega aos alunos.
"""

import pytest

from app.errors import AprovacaoNecessaria, NaoAutorizado, RegraDeNegocio
from app.models import Status
from app.services import catalogo, publicacao, rascunhos


def _importa(db, mundo, capitulo="Atomística"):
    return rascunhos.importar_questoes_vimeo(
        db, mundo["professor_mcp"], "Extensivo 2027", capitulo,
        [{"vimeo_id": "111", "titulo": "Vídeo 1"}, {"vimeo_id": "222", "titulo": "Vídeo 2"}],
    )


def test_escrita_pelo_mcp_nasce_em_rascunho(db, mundo):
    resultado = _importa(db, mundo)

    assert resultado["status"] == Status.RASCUNHO
    assert resultado["publicado"] is False
    assert len(resultado["questoes"]) == 2

    visivel = catalogo.buscar_questoes(db, mundo["joao"], capitulo="Atomística")
    assert visivel == [], "aluno não pode ver nada que ainda está em rascunho"


def test_publicar_sem_aprovacao_humana_e_recusado(db, mundo):
    rascunho = _importa(db, mundo)

    with pytest.raises(AprovacaoNecessaria) as erro:
        publicacao.publicar_rascunho(db, mundo["professor_mcp"], rascunho["rascunho_id"])

    assert "aprovação humana" in str(erro.value)
    # e nada mudou
    assert catalogo.buscar_questoes(db, mundo["joao"], capitulo="Atomística") == []


def test_agente_nao_consegue_aprovar_sozinho(db, mundo):
    """Aprovar exige sessão do portal — token de MCP não serve."""
    rascunho = _importa(db, mundo)

    with pytest.raises(NaoAutorizado):
        publicacao.aprovar_rascunho(db, mundo["professor_mcp"], rascunho["rascunho_id"])


def test_professor_aprova_no_portal_e_publica(db, mundo):
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]

    resultado = publicacao.aprovar_e_publicar(db, mundo["professor"], rid)

    assert resultado["publicado"] is True
    assert resultado["aprovado_via"] == publicacao.ViaAprovacao.PORTAL
    assert len(catalogo.buscar_questoes(db, mundo["joao"], capitulo="Atomística")) == 2


def test_confirmacao_no_cliente_mcp_tambem_vale_como_aprovacao(db, mundo):
    """O caminho que a tool usa depois do usuário confirmar no cliente."""
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]

    publicacao.registrar_confirmacao_do_cliente_mcp(db, mundo["professor_mcp"], rid)
    resultado = publicacao.publicar_rascunho(db, mundo["professor_mcp"], rid)

    assert resultado["publicado"] is True
    assert resultado["aprovado_via"] == publicacao.ViaAprovacao.ELICITATION_MCP


def test_nao_publica_duas_vezes(db, mundo):
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]
    publicacao.aprovar_e_publicar(db, mundo["professor"], rid)

    with pytest.raises(RegraDeNegocio):
        publicacao.publicar_rascunho(db, mundo["professor"], rid)


def test_descartar_remove_a_proposta_sem_deixar_rastro(db, mundo):
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]

    publicacao.descartar_rascunho(db, mundo["professor"], rid)

    assert rascunhos.listar_rascunhos(db, mundo["professor"]) == []
    assert catalogo.buscar_questoes(db, mundo["professor"], capitulo="Atomística") == []


def test_aluno_nao_opera_o_mcp(db, mundo):
    with pytest.raises(NaoAutorizado):
        rascunhos.criar_questao_rascunho(
            db, mundo["joao"], "Extensivo 2027", "Atomística", "Questão do aluno",
            {l: l for l in "ABCDE"}, "A",
        )
