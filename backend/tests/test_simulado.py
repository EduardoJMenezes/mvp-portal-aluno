"""As regras do simulado (docs/MODELO-SIMULADO.md), com o relógio na mão.

Publicar usa o relógio de verdade, então a agenda daqui fica no futuro próximo;
o resto recebe `agora` e anda no tempo sem esperar.
"""

from datetime import UTC, datetime, timedelta

import pytest

from app.errors import NaoAutorizado, RegraDeNegocio
from app.identidade import Canal, Identidade
from app.models import Matricula, Papel, Status, Usuario
from app.security import hash_senha
from app.services import acesso, publicacao, questoes, rascunhos, simulados

AGORA = datetime.now(UTC).replace(microsecond=0)
ABRE = AGORA + timedelta(hours=1)
FECHA = AGORA + timedelta(hours=3)
DURANTE = ABRE + timedelta(minutes=5)
DEPOIS = FECHA + timedelta(minutes=1)

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32


def _nova(n: int, **extra) -> dict:
    return {
        "enunciado": f"Questão nova {n}: calcule $n = \\frac{{m}}{{M}}$",
        "alternativas": {letra: f"{letra}{n}" for letra in "ABCDE"},
        "gabarito": "C",
        "assunto": "Estequiometria",
        **extra,
    }


def _rascunho(db, mundo, turmas=("Extensivo 2027",), questoes_=None, duracao=60, **extra):
    ids = questoes_ or [q.id for q in mundo["questoes"][:2]]
    return rascunhos.criar_simulado_rascunho(
        db, mundo["professor_mcp"], list(turmas), "Simulado 30", ids,
        abre_em=extra.pop("abre_em", ABRE), fecha_em=extra.pop("fecha_em", FECHA),
        duracao_minutos=duracao, **extra,
    )


def _publicado(db, mundo, **kwargs) -> int:
    r = _rascunho(db, mundo, **kwargs)
    publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])
    return r["simulado"]["simulado_id"]


def _aluno(db, nome: str, turma) -> Identidade:
    u = Usuario(nome=nome, email=f"{nome.lower()}@x.demo", senha_hash=hash_senha("x"),
                papel=Papel.ALUNO)
    db.add(u)
    db.flush()
    db.add(Matricula(usuario_id=u.id, turma_id=turma.id))
    db.commit()
    return Identidade(u.id, nome, u.email, Papel.ALUNO, Canal.PORTAL)


def _faz(db, quem, sid, respostas: dict[int, str], quando=DURANTE):
    simulados.abrir_simulado(db, quem, sid, agora=quando)
    for questao_id, letra in respostas.items():
        simulados.responder(db, quem, sid, questao_id, letra, agora=quando)
    return simulados.entregar(db, quem, sid, agora=quando)


# --- agenda e prazo ----------------------------------------------------------


def test_prova_nao_abre_antes_da_hora(db, mundo):
    sid = _publicado(db, mundo)

    prova = simulados.abrir_simulado(db, mundo["joao"], sid, agora=AGORA)

    assert prova["estado"] == simulados.EstadoDaProva.AGENDADO
    assert "questoes" not in prova, "a prova não pode vazar antes de abrir"
    with pytest.raises(RegraDeNegocio):
        simulados.responder(db, mundo["joao"], sid, mundo["questoes"][0].id, "B", agora=AGORA)


def test_prazo_e_o_que_vier_primeiro_entre_duracao_e_fechamento(db, mundo):
    sid = _publicado(db, mundo, duracao=60)
    maria = _aluno(db, "Maria", mundo["turma_2027"])

    cedo = simulados.abrir_simulado(db, mundo["joao"], sid, agora=DURANTE)
    tarde = simulados.abrir_simulado(db, maria, sid, agora=FECHA - timedelta(minutes=10))

    assert cedo["segundos_restantes"] == 60 * 60
    assert tarde["segundos_restantes"] == 10 * 60, "o fechamento corta o tempo de quem começa tarde"


def test_tempo_estourado_entrega_sozinho_com_o_que_foi_respondido(db, mundo):
    sid = _publicado(db, mundo, duracao=30)
    q1 = mundo["questoes"][0].id
    simulados.abrir_simulado(db, mundo["joao"], sid, agora=DURANTE)
    simulados.responder(db, mundo["joao"], sid, q1, "B", agora=DURANTE)

    estourou = DURANTE + timedelta(minutes=31)
    with pytest.raises(RegraDeNegocio) as erro:
        simulados.responder(db, mundo["joao"], sid, q1, "A", agora=estourou)
    assert "tempo acabou" in str(erro.value)

    prova = simulados.abrir_simulado(db, mundo["joao"], sid, agora=estourou)
    assert prova["estado"] == simulados.EstadoDaProva.ENTREGUE
    assert prova["entregue_automaticamente"] is True

    resultado = simulados.resultado(db, mundo["joao"], sid, agora=DEPOIS)
    assert resultado["acertos"] == 1, "a resposta dada antes do prazo vale"
    assert resultado["em_branco"] == 1, "o resto fica em branco, e em branco é erro"


# --- resultado e ranking -----------------------------------------------------


def test_resultado_so_sai_depois_do_fechamento(db, mundo):
    sid = _publicado(db, mundo)
    q1, q2 = (q.id for q in mundo["questoes"][:2])

    recibo = _faz(db, mundo["joao"], sid, {q1: "B", q2: "A"})

    assert "acertos" not in recibo and "gabarito" not in str(recibo)
    assert "resultado sai em" in recibo["mensagem"]
    with pytest.raises(RegraDeNegocio):
        simulados.resultado(db, mundo["joao"], sid, agora=DURANTE)

    resultado = simulados.resultado(db, mundo["joao"], sid, agora=DEPOIS)
    assert (resultado["acertos"], resultado["total"]) == (1, 2)
    assert [q["gabarito"] for q in resultado["questoes"]] == ["B", "B"]
    assert [q["marcada"] for q in resultado["questoes"]] == ["B", "A"]
    assert resultado["analise"][0]["topico"] == "Pureza e rendimento"


def test_ranking_divide_posicao_no_empate_e_deixa_de_fora_quem_nao_comecou(db, mundo):
    sid = _publicado(db, mundo, turmas=("Extensivo 2027", "Extensivo 2026"))
    q1, q2 = (q.id for q in mundo["questoes"][:2])
    ana = _aluno(db, "Ana", mundo["turma_2027"])
    bia = _aluno(db, "Bia", mundo["turma_2026"])
    _aluno(db, "Caio", mundo["turma_2026"])  # matriculado, não começa

    _faz(db, mundo["joao"], sid, {q1: "B", q2: "B"})  # 2
    _faz(db, mundo["pedro"], sid, {q1: "B"})  # 1
    _faz(db, ana, sid, {q2: "B", q1: "A"})  # 1
    _faz(db, bia, sid, {})  # 0

    ranking = simulados.ranking(db, mundo["professor"], sid, agora=DEPOIS)

    assert [(r["aluno"], r["posicao"]) for r in ranking["ranking"]] == [
        ("João", 1), ("Ana", 2), ("Pedro", 2), ("Bia", 4)
    ]
    assert ranking["participantes"] == 4 and ranking["parcial"] is False

    do_pedro = simulados.resultado(db, mundo["pedro"], sid, agora=DEPOIS)
    assert (do_pedro["posicao"], do_pedro["participantes"]) == (2, 4)
    assert "ranking" not in do_pedro, "o aluno vê a posição dele, não a lista"


def test_ranking_completo_e_so_do_professor(db, mundo):
    sid = _publicado(db, mundo)

    with pytest.raises(NaoAutorizado):
        simulados.ranking(db, mundo["joao"], sid, agora=DEPOIS)


# --- trava depois de aberto --------------------------------------------------


def test_questoes_e_gabarito_travam_quando_abre(db, mundo):
    sid = _publicado(db, mundo)
    prof = mundo["professor"]

    with pytest.raises(RegraDeNegocio):
        simulados.editar_simulado(db, prof, sid, questoes=[mundo["questoes"][2].id], agora=DURANTE)
    with pytest.raises(RegraDeNegocio):
        questoes.editar_questao(db, prof, mundo["questoes"][0].id, gabarito="A", agora=DURANTE)
    with pytest.raises(RegraDeNegocio):
        simulados.editar_simulado(db, prof, sid, fecha_em=FECHA - timedelta(minutes=1),
                                  agora=DURANTE)

    estendido = simulados.editar_simulado(db, prof, sid, fecha_em=FECHA + timedelta(hours=1),
                                          agora=DURANTE)
    assert estendido["fecha_em"] == simulados._iso(FECHA + timedelta(hours=1))

    # Classificação não mexe na prova de ninguém: continua editável.
    questoes.editar_questao(db, prof, mundo["questoes"][0].id, dificuldade="DIFICIL", agora=DURANTE)

    with pytest.raises(RegraDeNegocio) as erro:
        simulados.editar_simulado(db, prof, sid, fecha_em=FECHA + timedelta(days=1),
                                  agora=FECHA + timedelta(hours=2))
    assert "resultado já saiu" in str(erro.value)


def test_antes_de_abrir_o_simulado_publicado_muda_mas_segue_pronto(db, mundo):
    sid = _publicado(db, mundo)

    editado = simulados.editar_simulado(
        db, mundo["professor"], sid, questoes=[mundo["questoes"][2].id, mundo["questoes"][0].id],
        turmas=["Extensivo 2027", "Extensivo 2026"], agora=AGORA,
    )
    assert editado["total_questoes"] == 2 and len(editado["turmas"]) == 2

    with pytest.raises(RegraDeNegocio) as erro:
        simulados.editar_simulado(db, mundo["professor"], sid, fecha_em=AGORA - timedelta(hours=1),
                                  abre_em=AGORA - timedelta(hours=2), agora=AGORA)
    assert "já passou" in str(erro.value)


# --- publicação barrada por pendência ----------------------------------------


def test_simulado_sem_agenda_nao_publica(db, mundo):
    r = _rascunho(db, mundo, abre_em=None, fecha_em=None, duracao=None)

    with pytest.raises(RegraDeNegocio) as erro:
        publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])

    assert "abertura e fechamento" in str(erro.value)
    assert "tempo de prova" in str(erro.value)


def test_imagem_pendente_barra_a_publicacao_ate_ser_anexada(db, mundo):
    r = _rascunho(db, mundo, questoes_=[mundo["questoes"][0].id, _nova(2, imagem_pendente=True)])
    pendente = r["simulado"]["questoes"][1]["questao_id"]
    assert r["simulado"]["pendencias_para_publicar"] == ["questões com imagem pendente: [2]"]

    with pytest.raises(RegraDeNegocio) as erro:
        publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])
    assert "imagem pendente" in str(erro.value)

    anexo = questoes.anexar_imagem(db, mundo["professor"], pendente, PNG, "figura.png")
    assert anexo["tipo"] == "image/png" and anexo["imagem_pendente"] is False

    publicado = publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])
    assert publicado["simulado_publicado"] == "Simulado 30"
    assert publicado["questoes_publicadas"] == 1, "a questão nova vai ao acervo junto"


def test_imagem_so_aceita_formato_de_imagem_de_verdade(db, mundo):
    alvo = mundo["questoes"][0].id
    with pytest.raises(RegraDeNegocio):
        questoes.anexar_imagem(db, mundo["professor"], alvo, b"<svg onload='x()'/>", "a.svg")
    with pytest.raises(RegraDeNegocio):
        questoes.anexar_imagem(db, mundo["professor"], alvo, PNG + b"\x00" * (3 * 1024 * 1024))


# --- o rascunho único e o que o aluno alcança --------------------------------


def _com_resolucao(db, mundo):
    """Questões novas com resolução casada pelo número, publicadas."""
    resolucoes = {
        n: {"vimeo_id": f"res{n}", "titulo": f"Q{n:02d}",
            "embed_url": f"https://player.vimeo.com/video/{n}?h=res"}
        for n in (1, 2)
    }
    r = _rascunho(db, mundo, questoes_=[_nova(1), _nova(2)], resolucoes=resolucoes)
    publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])
    return r


def test_simulado_e_questoes_novas_nascem_num_rascunho_so(db, mundo):
    r = _rascunho(db, mundo, questoes_=[mundo["questoes"][0].id, _nova(12, numero=12)],
                  resolucoes={12: {"vimeo_id": "res12", "titulo": "Q12"}})

    assert r["tipo"] == "SIMULADO" and len(r["questoes"]) == 1
    prova = r["simulado"]["questoes"]
    assert [q["nova"] for q in prova] == [False, True]
    assert prova[1]["resolucao"] == "Q12", "a resolução casa pelo número da questão"
    assert r["questoes"][0]["classificacao"] == [{"assunto": "Estequiometria", "subassunto": None}]


def test_questao_errada_na_montagem_diz_qual_e(db, mundo):
    with pytest.raises(RegraDeNegocio) as erro:
        _rascunho(db, mundo, questoes_=[mundo["questoes"][0].id, _nova(2, gabarito="Z")])

    assert str(erro.value).startswith("Questão 2 da prova:")


def test_resolucao_libera_so_depois_do_fechamento_e_so_para_quem_fez(db, mundo):
    r = _com_resolucao(db, mundo)
    sid = r["simulado"]["simulado_id"]
    ids = [q["questao_id"] for q in r["simulado"]["questoes"]]
    maria = _aluno(db, "Maria", mundo["turma_2027"])
    _faz(db, mundo["joao"], sid, {ids[0]: "C"})

    video = questoes.detalhar_questao(db, mundo["professor"], ids[0])["video_resolucao_id"]
    assert acesso.videos_liberados(db, mundo["joao"], [video], agora=DURANTE) == set()
    assert acesso.videos_liberados(db, mundo["joao"], [video], agora=DEPOIS) == {video}
    assert acesso.videos_liberados(db, maria, [video], agora=DEPOIS) == set()

    resolucao = simulados.resultado(db, mundo["joao"], sid, agora=DEPOIS)["questoes"][0]["resolucao"]
    assert resolucao["bloqueado"] is False and resolucao["embed_url"]


def test_imagem_so_para_quem_comecou_a_prova(db, mundo):
    alvo = mundo["questoes"][0].id
    questoes.anexar_imagem(db, mundo["professor"], alvo, PNG)
    sid = _publicado(db, mundo)

    with pytest.raises(NaoAutorizado):
        questoes.imagem_da_questao(db, mundo["joao"], alvo)
    with pytest.raises(NaoAutorizado):
        questoes.imagem_da_questao(db, mundo["pedro"], alvo)

    simulados.abrir_simulado(db, mundo["joao"], sid, agora=DURANTE)
    assert questoes.imagem_da_questao(db, mundo["joao"], alvo).tipo == "image/png"


def test_lista_do_aluno_diz_onde_ele_esta(db, mundo):
    sid = _publicado(db, mundo)
    _faz(db, mundo["joao"], sid, {})

    durante = simulados.listar_simulados(db, mundo["joao"], agora=DURANTE)[0]
    depois = simulados.listar_simulados(db, mundo["joao"], agora=DEPOIS)[0]

    assert durante["minha_prova"]["entregue"] is True and durante["resultado_disponivel"] is False
    assert depois["situacao"] == "ENCERRADO" and depois["resultado_disponivel"] is True


# --- manutenção --------------------------------------------------------------


def test_questao_em_simulado_por_acontecer_nao_sai_do_acervo(db, mundo):
    sid = _publicado(db, mundo)
    alvo = mundo["questoes"][0].id

    with pytest.raises(RegraDeNegocio) as erro:
        questoes.remover_questao(db, mundo["professor"], alvo)
    assert "editar_simulado" in str(erro.value)

    assert questoes.remover_questao(db, mundo["professor"], mundo["questoes"][2].id)["reversivel"]
    assert simulados.remover_simulado(db, mundo["professor"], sid, agora=AGORA)["reversivel"]


def test_remover_simulado_em_rascunho_leva_as_questoes_novas_junto(db, mundo):
    r = _rascunho(db, mundo, questoes_=[_nova(1), mundo["questoes"][0].id])

    saida = simulados.remover_simulado(db, mundo["professor"], r["simulado"]["simulado_id"])

    assert saida["questoes_novas_removidas"] == 1
    with pytest.raises(RegraDeNegocio):
        publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])


def test_publicado_nao_aparece_como_rascunho_para_o_aluno(db, mundo):
    r = _rascunho(db, mundo)
    assert simulados.listar_simulados(db, mundo["joao"]) == []

    publicacao.aprovar_e_publicar(db, mundo["professor"], r["rascunho_id"])
    listado = simulados.listar_simulados(db, mundo["joao"], agora=AGORA)
    assert [s["status"] for s in listado] == [Status.PUBLICADO]
