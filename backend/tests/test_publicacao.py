"""A regra que sustenta a POC: a IA propõe, o humano aprova, o backend publica.

Se algum destes testes cair, a demonstração perde o sentido — o professor
deixaria de ser a autoridade sobre o que chega aos alunos.

Editar e remover passaram a ser diretos (ver `services/estrutura.py`), mas
**publicar não**: não existe caminho que ponha um item na tela do aluno sem
aprovação humana gravada em `drafts.aprovado_por_id`. Publicar item a item
acontece de dentro de um rascunho aprovado, e é isso que os últimos testes
daqui seguram.
"""

import pytest

from app.errors import AprovacaoNecessaria, NaoAutorizado, RegraDeNegocio
from app.models import Status
from app.services import catalogo, estrutura, publicacao, rascunhos


def _importa(db, mundo, quantos: int = 2):
    """Dois vídeos entrando no sub-módulo 'Aulas' da 2027, como rascunho."""
    return rascunhos.importar_videos_como_itens(
        db,
        mundo["professor_mcp"],
        "Extensivo 2027",
        "K01 - Estequiometria",
        "Aulas",
        [
            {"vimeo_id": f"novo{n}", "titulo": f"Vídeo novo {n}"}
            for n in range(1, quantos + 1)
        ],
    )


def _itens_visiveis(db, mundo, quem="joao"):
    arvore = catalogo.conteudo_do_aluno(db, mundo[quem])
    return [
        item
        for turma in arvore
        for modulo in turma["modulos"]
        for sub in modulo["submodulos"]
        if sub["nome"] == "Aulas"
        for item in sub["itens"]
    ]


def test_escrita_pelo_mcp_nasce_em_rascunho(db, mundo):
    resultado = _importa(db, mundo)

    assert resultado["status"] == Status.RASCUNHO
    assert resultado["publicado"] is False
    assert len(resultado["itens"]) == 2

    assert _itens_visiveis(db, mundo) == [], "aluno não pode ver nada que ainda está em rascunho"


def test_publicar_sem_aprovacao_humana_e_recusado(db, mundo):
    rascunho = _importa(db, mundo)

    with pytest.raises(AprovacaoNecessaria) as erro:
        publicacao.publicar_rascunho(db, mundo["professor_mcp"], rascunho["rascunho_id"])

    assert "aprovação humana" in str(erro.value)
    assert _itens_visiveis(db, mundo) == []


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
    assert len(_itens_visiveis(db, mundo)) == 2


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


def test_publicar_item_a_item_deixa_o_resto_pendente(db, mundo):
    """Item a item sem furar a aprovação: ela fica gravada no rascunho, e o
    professor decide quando cada item entra."""
    rascunho = _importa(db, mundo, quantos=3)
    rid = rascunho["rascunho_id"]
    publicacao.aprovar_rascunho(db, mundo["professor"], rid)

    primeiro = rascunho["itens"][0]["item_id"]
    parcial = publicacao.publicar_rascunho(db, mundo["professor"], rid, itens_ids=[primeiro])

    assert parcial["itens_publicados"] == 1
    assert parcial["itens_ainda_pendentes"] == 2
    assert parcial["publicado"] is False, "o rascunho continua aberto para o resto"
    assert len(_itens_visiveis(db, mundo)) == 1

    resto = publicacao.publicar_rascunho(db, mundo["professor"], rid)
    assert resto["publicado"] is True
    assert len(_itens_visiveis(db, mundo)) == 3


def test_item_de_fora_do_rascunho_nao_entra_na_publicacao(db, mundo):
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]
    publicacao.aprovar_rascunho(db, mundo["professor"], rid)

    with pytest.raises(RegraDeNegocio) as erro:
        publicacao.publicar_rascunho(db, mundo["professor"], rid, itens_ids=[999999])

    assert "não estão pendentes" in str(erro.value)


def test_item_removido_nao_volta_pela_publicacao(db, mundo):
    """Remoção é lógica, mas não é um 'talvez': publicar não ressuscita."""
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]
    publicacao.aprovar_rascunho(db, mundo["professor"], rid)

    alvo = estrutura.resolver_item(db, mundo["aulas"], rascunho["itens"][0]["nome"])
    estrutura.remover_item(db, mundo["professor"], alvo)
    db.commit()

    resultado = publicacao.publicar_rascunho(db, mundo["professor"], rid)

    assert resultado["itens_publicados"] == 1
    assert len(_itens_visiveis(db, mundo)) == 1


def test_descartar_remove_a_proposta_sem_deixar_rastro(db, mundo):
    rascunho = _importa(db, mundo)
    rid = rascunho["rascunho_id"]

    publicacao.descartar_rascunho(db, mundo["professor"], rid)

    assert rascunhos.listar_rascunhos(db, mundo["professor"]) == []
    assert _itens_visiveis(db, mundo) == []


def test_descartar_nao_vale_depois_de_publicar_algum_item(db, mundo):
    """Descartar apaga de verdade; o que já foi ao ar sai por remoção lógica."""
    rascunho = _importa(db, mundo, quantos=2)
    rid = rascunho["rascunho_id"]
    publicacao.aprovar_rascunho(db, mundo["professor"], rid)
    publicacao.publicar_rascunho(
        db, mundo["professor"], rid, itens_ids=[rascunho["itens"][0]["item_id"]]
    )

    with pytest.raises(RegraDeNegocio) as erro:
        publicacao.descartar_rascunho(db, mundo["professor"], rid)

    assert "já teve" in str(erro.value)


def test_aluno_nao_opera_o_mcp(db, mundo):
    with pytest.raises(NaoAutorizado):
        rascunhos.criar_questao_rascunho(
            db, mundo["joao"], "Questão do aluno", {l: l for l in "ABCDE"}, "A",
        )


def test_publicacao_recusada_nao_deixa_aprovacao_gravada(db, mundo):
    """Aprovar e publicar é um gesto só: recusada a publicação, a aprovação sai junto.

    Senão o rascunho corrigido depois pelo MCP sairia publicado sem ninguém
    ter visto a correção — a aprovação era para aquela versão, naquela hora.
    """
    from datetime import UTC, datetime, timedelta

    from app.services import simulados

    sem_agenda = rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], ["Extensivo 2027"], "Prova", [mundo["questoes"][0].id]
    )
    rid = sem_agenda["rascunho_id"]
    with pytest.raises(RegraDeNegocio):
        publicacao.aprovar_e_publicar(db, mundo["professor"], rid)

    agora = datetime.now(UTC)
    simulados.editar_simulado(
        db, mundo["professor_mcp"], sem_agenda["simulado"]["simulado_id"],
        abre_em=agora + timedelta(hours=1), fecha_em=agora + timedelta(hours=2), duracao_minutos=60,
    )
    with pytest.raises(AprovacaoNecessaria):
        publicacao.publicar_rascunho(db, mundo["professor_mcp"], rid)
