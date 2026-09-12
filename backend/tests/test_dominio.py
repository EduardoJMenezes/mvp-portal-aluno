"""Regras de domínio e as respostas que o agente recebe quando erra.

Mensagem de erro aqui é interface: o LLM lê e se corrige. Testamos que ela
diz o que existe, não só que algo falhou.
"""

import pytest

from app.errors import NaoEncontrado, RegraDeNegocio
from app.services import catalogo, estrutura, publicacao, rascunhos, simulados, taxonomia


# --- resolução de referências ------------------------------------------------


def test_referencias_resolvem_pelo_nome(db, mundo):
    assert catalogo.resolver_turma(db, "extensivo 2027").nome == "Extensivo 2027"
    assert catalogo.resolver_turma(db, mundo["turma_2027"].id).ano == 2027

    modulo = estrutura.resolver_modulo(db, mundo["turma_2027"], "k01")
    assert modulo.nome == "K01 - Estequiometria"

    assert taxonomia.resolver_assunto(db, "estequiometria").nome == "Estequiometria"


def test_turma_ambigua_lista_as_opcoes(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        catalogo.resolver_turma(db, "Extensivo")

    assert "Extensivo 2026" in str(erro.value)
    assert "Extensivo 2027" in str(erro.value)


def test_turma_inexistente_diz_quais_existem(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        catalogo.resolver_turma(db, "Intensivo 2030")

    assert "Extensivo 2027" in str(erro.value)


def test_modulo_inexistente_lista_os_da_turma(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        estrutura.resolver_modulo(db, mundo["turma_2027"], "K99")

    assert "K01 - Estequiometria" in str(erro.value)


def test_assunto_inexistente_ensina_como_criar(db, mundo):
    with pytest.raises(NaoEncontrado) as erro:
        taxonomia.resolver_assunto(db, "Termoquímica")

    assert "Estequiometria" in str(erro.value)
    assert "criar_assunto" in str(erro.value)


# --- questão de simulado -----------------------------------------------------


def test_questao_exige_as_cinco_alternativas(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_questao_rascunho(
            db, mundo["professor_mcp"], "Só três", {"A": "a", "B": "b", "C": "c"}, "A",
        )

    assert "D" in str(erro.value) and "E" in str(erro.value)


def test_gabarito_fora_de_a_e_e_recusado(db, mundo):
    with pytest.raises(RegraDeNegocio):
        rascunhos.criar_questao_rascunho(
            db, mundo["professor_mcp"], "Enunciado", {l: l for l in "ABCDE"}, "Z",
        )


def test_simulado_recusa_questao_fora_do_acervo_publicado(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_simulado_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Prova", [999999],
        )

    assert "não está no acervo publicado" in str(erro.value)


def test_simulado_recusa_questao_sem_alternativas(db, mundo):
    """Questão que nasceu incompleta não pode virar prova."""
    from app.models import Questao, Status

    incompleta = Questao(
        enunciado="Só o enunciado", gabarito="A", status=Status.PUBLICADO,
        criado_por_id=mundo["professor"].usuario_id,
    )
    db.add(incompleta)
    db.commit()

    with pytest.raises(RegraDeNegocio) as erro:
        rascunhos.criar_simulado_rascunho(
            db, mundo["professor_mcp"], "Extensivo 2027", "Prova", [incompleta.id],
        )

    assert "alternativas" in str(erro.value)


# --- estrutura do curso ------------------------------------------------------


def test_modulo_repetido_na_mesma_turma_e_recusado(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        estrutura.criar_modulo(db, mundo["professor"], mundo["turma_2027"], "K01 - Estequiometria")

    assert "já tem um módulo" in str(erro.value)


def test_o_mesmo_nome_de_modulo_vale_em_outra_turma(db, mundo):
    """Módulo pertence à turma: 'K01' de 2026 não colide com o de 2027."""
    criado = estrutura.criar_modulo(
        db, mundo["professor"], mundo["turma_2026"], "K01 - Estequiometria"
    )
    db.commit()

    assert criado.turma_id == mundo["turma_2026"].id


def test_nome_de_modulo_removido_pode_ser_reusado(db, mundo):
    """O índice de unicidade é parcial — senão a remoção lógica queimaria o
    nome para sempre."""
    estrutura.remover_modulo(db, mundo["professor"], mundo["modulo"])
    db.commit()

    recriado = estrutura.criar_modulo(
        db, mundo["professor"], mundo["turma_2027"], "K01 - Estequiometria"
    )
    db.commit()

    assert recriado.id != mundo["modulo"].id


def test_o_mesmo_video_nao_entra_duas_vezes_no_sub_modulo(db, mundo):
    item = mundo["itens"][0]

    with pytest.raises(RegraDeNegocio) as erro:
        estrutura.criar_item(db, mundo["professor"], mundo["submodulo"], item.video)

    assert "já tem este vídeo" in str(erro.value)


def test_remover_modulo_nao_cascateia(db, mundo):
    """Os filhos ficam intactos: é o que permite restaurar como estava."""
    from app.services.consultas import restaurar

    estrutura.remover_modulo(db, mundo["professor"], mundo["modulo"])
    db.commit()
    assert catalogo.conteudo_do_aluno(db, mundo["joao"]) == []

    restaurar(db, mundo["professor"], mundo["modulo"])
    db.commit()

    arvore = catalogo.conteudo_do_aluno(db, mundo["joao"])
    itens = [
        item
        for turma in arvore
        for modulo in turma["modulos"]
        for sub in modulo["submodulos"]
        for item in sub["itens"]
    ]
    assert len(itens) == 3, "a subárvore volta inteira"


def test_remocao_e_idempotente(db, mundo):
    from app.services.consultas import remover

    assert remover(db, mundo["professor"], mundo["itens"][0]) == 1
    assert remover(db, mundo["professor"], mundo["itens"][0]) == 0


def test_edicao_registra_quem_mexeu(db, mundo):
    """Sem rascunho no meio, o rastro é o que resta."""
    estrutura.editar_item(db, mundo["professor"], mundo["itens"][0], nome="Q01 — revisada")
    db.commit()

    assert mundo["itens"][0].alterado_por_id == mundo["professor"].usuario_id
    assert mundo["itens"][0].alterado_em is not None


# --- taxonomia ---------------------------------------------------------------


def test_assunto_e_global_entre_turmas(db, mundo):
    """A mesma etiqueta alcança vídeos das duas turmas — é isso que faz a
    recomendação atravessar cursos."""
    videos = taxonomia.videos_que_explicam(
        db, mundo["assunto"].id, mundo["subassunto"].id, limite=10
    )

    ids = {v.id for v in videos}
    assert mundo["video_de_outra_turma"].id in ids
    assert len(ids) >= 4
