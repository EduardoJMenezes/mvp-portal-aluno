"""Consulta do acervo: turmas, capítulos e questões.

Aqui mora a segregação por turma (seção 11). Ela é aplicada nas consultas, no
backend — o frontend não filtra nada por conta própria, e o MCP não tem
caminho alternativo até os dados.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoAutorizado, NaoEncontrado
from app.identidade import Identidade
from app.models import (
    Capitulo,
    Matricula,
    Questao,
    Status,
    Turma,
    TurmaQuestao,
)


# --- resolução de referências ------------------------------------------------
#
# Quem chama pelo MCP fala "Extensivo 2027", não `turma_id=2`. Estas funções
# aceitam id ou nome e, quando não acham, devolvem a lista do que existe — o
# agente se corrige sozinho em vez de insistir num id inventado.


def resolver_turma(db: Session, referencia: str | int) -> Turma:
    if isinstance(referencia, int) or str(referencia).isdigit():
        turma = db.get(Turma, int(referencia))
        if turma:
            return turma

    texto = str(referencia).strip().lower()
    turmas = db.scalars(select(Turma).order_by(Turma.nome)).all()
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


def resolver_capitulo(db: Session, referencia: str | int) -> Capitulo:
    if isinstance(referencia, int) or str(referencia).isdigit():
        cap = db.get(Capitulo, int(referencia))
        if cap:
            return cap

    texto = str(referencia).strip().lower()
    capitulos = db.scalars(select(Capitulo).order_by(Capitulo.nome)).all()
    exatos = [c for c in capitulos if c.nome.lower() == texto]
    if exatos:
        return exatos[0]

    parciais = [c for c in capitulos if texto in c.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]
    if len(parciais) > 1:
        nomes = ", ".join(c.nome for c in parciais)
        raise NaoEncontrado(f"'{referencia}' corresponde a mais de um capítulo: {nomes}.")

    disponiveis = ", ".join(c.nome for c in capitulos) or "(nenhum cadastrado)"
    raise NaoEncontrado(f"Capítulo '{referencia}' não existe. Capítulos: {disponiveis}.")


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


# --- consultas ---------------------------------------------------------------


def listar_turmas(db: Session, ident: Identidade) -> list[dict]:
    consulta = select(Turma).order_by(Turma.ano, Turma.nome)
    if ident.e_aluno:
        permitidas = ids_das_turmas_do_aluno(db, ident.usuario_id)
        consulta = consulta.where(Turma.id.in_(permitidas or [-1]))
    turmas = db.scalars(consulta).all()

    def _contar(turma_id: int, status: str) -> int:
        return db.scalar(
            select(func.count(TurmaQuestao.id)).where(
                TurmaQuestao.turma_id == turma_id, TurmaQuestao.status == status
            )
        )

    saida = []
    for t in turmas:
        item = {
            "id": t.id,
            "nome": t.nome,
            "ano": t.ano,
            "alunos": db.scalar(
                select(func.count(Matricula.id)).where(Matricula.turma_id == t.id)
            ),
            "questoes_publicadas": _contar(t.id, Status.PUBLICADO),
        }
        if ident.e_operador:
            item["questoes_em_rascunho"] = _contar(t.id, Status.RASCUNHO)
        saida.append(item)
    return saida


def listar_capitulos(db: Session) -> list[dict]:
    capitulos = db.scalars(select(Capitulo).order_by(Capitulo.nome)).all()
    return [{"id": c.id, "nome": c.nome} for c in capitulos]


def _questao_para_dict(vinculo: TurmaQuestao, incluir_gabarito: bool) -> dict:
    q = vinculo.questao
    classificacao = q.classificacoes[0] if q.classificacoes else None
    dados = {
        "vinculo_id": vinculo.id,
        "questao_id": q.id,
        "numero": vinculo.numero,
        "turma": vinculo.turma.nome,
        "capitulo": vinculo.capitulo.nome,
        "enunciado": q.enunciado,
        "alternativas": {a.letra: a.texto for a in q.alternativas},
        "dificuldade": q.dificuldade,
        "topico": classificacao.topico if classificacao else None,
        "subtopico": classificacao.subtopico if classificacao else None,
        "status": vinculo.status,
        "video": (
            {
                "vimeo_id": q.video.vimeo_id,
                "titulo": q.video.titulo,
                "url": q.video.url,
                # O embed guardado tem o hash de vídeo unlisted; a URL montada
                # a partir do id só serve para vídeo público.
                "embed_url": (
                    q.video.embed_url or f"https://player.vimeo.com/video/{q.video.vimeo_id}"
                ),
            }
            if q.video
            else None
        ),
    }
    if incluir_gabarito:
        dados["gabarito"] = q.gabarito
    return dados


def buscar_questoes(
    db: Session,
    ident: Identidade,
    turma: str | int | None = None,
    capitulo: str | int | None = None,
    status: str | None = None,
    limite: int = 50,
) -> list[dict]:
    """Questões visíveis para esta identidade, sempre pelo vínculo com a turma.

    Aluno recebe só o que está publicado nas turmas dele, e sem gabarito.
    """
    consulta = (
        select(TurmaQuestao)
        .options(
            selectinload(TurmaQuestao.questao).selectinload(Questao.alternativas),
            selectinload(TurmaQuestao.questao).selectinload(Questao.classificacoes),
            selectinload(TurmaQuestao.questao).selectinload(Questao.video),
            selectinload(TurmaQuestao.turma),
            selectinload(TurmaQuestao.capitulo),
        )
        .order_by(TurmaQuestao.turma_id, TurmaQuestao.capitulo_id, TurmaQuestao.numero)
    )

    if turma is not None:
        alvo = resolver_turma(db, turma)
        exigir_acesso_a_turma(db, ident, alvo)
        consulta = consulta.where(TurmaQuestao.turma_id == alvo.id)
    elif ident.e_aluno:
        consulta = consulta.where(
            TurmaQuestao.turma_id.in_(ids_das_turmas_do_aluno(db, ident.usuario_id) or [-1])
        )

    if capitulo is not None:
        consulta = consulta.where(TurmaQuestao.capitulo_id == resolver_capitulo(db, capitulo).id)

    if ident.e_aluno:
        consulta = consulta.where(TurmaQuestao.status == Status.PUBLICADO)
    elif status:
        consulta = consulta.where(TurmaQuestao.status == status.upper())

    vinculos = db.scalars(consulta.limit(limite)).all()
    return [_questao_para_dict(v, incluir_gabarito=ident.e_operador) for v in vinculos]


def conteudo_do_aluno(db: Session, ident: Identidade) -> list[dict]:
    """Árvore turma > capítulo > questões publicadas, para a tela do aluno."""
    questoes = buscar_questoes(db, ident, limite=500)

    arvore: dict[str, dict] = {}
    for q in questoes:
        turma = arvore.setdefault(q["turma"], {"turma": q["turma"], "capitulos": {}})
        cap = turma["capitulos"].setdefault(q["capitulo"], {"capitulo": q["capitulo"], "questoes": []})
        cap["questoes"].append(q)

    return [
        {"turma": t["turma"], "capitulos": list(t["capitulos"].values())} for t in arvore.values()
    ]
