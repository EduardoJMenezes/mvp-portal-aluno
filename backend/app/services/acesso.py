"""Quem pode assistir o quê — e o que o backend conta sobre o que não pode.

Duas responsabilidades que andam juntas:

* **A decisão.** Um só lugar responde "esta pessoa pode assistir este vídeo?".
  Hoje a resposta sai de duas fontes: a matrícula, para o vídeo do curso, e a
  prova feita, para a resolução das questões de um simulado que já fechou.
  Quando existir compra, ganha mais uma fonte aqui dentro, e nenhum service
  precisa saber disso.
* **O que sai no lugar.** Vídeo bloqueado devolve o nome e mais nada — sem
  `embed_url`, sem thumbnail, sem duração.

A segunda parte não é detalhe de tela. `videos.embed_url` guarda a URL como o
Vimeo devolve, **com o hash de privacidade**: é ela que faz um vídeo unlisted
tocar. Serializar o objeto completo e esconder o player no frontend
transformaria o bloqueio em decoração, com o devtools liberando o acervo. Não
havendo nada no payload, não há o que vazar.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.identidade import Identidade
from app.models import (
    Item,
    Matricula,
    Modulo,
    Questao,
    Simulado,
    SimuladoQuestao,
    Status,
    SubModulo,
    Tentativa,
    Video,
)
from app.services.consultas import selecionar, vivos

MOTIVO_BLOQUEIO = "Não incluído no seu plano"


def videos_liberados(
    db: Session, ident: Identidade, video_ids: Iterable[int], agora: datetime | None = None
) -> set[int]:
    """Quais destes vídeos esta pessoa pode assistir.

    Em lote de propósito: a tela do aluno pergunta por dezenas de uma vez, e
    uma consulta por vídeo viraria dezenas de idas ao banco.
    """
    ids = {int(v) for v in video_ids}
    if not ids:
        return set()

    # Operador enxerga o acervo inteiro — é ele quem monta o curso.
    if ident.e_operador:
        return ids

    do_curso = (
        selecionar(Item.video_id)
        .join(SubModulo, SubModulo.id == Item.submodulo_id)
        .join(Modulo, Modulo.id == SubModulo.modulo_id)
        .join(Matricula, Matricula.turma_id == Modulo.turma_id)
        .where(
            Item.video_id.in_(ids),
            Item.status == Status.PUBLICADO,
            Matricula.usuario_id == ident.usuario_id,
        )
    )

    # A resolução vem com o resultado: para quem fez a prova, depois que ela
    # fecha. Antes disso, o vídeo seria o gabarito com narração.
    de_prova_feita = (
        select(Questao.video_id)
        .join(SimuladoQuestao, SimuladoQuestao.questao_id == Questao.id)
        .join(Simulado, Simulado.id == SimuladoQuestao.simulado_id)
        .join(Tentativa, Tentativa.simulado_id == Simulado.id)
        .where(
            Questao.video_id.in_(ids),
            Tentativa.aluno_id == ident.usuario_id,
            Simulado.status == Status.PUBLICADO,
            Simulado.fecha_em <= (agora or datetime.now(UTC)),
        )
    )

    return set(db.scalars(vivos(do_curso, Item, SubModulo, Modulo)).all()) | set(
        db.scalars(vivos(de_prova_feita, Simulado)).all()
    )


def pode_assistir(db: Session, ident: Identidade, video_id: int) -> bool:
    """A pergunta única. Prefira `videos_liberados` quando forem vários."""
    return video_id in videos_liberados(db, ident, [video_id])


def descrever_video(video: Video | None, liberado: bool) -> dict | None:
    """O vídeo como ele sai do backend.

    Bloqueado devolve o indispensável para o card existir na tela — nome e o
    aviso. Nada que permita assistir, e nada que revele de qual curso veio: a
    grade de outra turma não é da conta de quem não a comprou.
    """
    if video is None:
        return None

    if not liberado:
        return {
            "id": video.id,
            "titulo": video.titulo,
            "bloqueado": True,
            "motivo": MOTIVO_BLOQUEIO,
        }

    return {
        "id": video.id,
        "titulo": video.titulo,
        "bloqueado": False,
        "vimeo_id": video.vimeo_id,
        # Repassada como a API devolveu: sem o hash, o player recusa tocar
        # vídeo unlisted. Ver docs/VIMEO.md.
        "embed_url": video.embed_url,
        "thumbnail_url": video.thumbnail_url,
        "duracao_segundos": video.duracao_segundos,
    }
