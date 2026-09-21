"""O importador de ponta a ponta: link de envio, arquivo, rascunho e revisão.

O rascunho que sai daqui continua precisando da aprovação de sempre para ir ao
ar — nada neste fluxo publica.
"""

import io
from datetime import timedelta
from functools import partial

import pytest
from PIL import Image

from app.errors import RegraDeNegocio
from app.models import Imagem, Importacao, ParteDaQuestao, Questao, Status
from app.services import importacoes, questoes, rascunhos
from tests.docx_de_teste import docx, figura, p, print_de_questao, questao


def _link(db, mundo):
    saida = importacoes.criar_link(db, mundo["professor_mcp"], ["Extensivo 2027"])
    return saida["importacao_id"], saida["link"].rsplit("/", 1)[-1]


def _simulado_com_figura_e_resolucao():
    return docx(
        p("SIMULADO 07"),
        p("01. Observe a estrutura:"), p(figura()),
        *[p(f"{letra}) cadeia {letra}") for letra in "abcde"],
        p("GABARITO: D"),
        p("A cadeia é normal."), p(figura()),
        *questao(2, "Qual o pH?", "A", ["Neutro."], marcador="LETRA "),
    )


def test_o_docx_vira_rascunho_com_figuras_no_lugar_e_resolucao_comentada(db, mundo):
    importacao_id, token = _link(db, mundo)

    recibo = importacoes.receber_arquivo(db, token, "Simulado 7.docx", _simulado_com_figura_e_resolucao())

    assert (recibo["questoes_lidas"], recibo["questoes_completas"]) == (2, 2)
    revisao, figuras = importacoes.revisar(db, mundo["professor"], importacao_id)
    assert revisao["titulo"] == "SIMULADO 07"
    primeira = revisao["questoes"][0]
    figura_id = primeira["figuras"][0]
    assert primeira["enunciado"] == f"Observe a estrutura:\n\n![](figura:{figura_id})"
    assert primeira["resolucao_comentada"] == f"A cadeia é normal.\n\n![](figura:{figura_id})"
    assert primeira["gabarito"] == "D" and revisao["questoes"][1]["gabarito"] == "A"
    assert [f.id for f in figuras] == [figura_id]

    imagem = db.get(Imagem, figura_id)
    assert (imagem.questao_id, imagem.parte) == (primeira["questao_id"], ParteDaQuestao.ENUNCIADO)
    assert db.get(Importacao, importacao_id).rascunho_id == revisao["rascunho_id"]


def test_nada_do_importador_chega_ao_aluno_sem_aprovacao(db, mundo):
    importacao_id, token = _link(db, mundo)
    importacoes.receber_arquivo(db, token, "s.docx", _simulado_com_figura_e_resolucao())

    revisao, _ = importacoes.revisar(db, mundo["professor"], importacao_id)
    detalhe = questoes.detalhar_questao(db, mundo["professor"], revisao["questoes"][0]["questao_id"])
    assert detalhe["status"] == Status.RASCUNHO


def test_link_e_de_uso_unico_e_tem_prazo(db, mundo):
    _, token = _link(db, mundo)
    importacoes.receber_arquivo(db, token, "s.docx", _simulado_com_figura_e_resolucao())

    with pytest.raises(RegraDeNegocio) as usado:
        importacoes.receber_arquivo(db, token, "s.docx", _simulado_com_figura_e_resolucao())
    assert "já recebeu" in str(usado.value)

    _, outro = _link(db, mundo)
    depois = db.get(Importacao, _).expira_em + timedelta(minutes=1)
    with pytest.raises(RegraDeNegocio) as expirado:
        importacoes.receber_arquivo(db, outro, "s.docx", _simulado_com_figura_e_resolucao(), agora=depois)
    assert "expirou" in str(expirado.value)


def test_arquivo_errado_nao_gasta_o_link(db, mundo):
    _, token = _link(db, mundo)

    with pytest.raises(RegraDeNegocio):
        importacoes.receber_arquivo(db, token, "simulado.pdf", b"%PDF")

    assert importacoes.situacao_do_link(db, token)["situacao"] == "AGUARDANDO"


def test_questao_que_as_regras_nao_fecharam_e_completada_pelos_blocos(db, mundo):
    importacao_id, token = _link(db, mundo)
    sem_gabarito = questao(2, "Qual a massa?", None, [])
    arquivo = docx(*questao(1, "Primeira", "A", ["ok"]), *sem_gabarito, *questao(3, "Terceira", "C", ["ok"]))
    importacoes.receber_arquivo(db, token, "s.docx", arquivo)

    revisao, _ = importacoes.revisar(db, mundo["professor"], importacao_id)
    assert revisao["total_questoes"] == 2
    [incompleta] = revisao["incompletas"]
    indices = [b["indice"] for b in incompleta["blocos"]]

    saida = importacoes.completar_questao(
        db, mundo["professor"], importacao_id, numero=2, enunciado=str(indices[0]),
        alternativas=f"{indices[1]}-{indices[5]}", gabarito="B",
    )

    assert saida["ordem"] == 2, "entra na posição do número dela"
    revisao, _ = importacoes.revisar(db, mundo["professor"], importacao_id)
    nova = revisao["questoes"][1]
    assert (nova["enunciado"], nova["alternativas"]["A"], nova["gabarito"]) == (
        "Qual a massa?", "alternativa a da 2", "B"
    )
    assert revisao["incompletas"] == []


# --- prints ------------------------------------------------------------------


def test_prints_chegam_pelo_link_e_o_claude_ve_na_escala_do_recorte(db, mundo):
    saida = importacoes.criar_link_de_prints(db, mundo["professor_mcp"])
    token = saida["link"].rsplit("/", 1)[-1]
    assert importacoes.situacao_do_link(db, token)["formato"] == "prints"

    with pytest.raises(RegraDeNegocio) as docx_no_link:
        importacoes.receber_arquivo(db, token, "s.docx", _simulado_com_figura_e_resolucao())
    assert "prints" in str(docx_no_link.value)
    with pytest.raises(RegraDeNegocio):
        importacoes.receber_prints(db, token, [("prova.pdf", b"%PDF-1.4")])
    assert importacoes.situacao_do_link(db, token)["situacao"] == "AGUARDANDO"

    importacoes.receber_prints(db, token, [("tela.png", print_de_questao(3000, 2000)),
                                           ("site.png", print_de_questao())])

    dados, vistas = importacoes.ver_prints(db, mundo["professor"], saida["importacao_id"])
    grande, pequeno = dados["prints"]
    assert max(grande["largura"], grande["altura"]) <= importacoes.LADO_DA_VISTA
    assert grande["largura"] * grande["altura"] <= importacoes.PIXELS_DA_VISTA
    assert 900 < pequeno["largura"] <= 900 * importacoes.AMPLIACAO_MAXIMA, "print pequeno cresce na vista"
    assert pequeno["largura"] * pequeno["altura"] <= importacoes.PIXELS_DA_VISTA
    assert len(vistas) == 2
    with pytest.raises(RegraDeNegocio) as usado:
        importacoes.receber_prints(db, token, [("outro.png", print_de_questao())])
    assert "já recebeu" in str(usado.value)


def test_figura_recortada_do_print_entra_no_lugar_da_marca(db, mundo):
    link = importacoes.criar_link_de_prints(db, mundo["professor_mcp"])
    importacoes.receber_prints(db, link["link"].rsplit("/", 1)[-1], [("tela.png", print_de_questao(3000, 2000))])
    vista = importacoes.ver_prints(db, mundo["professor"], link["importacao_id"])[0]["prints"][0]
    marca = "![](figura:pendente)"
    rascunho = rascunhos.criar_simulado_rascunho(db, mundo["professor"], ["Extensivo 2027"], "Prints", [{
        "enunciado": f"Observe:\n\n{marca}",
        "alternativas": {"A": "um", "B": "dois", "C": marca, "D": "quatro", "E": "cinco"},
        "gabarito": "C",
    }])
    questao_id = rascunho["simulado"]["questoes"][0]["questao_id"]
    recortar = partial(importacoes.recortar_figura, db, mundo["professor"], link["importacao_id"], 1, questao_id)

    # O retângulo vem na escala da vista (a tela de 3000 px foi reduzida); o
    # recorte sai do original, só com a figura: 201 px mais a folga de 8 de cada lado.
    saida, png = recortar([35, 55, 145, 165])
    with Image.open(io.BytesIO(png)) as recorte:
        assert recorte.size == (217, 217)
    detalhe = questoes.detalhar_questao(db, mundo["professor"], questao_id)
    assert detalhe["enunciado"] == f"Observe:\n\n![](figura:{saida['figura_id']})"
    assert saida["imagem_pendente"] is True, "a alternativa C ainda espera a figura dela"

    na_c, _ = recortar([35, 55, 145, 165], parte="ALTERNATIVA", alternativa="C")
    assert na_c["imagem_pendente"] is False
    assert questoes.detalhar_questao(db, mundo["professor"], questao_id)["alternativas"]["C"] == (
        f"![](figura:{na_c['figura_id']})"
    )

    # Recorte que pegou texto se troca sem mexer no texto da questão.
    trocado, novo = recortar([0, 0, 300, 200], substituir=saida["figura_id"])
    assert trocado["figura_id"] == saida["figura_id"]
    assert db.get(Imagem, saida["figura_id"]).conteudo == novo
    assert questoes.detalhar_questao(db, mundo["professor"], questao_id)["enunciado"] == detalhe["enunciado"]

    with pytest.raises(RegraDeNegocio) as fora:
        recortar([0, 0, 3000, 2000])
    assert f"{vista['largura']}×{vista['altura']}" in str(fora.value)

    db.get(Questao, questao_id).status = Status.PUBLICADO
    db.commit()
    with pytest.raises(RegraDeNegocio) as publicada:
        recortar([35, 55, 145, 165])
    assert "rascunho" in str(publicada.value)


def test_retangulo_curto_nao_corta_o_desenho(db, mundo):
    """No primeiro teste real, três de oito figuras saíram cortadas de prints pequenos."""
    link = importacoes.criar_link_de_prints(db, mundo["professor_mcp"])
    importacoes.receber_prints(db, link["link"].rsplit("/", 1)[-1], [("site.png", print_de_questao())])
    rascunho = rascunhos.criar_simulado_rascunho(db, mundo["professor"], ["Extensivo 2027"], "Curto", [{
        "enunciado": "![](figura:pendente)\n\n![](figura:pendente)",
        "alternativas": {letra: letra for letra in "ABCDE"}, "gabarito": "A",
    }])
    questao_id = rascunho["simulado"]["questoes"][0]["questao_id"]
    recortar = partial(importacoes.recortar_figura, db, mundo["professor"], link["importacao_id"], 1, questao_id)
    escala = importacoes._tamanho_da_vista(900, 600)[0] / 900

    def tamanho(saida):
        with Image.open(io.BytesIO(db.get(Imagem, saida["figura_id"]).conteudo)) as figura:
            return figura.size

    # Só o miolo da figura (150–250, dentro de 100–300): as quatro bordas cortam o traço.
    miolo = [150 * escala, 200 * escala, 250 * escala, 300 * escala]
    inteira, previa = recortar(miolo)
    cortada, _ = recortar(miolo, estender=False)

    assert tamanho(inteira) == (217, 217), "estende até a faixa em branco, sem pegar as linhas de texto"
    assert tamanho(cortada) == (116, 116)
    with Image.open(io.BytesIO(previa)) as vista:
        assert vista.width > 217, "a prévia vem na escala ampliada em que o Claude viu o print"
