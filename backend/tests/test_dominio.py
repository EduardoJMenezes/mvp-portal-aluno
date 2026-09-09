"""Regras de domínio e as respostas que o agente recebe quando erra.

Mensagem de erro aqui é interface: o LLM lê e se corrige. Testamos que ela
diz o que existe, não só que algo falhou.
"""

import pytest

from app.errors import NaoEncontrado, RegraDeNegocio
from app.services import analytics, catalogo, publicacao, rascunhos, simulados


def test_referencias_resolvem_pelo_nome(db, mundo):
    assert catalogo.resolver_turma(db, "Extensivo 2027").ano == 2027
    assert catalogo.resolver_turma(db, "2027").ano == 2027  # trecho do nome
    assert catalogo.resolver_capitulo(db, "estequiometria").nome == "Estequiometria"
    assert analytics.resolver_aluno(db, "joão").nome == "João"


def test_turma_ambigua_lista_as_opcoes(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        catalogo.resolver_turma(db, "Extensivo")
    assert "Extensivo 2026" in str(erro.value) and "Extensivo 2027" in str(erro.value)


def test_turma_inexistente_diz_quais_existem(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        catalogo.resolver_turma(db, "Intensivo 2030")
    assert "Extensivo 2027" in str(erro.value)


def test_questao_exige_as_cinco_alternativas(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_questao_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Estequiometria",
            "Enunciado", {"A": "um", "B": "dois"}, "A",
        )
    assert "C, D, E" in str(erro.value)


def test_gabarito_fora_de_a_e_e_recusado(db, mundo):
    with pytest.raises(RegraDeNegocio):
        rascunhos.criar_questao_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Estequiometria",
            "Enunciado", {l: l for l in "ABCDE"}, "F",
        )


def test_simulado_recusa_questao_nao_publicada_na_turma(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_simulado_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Prova", ["99"],
            capitulo="Estequiometria",
        )
    assert "Disponíveis" in str(erro.value)


def test_simulado_recusa_questao_sem_alternativas(db, mundo):
    """Questão que só tem vídeo de resolução não pode ser respondida."""
    rascunho = rascunhos.importar_questoes_vimeo(
        db, mundo["professor_mcp"], "Extensivo 2027", "Atomística",
        [{"vimeo_id": "555", "titulo": "Só vídeo"}],
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_simulado_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Prova", ["1"], capitulo="Atomística"
        )
    assert "sem as alternativas" in str(erro.value)


def test_numero_repetido_entre_capitulos_pede_o_capitulo(db, mundo):
    rascunho = rascunhos.importar_questoes_vimeo(
        db, mundo["professor_mcp"], "Extensivo 2027", "Atomística",
        [{"vimeo_id": "777", "titulo": "V", "alternativas": {l: l for l in "ABCDE"},
          "gabarito": "C"}],
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_simulado_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Prova", ["1"]
        )
    assert "mais de um capítulo" in str(erro.value)


def test_mesma_questao_serve_a_dois_anos(db, mundo):
    """A organização anual é da plataforma, não do Vimeo (seção 18)."""
    from app.models import Status, TurmaQuestao

    questao_id = catalogo.buscar_questoes(db, mundo["professor"])[0]["questao_id"]
    db.add(
        TurmaQuestao(
            turma_id=mundo["turma_2026"].id, capitulo_id=mundo["capitulo"].id,
            questao_id=questao_id, numero=17, status=Status.PUBLICADO,
        )
    )
    db.commit()

    em_2026 = catalogo.buscar_questoes(db, mundo["professor"], turma="Extensivo 2026")
    assert [q["questao_id"] for q in em_2026] == [questao_id]
    assert em_2026[0]["numero"] == 17


def test_respostas_sao_corrigidas_no_backend(db, mundo):
    rascunho = rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], "Extensivo 2027", "Prova", ["1", "2"],
        capitulo="Estequiometria",
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])
    sid = simulados.listar_simulados(db, mundo["joao"])[0]["simulado_id"]

    prova = simulados.abrir_simulado(db, mundo["joao"], sid)
    assert all("gabarito" not in q for q in prova["questoes"]), "gabarito não vai para o aluno"

    q1, q2 = (q["questao_id"] for q in prova["questoes"])
    simulados.responder(db, mundo["joao"], sid, q1, "B")  # correta
    resposta = simulados.responder(db, mundo["joao"], sid, q2, "A")  # errada
    assert "correta" not in resposta, "o aluno não descobre o acerto ao responder"

    resultado = simulados.finalizar(db, mundo["joao"], sid)
    assert resultado["acertos"] == 1 and resultado["total"] == 2
    assert resultado["percentual"] == 50.0


def test_estatisticas_saem_das_respostas_reais(db, mundo):
    rascunho = rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], "Extensivo 2027", "Prova", ["1", "2"],
        capitulo="Estequiometria",
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])
    sid = simulados.listar_simulados(db, mundo["joao"])[0]["simulado_id"]

    prova = simulados.abrir_simulado(db, mundo["joao"], sid)
    q1, q2 = (q["questao_id"] for q in prova["questoes"])
    simulados.responder(db, mundo["joao"], sid, q1, "B")
    simulados.responder(db, mundo["joao"], sid, q2, "A")
    simulados.finalizar(db, mundo["joao"], sid)

    stats = analytics.estatisticas_simulado(db, mundo["professor"], "Prova")
    assert stats["alunos_responderam"] == 1
    assert stats["media_percentual"] == 50.0
    por_questao = {q["questao_id"]: q["percentual_acerto"] for q in stats["por_questao"]}
    assert por_questao[q1] == 100.0 and por_questao[q2] == 0.0
    assert stats["maior_dificuldade"]["questao_id"] == q2


def test_sem_respostas_o_analytics_diz_isso_em_vez_de_inventar(db, mundo):
    desempenho = analytics.desempenho_aluno(db, mundo["professor"], "João")
    assert desempenho["encontrou_dados"] is False
    assert "ainda não respondeu" in desempenho["mensagem"]


def test_embed_do_vimeo_e_repassado_como_veio(db, mundo):
    """Vídeo unlisted só toca com o hash de privacidade que a API devolve.

    Montar a URL do player a partir do id perde o `?h=...` e o player recusa
    tocar — foi assim que o acervo real quebraria na tela do aluno.
    """
    embed = "https://player.vimeo.com/video/76979871?h=8272103f6e"
    rascunho = rascunhos.importar_questoes_vimeo(
        db, mundo["professor_mcp"], "Extensivo 2027", "Atomística",
        [{"vimeo_id": "76979871", "titulo": "Aula", "embed_url": embed}],
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    questao = catalogo.buscar_questoes(db, mundo["joao"], capitulo="Atomística")[0]
    assert questao["video"]["embed_url"] == embed


def test_video_sem_embed_conhecido_cai_na_url_publica(db, mundo):
    rascunho = rascunhos.importar_questoes_vimeo(
        db, mundo["professor_mcp"], "Extensivo 2027", "Atomística",
        [{"vimeo_id": "123", "titulo": "Aula"}],
    )
    publicacao.aprovar_e_publicar(db, mundo["professor"], rascunho["rascunho_id"])

    questao = catalogo.buscar_questoes(db, mundo["joao"], capitulo="Atomística")[0]
    assert questao["video"]["embed_url"] == "https://player.vimeo.com/video/123"
