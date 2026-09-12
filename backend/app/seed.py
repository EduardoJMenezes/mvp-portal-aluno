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
    Assunto,
    Base,
    Dificuldade,
    Item,
    Matricula,
    Modulo,
    Papel,
    Questao,
    QuestaoAssunto,
    Status,
    SubAssunto,
    SubModulo,
    TipoSubModulo,
    TokenMCP,
    Turma,
    Usuario,
    Video,
    VideoAssunto,
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

        # A taxonomia é global: "Estequiometria" vale para 2026 e 2027, e é
        # ela que liga o erro do aluno ao vídeo que explica aquilo. O nome
        # nunca leva o K0X — esse é o endereço na apostila de uma turma.
        assuntos = {nome: Assunto(nome=nome) for nome in QUESTOES}
        db.add_all(assuntos.values())
        db.flush()

        subassuntos: dict[tuple[str, str], SubAssunto] = {}
        for assunto_nome, itens in QUESTOES.items():
            for item in itens:
                chave = (assunto_nome, item["subtopico"])
                if chave not in subassuntos:
                    sub = SubAssunto(assunto_id=assuntos[assunto_nome].id, nome=item["subtopico"])
                    db.add(sub)
                    subassuntos[chave] = sub
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

        # O acervo é global e reaproveitável: um vídeo por linha do catálogo,
        # classificado uma vez. Onde ele aparece é decisão da turma, logo
        # abaixo — e é isso que faz um vídeo do 2027 poder aparecer bloqueado
        # para um aluno do 2026 que errou aquele assunto.
        videos: dict[str, list[Video]] = {}
        questoes: dict[str, list[Questao]] = {}

        for assunto_nome, itens in QUESTOES.items():
            videos[assunto_nome] = []
            questoes[assunto_nome] = []
            for item in itens:
                video = _video_demo(
                    item["vimeo"], f"{assunto_nome} — {item['subtopico']}", assunto_nome
                )
                db.add(video)
                db.flush()
                db.add(
                    VideoAssunto(
                        video_id=video.id,
                        assunto_id=assuntos[assunto_nome].id,
                        subassunto_id=subassuntos[(assunto_nome, item["subtopico"])].id,
                    )
                )
                videos[assunto_nome].append(video)

                # A questão com gabarito serve ao simulado, e só a ele: a
                # questão da apostila mora na apostila, e o que entra no curso
                # é o vídeo da resolução dela.
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
                    QuestaoAssunto(
                        questao_id=questao.id,
                        assunto_id=assuntos[assunto_nome].id,
                        subassunto_id=subassuntos[(assunto_nome, item["subtopico"])].id,
                    )
                )
                questoes[assunto_nome].append(questao)

        # A organização é da turma: cada uma numera os próprios capítulos, com
        # "Aulas" e "Questões da apostila" como sub-módulos — que é o formato
        # das pastas do acervo real no Vimeo.
        for turma_nome, capitulos_da_turma in DISTRIBUICAO.items():
            for posicao, assunto_nome in enumerate(capitulos_da_turma, start=1):
                modulo = Modulo(
                    turma_id=turmas[turma_nome].id,
                    nome=f"K{posicao:02d} - {assunto_nome}",
                    ordem=posicao,
                )
                db.add(modulo)
                db.flush()

                aulas = SubModulo(
                    modulo_id=modulo.id, nome="Aulas", tipo=TipoSubModulo.VIDEO, ordem=1
                )
                apostila = SubModulo(
                    modulo_id=modulo.id,
                    nome="Questões da apostila",
                    tipo=TipoSubModulo.VIDEO,
                    ordem=2,
                )
                db.add_all([aulas, apostila])
                db.flush()

                # Poucas aulas e longas; muitas questões e curtas.
                db.add(
                    Item(
                        submodulo_id=aulas.id,
                        video_id=videos[assunto_nome][0].id,
                        nome=f"Aula 1 — {assunto_nome}",
                        ordem=1,
                        status=Status.PUBLICADO,
                    )
                )
                for numero, video in enumerate(videos[assunto_nome], start=1):
                    db.add(
                        Item(
                            submodulo_id=apostila.id,
                            video_id=video.id,
                            nome=f"Q{numero:02d}",
                            ordem=numero,
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
