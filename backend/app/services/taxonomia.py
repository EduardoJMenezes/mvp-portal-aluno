"""Assunto e sub-assunto: a etiqueta do conteúdo.

É o eixo que atravessa turmas e anos, e é por isso que o nome de um assunto
**nunca** carrega numeração de capítulo. "K03" é a posição na apostila de uma
turma — no acervo real, K03 é Estequiometria em 2026 e Tabela Periódica em
2025. Uma etiqueta presa a um ano não etiqueta nada.

    MÓDULO (da turma)                     ASSUNTO (global)
    "K03 - Estequiometria"     (2026)     "Estequiometria" › "Pureza e rendimento"
    "K03 - Tabela Periódica"   (2025)     "Tabela Periódica" › "Propriedades periódicas"

A etiqueta mora no acervo (`Video`, `Questao`), não na organização (`Item`):
se o mesmo vídeo aparece em 2026 e 2027, ensina a mesma coisa nos dois, e
classificar uma vez basta.

É esta tabela que fecha o ciclo do simulado: o aluno erra uma questão de
"Pureza e rendimento" e a plataforma sabe quais vídeos explicam exatamente
aquilo — inclusive vídeos de outras turmas, que aparecem bloqueados.
"""

from __future__ import annotations

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.errors import NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    Assunto,
    Item,
    Modulo,
    Questao,
    QuestaoAssunto,
    Status,
    SubAssunto,
    SubModulo,
    Video,
    VideoAssunto,
)
from app.services.consultas import remover, selecionar, tocar, vivos


# --- cadastro ----------------------------------------------------------------


def listar_assuntos(db: Session) -> list[dict]:
    assuntos = list(db.scalars(selecionar(Assunto).order_by(Assunto.nome)))
    return [
        {
            "id": a.id,
            "nome": a.nome,
            "subassuntos": [
                {"id": s.id, "nome": s.nome}
                for s in db.scalars(
                    selecionar(SubAssunto)
                    .where(SubAssunto.assunto_id == a.id)
                    .order_by(SubAssunto.nome)
                )
            ],
        }
        for a in assuntos
    ]


def resolver_assunto(db: Session, referencia: str | int) -> Assunto:
    assuntos = list(db.scalars(selecionar(Assunto).order_by(Assunto.nome)))

    if isinstance(referencia, int) or str(referencia).strip().isdigit():
        alvo = [a for a in assuntos if a.id == int(referencia)]
        if alvo:
            return alvo[0]

    texto = str(referencia).strip().lower()
    exatas = [a for a in assuntos if a.nome.lower() == texto]
    if exatas:
        return exatas[0]

    parciais = [a for a in assuntos if texto in a.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]

    nomes = ", ".join(f"'{a.nome}'" for a in assuntos) or "nenhum cadastrado"
    if len(parciais) > 1:
        raise RegraDeNegocio(
            f"'{referencia}' casa com mais de um assunto: "
            f"{', '.join(repr(a.nome) for a in parciais)}. Diga qual."
        )
    raise NaoEncontrado(
        f"Assunto '{referencia}' não existe. Assuntos: {nomes}. "
        "Para criar um novo, use criar_assunto."
    )


def resolver_subassunto(db: Session, assunto: Assunto, referencia: str | int) -> SubAssunto:
    subs = list(
        db.scalars(
            selecionar(SubAssunto)
            .where(SubAssunto.assunto_id == assunto.id)
            .order_by(SubAssunto.nome)
        )
    )

    if isinstance(referencia, int) or str(referencia).strip().isdigit():
        alvo = [s for s in subs if s.id == int(referencia)]
        if alvo:
            return alvo[0]

    texto = str(referencia).strip().lower()
    exatas = [s for s in subs if s.nome.lower() == texto]
    if exatas:
        return exatas[0]

    parciais = [s for s in subs if texto in s.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]

    nomes = ", ".join(f"'{s.nome}'" for s in subs) or "nenhum cadastrado"
    raise NaoEncontrado(
        f"Sub-assunto '{referencia}' não existe em '{assunto.nome}'. Há: {nomes}."
    )


def criar_assunto(db: Session, ident: Identidade, nome: str) -> Assunto:
    ident.exigir_operador()
    nome = (nome or "").strip()
    if not nome:
        raise RegraDeNegocio("O assunto precisa de um nome, ex.: 'Estequiometria'.")

    ja_existe = db.scalar(selecionar(Assunto).where(func.lower(Assunto.nome) == nome.lower()))
    if ja_existe is not None:
        return ja_existe

    assunto = Assunto(nome=nome)
    tocar(ident, assunto)
    db.add(assunto)
    db.flush()
    return assunto


def criar_subassunto(db: Session, ident: Identidade, assunto: Assunto, nome: str) -> SubAssunto:
    ident.exigir_operador()
    nome = (nome or "").strip()
    if not nome:
        raise RegraDeNegocio("O sub-assunto precisa de um nome, ex.: 'Pureza e rendimento'.")

    ja_existe = db.scalar(
        selecionar(SubAssunto).where(
            SubAssunto.assunto_id == assunto.id, func.lower(SubAssunto.nome) == nome.lower()
        )
    )
    if ja_existe is not None:
        return ja_existe

    sub = SubAssunto(assunto_id=assunto.id, nome=nome)
    tocar(ident, sub)
    db.add(sub)
    db.flush()
    return sub


def renomear_assunto(db: Session, ident: Identidade, assunto: Assunto, nome: str) -> Assunto:
    ident.exigir_operador()
    nome = (nome or "").strip()
    if not nome:
        raise RegraDeNegocio("O nome do assunto não pode ficar vazio.")
    assunto.nome = nome
    tocar(ident, assunto)
    db.flush()
    return assunto


def remover_assunto(db: Session, ident: Identidade, assunto: Assunto) -> dict:
    """Remove o assunto. Os vínculos ficam — e voltam a valer se ele for
    restaurado, que é a razão de nada ser apagado de verdade."""
    ident.exigir_operador()
    videos = int(
        db.scalar(
            selecionar(func.count(VideoAssunto.id))
            .select_from(VideoAssunto)
            .where(VideoAssunto.assunto_id == assunto.id)
        )
        or 0
    )  # vínculo não tem remoção lógica: é ligação, não conteúdo
    questoes = int(
        db.scalar(
            selecionar(func.count(QuestaoAssunto.id))
            .select_from(QuestaoAssunto)
            .where(QuestaoAssunto.assunto_id == assunto.id)
        )
        or 0
    )
    remover(db, ident, assunto)
    return {
        "assunto": assunto.nome,
        "videos_que_perdem_a_etiqueta": videos,
        "questoes_que_perdem_a_etiqueta": questoes,
        "reversivel": True,
    }


# --- classificação -----------------------------------------------------------


def classificar_video(
    db: Session,
    ident: Identidade,
    video: Video,
    assunto: Assunto,
    subassunto: SubAssunto | None = None,
) -> VideoAssunto:
    ident.exigir_operador()
    sub_id = subassunto.id if subassunto else None

    ja_existe = db.scalar(
        selecionar(VideoAssunto).where(
            VideoAssunto.video_id == video.id,
            VideoAssunto.assunto_id == assunto.id,
            VideoAssunto.subassunto_id.is_(None) if sub_id is None
            else VideoAssunto.subassunto_id == sub_id,
        )
    )
    if ja_existe is not None:
        return ja_existe

    vinculo = VideoAssunto(video_id=video.id, assunto_id=assunto.id, subassunto_id=sub_id)
    db.add(vinculo)
    tocar(ident, video)
    db.flush()
    return vinculo


def classificar_questao(
    db: Session,
    ident: Identidade,
    questao: Questao,
    assunto: Assunto,
    subassunto: SubAssunto | None = None,
) -> QuestaoAssunto:
    ident.exigir_operador()
    sub_id = subassunto.id if subassunto else None

    ja_existe = db.scalar(
        selecionar(QuestaoAssunto).where(
            QuestaoAssunto.questao_id == questao.id,
            QuestaoAssunto.assunto_id == assunto.id,
            QuestaoAssunto.subassunto_id.is_(None) if sub_id is None
            else QuestaoAssunto.subassunto_id == sub_id,
        )
    )
    if ja_existe is not None:
        return ja_existe

    vinculo = QuestaoAssunto(assunto_id=assunto.id, subassunto_id=sub_id)
    questao.assuntos.append(vinculo)  # pela coleção, para ela não ficar velha na sessão
    tocar(ident, questao)
    db.flush()
    return vinculo


def desclassificar_video(db: Session, ident: Identidade, video: Video, assunto: Assunto) -> int:
    """Tira do vídeo todas as etiquetas deste assunto.

    Vínculo é ligação, não conteúdo: aqui o DELETE é o comportamento certo —
    reclassificar não deveria deixar entulho para sempre.
    """
    ident.exigir_operador()
    vinculos = list(
        db.scalars(
            selecionar(VideoAssunto).where(
                VideoAssunto.video_id == video.id, VideoAssunto.assunto_id == assunto.id
            )
        )
    )
    for v in vinculos:
        db.delete(v)
    tocar(ident, video)
    db.flush()
    return len(vinculos)


def assuntos_do_video(db: Session, video_id: int) -> list[dict]:
    vinculos = db.scalars(
        selecionar(VideoAssunto).where(VideoAssunto.video_id == video_id)
    ).all()
    return [
        {
            "assunto": v.assunto.nome,
            "subassunto": v.subassunto.nome if v.subassunto else None,
        }
        for v in vinculos
        if v.assunto.removido_em is None
    ]


def assuntos_da_questao(questao: Questao) -> list[dict]:
    return [
        {
            "assunto": v.assunto.nome,
            "subassunto": v.subassunto.nome if v.subassunto else None,
        }
        for v in questao.assuntos
        if v.assunto.removido_em is None
    ]


# --- o elo que o simulado usa ------------------------------------------------


def videos_que_explicam(
    db: Session,
    assunto_id: int,
    subassunto_id: int | None = None,
    limite: int = 5,
) -> list[Video]:
    """Vídeos etiquetados com este assunto — o outro lado do erro do aluno.

    Busca no acervo inteiro, sem olhar turma: quem decide o que o aluno pode
    assistir é `services/acesso.py`, e o que ele não pode aparece bloqueado.
    Filtrar aqui esconderia justamente o vídeo que explica a dúvida dele.

    Sub-assunto primeiro, porque é a recomendação que acerta o alvo; o assunto
    completa a lista quando falta material fino.
    """
    encontrados: list[Video] = []
    vistos: set[int] = set()

    def acrescentar(stmt):
        for video in db.scalars(vivos(stmt, Video)):
            if video.id not in vistos:
                vistos.add(video.id)
                encontrados.append(video)

    if subassunto_id is not None:
        acrescentar(
            selecionar(Video)
            .join(VideoAssunto, VideoAssunto.video_id == Video.id)
            .where(VideoAssunto.subassunto_id == subassunto_id)
            .limit(limite)
        )

    if len(encontrados) < limite:
        acrescentar(
            selecionar(Video)
            .join(VideoAssunto, VideoAssunto.video_id == Video.id)
            .where(VideoAssunto.assunto_id == assunto_id)
            .limit(limite)
        )

    return encontrados[:limite]


def onde_o_video_aparece(db: Session, video_id: int) -> list[dict]:
    """Em que módulos publicados este vídeo está — para o card dizer de onde
    ele veio quando o aluno tem acesso."""
    stmt = (
        selecionar(Item, SubModulo, Modulo)
        .join(SubModulo, SubModulo.id == Item.submodulo_id)
        .join(Modulo, Modulo.id == SubModulo.modulo_id)
        .where(Item.video_id == video_id, Item.status == Status.PUBLICADO)
    )
    return [
        {
            "turma_id": modulo.turma_id,
            "modulo": modulo.nome,
            "submodulo": sub.nome,
            "item": item.nome,
        }
        for item, sub, modulo in db.execute(vivos(stmt, SubModulo, Modulo)).all()
    ]


def cadastrar_assunto(
    db: Session, ident: Identidade, nome: str, subassuntos: list[str] | None = None
) -> dict:
    """O assunto e, de uma vez, os sub-assuntos dele. Repetir não duplica."""
    assunto = criar_assunto(db, ident, nome)
    criados = [criar_subassunto(db, ident, assunto, s) for s in subassuntos or []]
    db.commit()
    return {
        "assunto_id": assunto.id,
        "assunto": assunto.nome,
        "subassuntos": [s.nome for s in criados],
    }
