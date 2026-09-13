"""Consulta do acervo: turmas, a árvore do curso e as questões de simulado.

Aqui mora a segregação por turma (seção 11). Ela é aplicada nas consultas, no
backend — o frontend não filtra nada por conta própria, e o MCP não tem
caminho alternativo até os dados.

O que o aluno vê é a árvore de `estrutura.py`, com os vídeos passando por
`acesso.py`: o que ele pode assistir vem completo, o resto vem como nome e
aviso. `Questao` não aparece aqui para ele — ela existe para o simulado.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoAutorizado, NaoEncontrado
from app.identidade import Identidade
from app.models import (
    Item,
    Matricula,
    Modulo,
    Questao,
    QuestaoAssunto,
    Status,
    SubModulo,
    Turma,
    Video,
)
from app.services import acesso, estrutura, taxonomia
from app.services.consultas import selecionar, vivos

# --- resolução de referências ------------------------------------------------
#
# Quem chama pelo MCP fala "Extensivo 2027", não `turma_id=2`. Estas funções
# aceitam id ou nome e, quando não acham, devolvem a lista do que existe — o
# agente se corrige sozinho em vez de insistir num id inventado.


def resolver_turma(db: Session, referencia: str | int) -> Turma:
    turmas = list(db.scalars(selecionar(Turma).order_by(Turma.nome)))

    if isinstance(referencia, int) or str(referencia).isdigit():
        alvo = [t for t in turmas if t.id == int(referencia)]
        if alvo:
            return alvo[0]

    texto = str(referencia).strip().lower()
    exatas = [t for t in turmas if t.nome.lower() == texto]
    if exatas:
        return exatas[0]

    parciais = [t for t in turmas if texto in t.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]
    if len(parciais) > 1:
        nomes = ", ".join(t.nome for t in parciais)
        raise NaoEncontrado(f"'{referencia}' corresponde a mais de uma turma: {nomes}.")

    disponiveis = ", ".join(t.nome for t in turmas) or "(nenhuma cadastrada)"
    raise NaoEncontrado(f"Turma '{referencia}' não existe. Turmas: {disponiveis}.")


# --- segregação --------------------------------------------------------------


def ids_das_turmas_do_aluno(db: Session, usuario_id: int) -> list[int]:
    return list(db.scalars(select(Matricula.turma_id).where(Matricula.usuario_id == usuario_id)))


def exigir_acesso_a_turma(db: Session, ident: Identidade, turma: Turma) -> None:
    """Operador vê qualquer turma; aluno só as suas.

    É a única porta: qualquer leitura de conteúdo de turma passa por aqui.
    """
    if ident.e_operador:
        return
    if turma.id not in ids_das_turmas_do_aluno(db, ident.usuario_id):
        raise NaoAutorizado(
            f"{ident.nome} não está matriculado em {turma.nome} e não pode ver este conteúdo."
        )


# --- turmas e a árvore do curso ----------------------------------------------


def listar_turmas(db: Session, ident: Identidade) -> list[dict]:
    consulta = selecionar(Turma).order_by(Turma.ano, Turma.nome)
    if ident.e_aluno:
        permitidas = ids_das_turmas_do_aluno(db, ident.usuario_id)
        consulta = consulta.where(Turma.id.in_(permitidas or [-1]))
    turmas = list(db.scalars(consulta))

    def _itens(turma_id: int, status: str) -> int:
        stmt = (
            selecionar(func.count(Item.id))
            .select_from(Item)
            .join(SubModulo, SubModulo.id == Item.submodulo_id)
            .join(Modulo, Modulo.id == SubModulo.modulo_id)
            .where(Modulo.turma_id == turma_id, Item.status == status)
        )
        return int(db.scalar(vivos(stmt, Item, SubModulo, Modulo)) or 0)

    def _modulos(turma_id: int) -> int:
        stmt = selecionar(func.count(Modulo.id)).select_from(Modulo).where(
            Modulo.turma_id == turma_id
        )
        return int(db.scalar(vivos(stmt, Modulo)) or 0)

    saida = []
    for t in turmas:
        dados = {
            "id": t.id,
            "nome": t.nome,
            "ano": t.ano,
            "alunos": db.scalar(
                select(func.count(Matricula.id)).where(Matricula.turma_id == t.id)
            ),
            "modulos": _modulos(t.id),
            "itens_publicados": _itens(t.id, Status.PUBLICADO),
        }
        if ident.e_operador:
            dados["itens_em_rascunho"] = _itens(t.id, Status.RASCUNHO)
        saida.append(dados)
    return saida


def listar_modulos(
    db: Session, ident: Identidade, turma: str | int | None = None
) -> list[dict]:
    """A árvore módulo › sub-módulo › item, de uma turma ou de todas as visíveis."""
    if turma is not None:
        alvos = [resolver_turma(db, turma)]
        exigir_acesso_a_turma(db, ident, alvos[0])
    elif ident.e_aluno:
        permitidas = ids_das_turmas_do_aluno(db, ident.usuario_id) or [-1]
        alvos = list(
            db.scalars(selecionar(Turma).where(Turma.id.in_(permitidas)).order_by(Turma.ano))
        )
    else:
        alvos = list(db.scalars(selecionar(Turma).order_by(Turma.ano, Turma.nome)))

    arvore = []
    for alvo in alvos:
        arvore.extend(estrutura.arvore_da_turma(db, alvo, apenas_publicados=ident.e_aluno))
    return arvore


def conteudo_do_aluno(db: Session, ident: Identidade) -> list[dict]:
    """O que a tela do aluno mostra: turma › módulo › sub-módulo › vídeos.

    O vídeo passa por `acesso.py` antes de sair: o que o aluno pode assistir
    vem com `embed_url`; o que não pode vem com nome e aviso, e nada mais.
    """
    if ident.e_aluno:
        turmas = list(
            db.scalars(
                selecionar(Turma)
                .where(Turma.id.in_(ids_das_turmas_do_aluno(db, ident.usuario_id) or [-1]))
                .order_by(Turma.ano)
            )
        )
    else:
        turmas = list(db.scalars(selecionar(Turma).order_by(Turma.ano, Turma.nome)))

    saida = []
    for turma in turmas:
        modulos = estrutura.arvore_da_turma(db, turma, apenas_publicados=ident.e_aluno)
        if not modulos:
            continue

        ids = {
            item["video_id"]
            for modulo in modulos
            for sub in modulo["submodulos"]
            for item in sub["itens"]
        }
        videos = {
            v.id: v for v in db.scalars(selecionar(Video).where(Video.id.in_(ids or [-1])))
        }
        liberados = acesso.videos_liberados(db, ident, ids)

        for modulo in modulos:
            for sub in modulo["submodulos"]:
                for item in sub["itens"]:
                    video = videos.get(item["video_id"])
                    item["video"] = acesso.descrever_video(
                        video, item["video_id"] in liberados
                    )

        saida.append({"turma": turma.nome, "turma_id": turma.id, "modulos": modulos})

    return saida


# --- questões (acervo de simulado) -------------------------------------------


def descrever_questao(db: Session, questao: Questao, incluir_gabarito: bool) -> dict:
    dados = {
        "questao_id": questao.id,
        "enunciado": questao.enunciado,
        "alternativas": {a.letra: a.texto for a in questao.alternativas},
        "dificuldade": questao.dificuldade,
        "status": questao.status,
        "assuntos": taxonomia.assuntos_do_video(db, questao.video_id)
        if questao.video_id
        else [],
        "classificacao": taxonomia.assuntos_da_questao(questao),
        "imagem_pendente": questao.imagem_pendente,
        "tem_imagem": questao.imagem_id is not None,
        "video_resolucao_id": questao.video_id,
    }
    if incluir_gabarito:
        dados["gabarito"] = questao.gabarito
    return dados


def buscar_questoes(
    db: Session,
    ident: Identidade,
    assunto: str | int | None = None,
    status: str | None = None,
    dificuldade: str | None = None,
    limite: int = 50,
) -> list[dict]:
    """Questões de simulado do acervo.

    Diferente da versão antiga, não filtra por turma: questão não pertence a
    turma nenhuma — o que pertence é o simulado onde ela entra. Por isso é
    consulta de operador; o aluno alcança questão pela prova, em `simulados.py`.
    """
    ident.exigir_operador()

    consulta = (
        selecionar(Questao)
        .options(
            selectinload(Questao.alternativas),
            selectinload(Questao.assuntos),
        )
        .order_by(Questao.id)
    )

    if assunto is not None:
        alvo = taxonomia.resolver_assunto(db, assunto)
        consulta = consulta.where(
            Questao.id.in_(
                select(QuestaoAssunto.questao_id).where(QuestaoAssunto.assunto_id == alvo.id)
            )
        )

    if status:
        consulta = consulta.where(Questao.status == status.upper())
    if dificuldade:
        consulta = consulta.where(Questao.dificuldade == dificuldade.upper())

    questoes = list(db.scalars(consulta.limit(limite)))
    return [descrever_questao(db, q, incluir_gabarito=True) for q in questoes]
