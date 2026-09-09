"""Cria o schema e os dados de demonstração (seção 7 do MVP).

    python -m app.seed          # cria o que faltar
    python -m app.seed --reset  # apaga tudo e recria

Deixa Cinética vazia no Extensivo 2027 de propósito: é o buraco que a
demonstração do fluxo Vimeo → plataforma preenche ao vivo.
"""

from __future__ import annotations

import sys

from sqlalchemy import select

from app.db import SessionLocal, engine
from app.models import (
    Alternativa,
    Base,
    Capitulo,
    Classificacao,
    Dificuldade,
    Matricula,
    Papel,
    Questao,
    Status,
    TokenMCP,
    Turma,
    TurmaQuestao,
    Usuario,
    Video,
)
from app.security import hash_senha, hash_token, novo_token_mcp

SENHA_DEMO = "demo1234"

QUESTOES = {
    "Estequiometria": [
        {
            "enunciado": (
                "Ao balancear a equação C3H8 + O2 → CO2 + H2O com os menores coeficientes "
                "inteiros, a soma de todos os coeficientes é:"
            ),
            "alternativas": {"A": "9", "B": "11", "C": "13", "D": "15", "E": "17"},
            "gabarito": "C",
            "subtopico": "Balanceamento",
            "dificuldade": Dificuldade.FACIL,
            "vimeo": "920000201",
        },
        {
            "enunciado": "Qual é a massa, em gramas, de 2,0 mol de CO2? (C = 12; O = 16)",
            "alternativas": {"A": "22 g", "B": "44 g", "C": "66 g", "D": "88 g", "E": "176 g"},
            "gabarito": "D",
            "subtopico": "Mol e massa molar",
            "dificuldade": Dificuldade.FACIL,
            "vimeo": "920000202",
        },
        {
            "enunciado": (
                "Na síntese N2 + 3 H2 → 2 NH3, parte-se de 1,0 mol de N2 e 2,0 mol de H2. "
                "Qual é o reagente limitante e a quantidade máxima de NH3 formada?"
            ),
            "alternativas": {
                "A": "N2; 2,0 mol de NH3",
                "B": "H2; aproximadamente 1,3 mol de NH3",
                "C": "H2; 2,0 mol de NH3",
                "D": "N2; aproximadamente 1,3 mol de NH3",
                "E": "Nenhum é limitante; 2,0 mol de NH3",
            },
            "gabarito": "B",
            "subtopico": "Reagente limitante",
            "dificuldade": Dificuldade.DIFICIL,
            "vimeo": "920000203",
        },
        {
            "enunciado": (
                "Uma reação deveria produzir 50 g de produto, mas produziu apenas 40 g. "
                "O rendimento do processo foi de:"
            ),
            "alternativas": {"A": "20%", "B": "40%", "C": "62,5%", "D": "80%", "E": "125%"},
            "gabarito": "D",
            "subtopico": "Rendimento de reação",
            "dificuldade": Dificuldade.MEDIA,
            "vimeo": "920000204",
        },
        {
            "enunciado": (
                "Uma amostra de 200 g de calcário apresenta 80% de pureza em CaCO3. "
                "A massa de CaCO3 presente na amostra é:"
            ),
            "alternativas": {"A": "20 g", "B": "80 g", "C": "120 g", "D": "160 g", "E": "200 g"},
            "gabarito": "D",
            "subtopico": "Pureza de reagentes",
            "dificuldade": Dificuldade.MEDIA,
            "vimeo": "920000205",
        },
    ],
    "Atomística": [
        {
            "enunciado": (
                "O experimento de Rutherford com a lâmina de ouro levou à conclusão de que o átomo:"
            ),
            "alternativas": {
                "A": "é maciço e indivisível",
                "B": "possui um núcleo pequeno, denso e positivo",
                "C": "tem elétrons em órbitas de energia quantizada",
                "D": "é uma esfera positiva com elétrons incrustados",
                "E": "não possui carga elétrica",
            },
            "gabarito": "B",
            "subtopico": "Modelos atômicos",
            "dificuldade": Dificuldade.FACIL,
            "vimeo": "910000101",
        },
        {
            "enunciado": (
                "Segundo o diagrama de Linus Pauling, a distribuição eletrônica do ferro "
                "(Z = 26) no estado fundamental termina em:"
            ),
            "alternativas": {"A": "3d⁶", "B": "4s²", "C": "4p⁶", "D": "3d⁸", "E": "4d⁶"},
            "gabarito": "A",
            "subtopico": "Distribuição eletrônica",
            "dificuldade": Dificuldade.MEDIA,
            "vimeo": "910000102",
        },
        {
            "enunciado": "Os átomos ⁴⁰₁₉K e ⁴⁰₂₀Ca são classificados como:",
            "alternativas": {
                "A": "isótopos",
                "B": "isóbaros",
                "C": "isótonos",
                "D": "alótropos",
                "E": "isoeletrônicos",
            },
            "gabarito": "B",
            "subtopico": "Isótopos, isóbaros e isótonos",
            "dificuldade": Dificuldade.MEDIA,
            "vimeo": "910000103",
        },
    ],
    "Cinética": [
        {
            "enunciado": (
                "A concentração de um reagente cai de 0,80 mol/L para 0,50 mol/L em 60 s. "
                "A velocidade média de consumo desse reagente é:"
            ),
            "alternativas": {
                "A": "0,0050 mol/(L·s)",
                "B": "0,050 mol/(L·s)",
                "C": "0,30 mol/(L·s)",
                "D": "0,013 mol/(L·s)",
                "E": "0,60 mol/(L·s)",
            },
            "gabarito": "A",
            "subtopico": "Velocidade média",
            "dificuldade": Dificuldade.MEDIA,
            "vimeo": "930000301",
        },
        {
            "enunciado": "Qual dos fatores abaixo NÃO altera a velocidade de uma reação química?",
            "alternativas": {
                "A": "temperatura",
                "B": "superfície de contato",
                "C": "presença de catalisador",
                "D": "concentração dos reagentes",
                "E": "variação de entalpia da reação",
            },
            "gabarito": "E",
            "subtopico": "Fatores de velocidade",
            "dificuldade": Dificuldade.FACIL,
            "vimeo": "930000302",
        },
        {
            "enunciado": "Um catalisador aumenta a velocidade de uma reação porque:",
            "alternativas": {
                "A": "aumenta a energia de ativação",
                "B": "oferece um caminho alternativo com menor energia de ativação",
                "C": "aumenta a variação de entalpia",
                "D": "desloca o equilíbrio no sentido dos produtos",
                "E": "eleva a temperatura do sistema",
            },
            "gabarito": "B",
            "subtopico": "Energia de ativação",
            "dificuldade": Dificuldade.DIFICIL,
            "vimeo": "930000303",
        },
    ],
}

# Quem enxerga o quê. Cinética não aparece no 2027: é o alvo da demonstração.
DISTRIBUICAO = {
    "Extensivo 2027": ["Estequiometria", "Atomística"],
    "Extensivo 2026": ["Atomística", "Cinética"],
}


def _video_demo(vimeo_id: str, titulo: str, pasta: str) -> Video:
    return Video(
        vimeo_id=vimeo_id,
        titulo=titulo,
        url=f"https://vimeo.com/{vimeo_id}",
        embed_url=f"https://player.vimeo.com/video/{vimeo_id}",
        pasta_vimeo=pasta,
        duracao_segundos=420,
    )


def povoar(reset: bool = False) -> None:
    if reset:
        Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)

    with SessionLocal() as db:
        if db.scalar(select(Usuario).limit(1)) is not None:
            print("Banco já tem dados. Use --reset para recriar do zero.")
            return

        professor = Usuario(
            nome="Prof. Helena Duarte",
            email="professor@escola.demo",
            senha_hash=hash_senha(SENHA_DEMO),
            papel=Papel.ADMIN,
        )
        gerenciador = Usuario(
            nome="Rafael Nunes",
            email="gerenciador@escola.demo",
            senha_hash=hash_senha(SENHA_DEMO),
            papel=Papel.GERENCIADOR,
        )
        db.add_all([professor, gerenciador])

        turmas = {
            "Extensivo 2026": Turma(nome="Extensivo 2026", ano=2026),
            "Extensivo 2027": Turma(nome="Extensivo 2027", ano=2027),
        }
        db.add_all(turmas.values())

        capitulos = {nome: Capitulo(nome=nome) for nome in ("Atomística", "Estequiometria", "Cinética")}
        db.add_all(capitulos.values())
        db.flush()

        alunos = [
            (Usuario(nome="João Pereira", email="joao@aluno.demo",
                     senha_hash=hash_senha(SENHA_DEMO), papel=Papel.ALUNO), "Extensivo 2027"),
            (Usuario(nome="Maria Souza", email="maria@aluno.demo",
                     senha_hash=hash_senha(SENHA_DEMO), papel=Papel.ALUNO), "Extensivo 2027"),
            (Usuario(nome="Pedro Lima", email="pedro@aluno.demo",
                     senha_hash=hash_senha(SENHA_DEMO), papel=Papel.ALUNO), "Extensivo 2026"),
        ]
        for aluno, turma in alunos:
            db.add(aluno)
            db.flush()
            db.add(Matricula(usuario_id=aluno.id, turma_id=turmas[turma].id))

        # Uma questão por linha do catálogo; o vínculo com a turma é quem
        # decide onde ela aparece — a mesma questão serve a mais de um ano.
        criadas: dict[str, list[Questao]] = {}
        for capitulo_nome, itens in QUESTOES.items():
            criadas[capitulo_nome] = []
            for item in itens:
                video = _video_demo(item["vimeo"], f"{capitulo_nome} — {item['subtopico']}", capitulo_nome)
                db.add(video)
                db.flush()

                questao = Questao(
                    enunciado=item["enunciado"],
                    gabarito=item["gabarito"],
                    dificuldade=item["dificuldade"],
                    video_id=video.id,
                    status=Status.PUBLICADO,
                    criado_por_id=professor.id,
                )
                db.add(questao)
                db.flush()

                for letra, texto in item["alternativas"].items():
                    db.add(Alternativa(questao_id=questao.id, letra=letra, texto=texto))
                db.add(
                    Classificacao(
                        questao_id=questao.id, topico=capitulo_nome, subtopico=item["subtopico"]
                    )
                )
                criadas[capitulo_nome].append(questao)

        for turma_nome, capitulos_da_turma in DISTRIBUICAO.items():
            for capitulo_nome in capitulos_da_turma:
                for numero, questao in enumerate(criadas[capitulo_nome], start=1):
                    db.add(
                        TurmaQuestao(
                            turma_id=turmas[turma_nome].id,
                            capitulo_id=capitulos[capitulo_nome].id,
                            questao_id=questao.id,
                            numero=numero,
                            status=Status.PUBLICADO,
                        )
                    )

        tokens = {}
        for usuario in (professor, gerenciador):
            valor = novo_token_mcp()
            db.add(
                TokenMCP(
                    usuario_id=usuario.id,
                    nome=f"Claude — {usuario.nome}",
                    token_hash=hash_token(valor),
                )
            )
            tokens[usuario.nome] = valor

        db.commit()

    print("\nBanco de demonstração criado.\n")
    print("Portal (senha de todos: %s)" % SENHA_DEMO)
    print("  professor@escola.demo     ADMIN")
    print("  gerenciador@escola.demo   GERENCIADOR")
    print("  joao@aluno.demo           ALUNO  — Extensivo 2027")
    print("  maria@aluno.demo          ALUNO  — Extensivo 2027")
    print("  pedro@aluno.demo          ALUNO  — Extensivo 2026")
    print("\nTokens do MCP (aparecem só agora; o banco guarda apenas o hash):")
    for nome, valor in tokens.items():
        print(f"  {nome}: {valor}")
    print()


if __name__ == "__main__":
    povoar(reset="--reset" in sys.argv)
