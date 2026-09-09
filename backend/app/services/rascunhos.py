"""Criação de rascunhos.

Regra fundamental da seção 6: a IA propõe, o humano aprova, o backend publica.
Aqui está a metade "propõe" — e ela é estrutural, não uma recomendação de
prompt: nenhuma função deste módulo aceita status como parâmetro, e todas
gravam RASCUNHO. Não existe caminho, a partir das tools, que crie conteúdo já
publicado. A metade "publica" está em publicacao.py.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.errors import RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    LETRAS,
    Alternativa,
    Capitulo,
    Classificacao,
    Dificuldade,
    Questao,
    Rascunho,
    Simulado,
    SimuladoQuestao,
    Status,
    TipoRascunho,
    TurmaQuestao,
    Video,
)
from app.services.catalogo import resolver_capitulo, resolver_turma
from app.vimeo.client import VideoVimeo


def _valida_alternativas(alternativas: dict[str, str], gabarito: str) -> tuple[dict[str, str], str]:
    normalizadas = {str(k).strip().upper(): str(v).strip() for k, v in (alternativas or {}).items()}
    faltando = [letra for letra in LETRAS if letra not in normalizadas]
    if faltando:
        raise RegraDeNegocio(
            f"Faltam as alternativas {', '.join(faltando)}. A questão precisa de A a E."
        )
    extras = [letra for letra in normalizadas if letra not in LETRAS]
    if extras:
        raise RegraDeNegocio(f"Alternativas inválidas: {', '.join(extras)}. Use apenas A a E.")

    gab = str(gabarito or "").strip().upper()
    if gab not in LETRAS:
        raise RegraDeNegocio(f"Gabarito '{gabarito}' inválido. Use uma letra de A a E.")
    return normalizadas, gab


def _valida_dificuldade(dificuldade: str | None) -> str:
    if not dificuldade:
        return Dificuldade.MEDIA
    valor = str(dificuldade).strip().upper()
    if valor not in Dificuldade.TODAS:
        raise RegraDeNegocio(
            f"Dificuldade '{dificuldade}' inválida. Use {', '.join(Dificuldade.TODAS)}."
        )
    return valor


def _proximo_numero(db: Session, turma_id: int, capitulo_id: int) -> int:
    atual = db.scalar(
        select(func.max(TurmaQuestao.numero)).where(
            TurmaQuestao.turma_id == turma_id, TurmaQuestao.capitulo_id == capitulo_id
        )
    )
    return (atual or 0) + 1


def _grava_video(db: Session, vimeo: VideoVimeo | None) -> Video | None:
    """Espelha o vídeo do Vimeo localmente, sem duplicar."""
    if vimeo is None:
        return None
    existente = db.scalar(select(Video).where(Video.vimeo_id == vimeo.id))
    if existente:
        return existente
    video = Video(
        vimeo_id=vimeo.id,
        titulo=vimeo.titulo,
        url=vimeo.url or f"https://vimeo.com/{vimeo.id}",
        embed_url=vimeo.embed_url,
        thumbnail_url=vimeo.thumbnail_url,
        duracao_segundos=vimeo.duracao_segundos,
        pasta_vimeo=vimeo.pasta,
    )
    db.add(video)
    db.flush()
    return video


def _cria_questao(
    db: Session,
    ident: Identidade,
    rascunho: Rascunho,
    turma_id: int,
    capitulo_id: int,
    enunciado: str,
    alternativas: dict[str, str] | None,
    gabarito: str | None,
    topico: str | None,
    subtopico: str | None,
    dificuldade: str | None,
    video: Video | None,
    numero: int,
) -> Questao:
    tem_conteudo = bool(alternativas) or bool(gabarito)
    letras, gab = ({}, "A")
    if tem_conteudo:
        letras, gab = _valida_alternativas(alternativas or {}, gabarito or "")

    questao = Questao(
        enunciado=enunciado.strip(),
        gabarito=gab,
        dificuldade=_valida_dificuldade(dificuldade),
        video=video,
        status=Status.RASCUNHO,
        rascunho_id=rascunho.id,
        criado_por_id=ident.usuario_id,
    )
    db.add(questao)
    db.flush()

    for letra, texto in letras.items():
        db.add(Alternativa(questao_id=questao.id, letra=letra, texto=texto))
    if topico:
        db.add(
            Classificacao(questao_id=questao.id, topico=topico.strip(), subtopico=(subtopico or None))
        )

    db.add(
        TurmaQuestao(
            turma_id=turma_id,
            capitulo_id=capitulo_id,
            questao_id=questao.id,
            numero=numero,
            status=Status.RASCUNHO,
            rascunho_id=rascunho.id,
        )
    )
    return questao


# --- operações expostas ------------------------------------------------------


def criar_questao_rascunho(
    db: Session,
    ident: Identidade,
    turma: str | int,
    capitulo: str | int,
    enunciado: str,
    alternativas: dict[str, str],
    gabarito: str,
    topico: str | None = None,
    subtopico: str | None = None,
    dificuldade: str | None = None,
    vimeo_id: str | None = None,
) -> dict:
    ident.exigir_operador()
    if not (enunciado or "").strip():
        raise RegraDeNegocio("Enunciado vazio.")

    alvo_turma = resolver_turma(db, turma)
    alvo_capitulo = resolver_capitulo(db, capitulo)

    rascunho = Rascunho(
        tipo=TipoRascunho.QUESTOES,
        turma_id=alvo_turma.id,
        capitulo_id=alvo_capitulo.id,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()

    video = None
    if vimeo_id:
        video = db.scalar(select(Video).where(Video.vimeo_id == str(vimeo_id)))
        if video is None:
            video = _grava_video(
                db, VideoVimeo(id=str(vimeo_id), titulo=f"Vídeo {vimeo_id}")
            )

    numero = _proximo_numero(db, alvo_turma.id, alvo_capitulo.id)
    _cria_questao(
        db,
        ident,
        rascunho,
        alvo_turma.id,
        alvo_capitulo.id,
        enunciado,
        alternativas,
        gabarito,
        topico,
        subtopico,
        dificuldade,
        video,
        numero,
    )

    rascunho.resumo = f"1 questão para {alvo_turma.nome} / {alvo_capitulo.nome} (Q{numero:02d})"
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def importar_questoes_vimeo(
    db: Session,
    ident: Identidade,
    turma: str | int,
    capitulo: str | int,
    videos: list[dict],
) -> dict:
    """Cria, em rascunho, uma questão por vídeo informado.

    Cada item precisa de `vimeo_id`; enunciado/alternativas/gabarito são
    opcionais. Sem enunciado, usamos o título do vídeo — a POC demonstra
    relacionar vídeo↔questão↔turma, não redigir a questão pelo aluno (redação
    autoral por IA está fora de escopo, seção 24).
    """
    ident.exigir_operador()
    if not videos:
        raise RegraDeNegocio("Nenhum vídeo informado para importar.")

    alvo_turma = resolver_turma(db, turma)
    alvo_capitulo = resolver_capitulo(db, capitulo)

    rascunho = Rascunho(
        tipo=TipoRascunho.QUESTOES,
        turma_id=alvo_turma.id,
        capitulo_id=alvo_capitulo.id,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()

    numero = _proximo_numero(db, alvo_turma.id, alvo_capitulo.id)
    criadas, erros = 0, []
    for item in videos:
        vimeo_id = str(item.get("vimeo_id") or "").strip()
        if not vimeo_id:
            erros.append(f"item sem vimeo_id: {item}")
            continue

        titulo = (item.get("titulo") or f"Vídeo {vimeo_id}").strip()
        video = _grava_video(
            db,
            VideoVimeo(
                id=vimeo_id,
                titulo=titulo,
                url=item.get("url"),
                embed_url=item.get("embed_url"),
                thumbnail_url=item.get("thumbnail_url"),
                duracao_segundos=item.get("duracao_segundos"),
                pasta=item.get("pasta"),
            ),
        )
        try:
            _cria_questao(
                db,
                ident,
                rascunho,
                alvo_turma.id,
                alvo_capitulo.id,
                (item.get("enunciado") or titulo),
                item.get("alternativas"),
                item.get("gabarito"),
                item.get("topico") or alvo_capitulo.nome,
                item.get("subtopico"),
                item.get("dificuldade"),
                video,
                numero,
            )
        except RegraDeNegocio as e:
            erros.append(f"{vimeo_id}: {e}")
            continue
        criadas += 1
        numero += 1

    if criadas == 0:
        db.rollback()
        raise RegraDeNegocio("Nenhuma questão pôde ser criada. " + " | ".join(erros))

    rascunho.resumo = (
        f"{criadas} questão(ões) importadas do Vimeo para "
        f"{alvo_turma.nome} / {alvo_capitulo.nome}"
    )
    db.commit()

    detalhe = detalhar_rascunho(db, ident, rascunho.id)
    detalhe["videos_associados"] = criadas
    detalhe["erros"] = erros
    return detalhe


def criar_simulado_rascunho(
    db: Session,
    ident: Identidade,
    turma: str | int,
    titulo: str,
    questoes: list[int],
    capitulo: str | int | None = None,
) -> dict:
    """Monta um simulado em rascunho a partir de questões já publicadas na turma.

    `questoes` são os NÚMEROS das questões dentro do capítulo (Q01, Q03...) ou
    os ids de questão — é o que o professor diz em voz alta. Como a numeração
    recomeça a cada capítulo, `capitulo` desfaz a ambiguidade ("as questões 1,
    3 e 5 de Estequiometria"). Só entram questões que a turma já enxerga e que
    estão completas.
    """
    ident.exigir_operador()
    if not (titulo or "").strip():
        raise RegraDeNegocio("O simulado precisa de um título.")
    if not questoes:
        raise RegraDeNegocio("Informe ao menos uma questão para o simulado.")

    alvo_turma = resolver_turma(db, turma)
    alvo_capitulo = resolver_capitulo(db, capitulo) if capitulo is not None else None

    rascunho = Rascunho(
        tipo=TipoRascunho.SIMULADO,
        turma_id=alvo_turma.id,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()

    simulado = Simulado(
        titulo=titulo.strip(),
        turma_id=alvo_turma.id,
        status=Status.RASCUNHO,
        rascunho_id=rascunho.id,
        criado_por_id=ident.usuario_id,
    )
    db.add(simulado)
    db.flush()

    for ordem, referencia in enumerate(questoes, start=1):
        questao = _questao_do_simulado(db, alvo_turma.id, referencia, alvo_capitulo)
        db.add(SimuladoQuestao(simulado_id=simulado.id, questao_id=questao.id, ordem=ordem))

    de_onde = f" ({alvo_capitulo.nome})" if alvo_capitulo else ""
    rascunho.resumo = (
        f"Simulado '{simulado.titulo}' com {len(questoes)} questões{de_onde} — {alvo_turma.nome}"
    )
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def _questao_do_simulado(
    db: Session, turma_id: int, referencia: int | str, capitulo: Capitulo | None = None
) -> Questao:
    """Resolve uma questão pelo número dentro do capítulo, ou pelo id."""
    consulta = (
        select(TurmaQuestao)
        .options(
            selectinload(TurmaQuestao.questao).selectinload(Questao.alternativas),
            selectinload(TurmaQuestao.capitulo),
        )
        .where(TurmaQuestao.turma_id == turma_id, TurmaQuestao.status == Status.PUBLICADO)
    )
    if capitulo is not None:
        consulta = consulta.where(TurmaQuestao.capitulo_id == capitulo.id)
    vinculos = db.scalars(consulta).all()

    texto = str(referencia).strip().upper().removeprefix("Q").lstrip("0") or "0"
    candidatos = [v for v in vinculos if str(v.numero) == texto]
    if not candidatos:
        candidatos = [v for v in vinculos if str(v.questao_id) == texto]
    if not candidatos:
        disponiveis = (
            ", ".join(f"{v.capitulo.nome} Q{v.numero:02d}" for v in vinculos)
            or "(nenhuma publicada)"
        )
        onde = f" em {capitulo.nome}" if capitulo else " nesta turma"
        raise RegraDeNegocio(
            f"Questão '{referencia}' não está publicada{onde}. Disponíveis: {disponiveis}."
        )
    if len(candidatos) > 1:
        capitulos = ", ".join(sorted({v.capitulo.nome for v in candidatos}))
        raise RegraDeNegocio(
            f"'{referencia}' existe em mais de um capítulo ({capitulos}). "
            "Informe o capítulo, ou use o id da questão."
        )

    questao = candidatos[0].questao
    if len(questao.alternativas) < len(LETRAS):
        raise RegraDeNegocio(
            f"A questão {referencia} ainda está sem as alternativas A-E e não pode ir para um simulado."
        )
    return questao


# --- leitura de rascunhos ----------------------------------------------------


def listar_rascunhos(db: Session, ident: Identidade, status: str | None = None) -> list[dict]:
    ident.exigir_operador()
    consulta = select(Rascunho).order_by(Rascunho.criado_em.desc())
    if status:
        consulta = consulta.where(Rascunho.status == status.upper())
    return [_resumo_rascunho(db, r) for r in db.scalars(consulta).all()]


def _resumo_rascunho(db: Session, r: Rascunho) -> dict:
    return {
        "rascunho_id": r.id,
        "tipo": r.tipo,
        "status": r.status,
        "resumo": r.resumo,
        "turma": r.turma.nome if r.turma else None,
        "capitulo": r.capitulo.nome if r.capitulo else None,
        "criado_por": r.criado_por.nome,
        "origem": r.origem,
        "criado_em": r.criado_em.isoformat(),
        "aprovado_por": r.aprovado_por.nome if r.aprovado_por else None,
        "aprovado_via": r.aprovado_via,
        "publicado_em": r.publicado_em.isoformat() if r.publicado_em else None,
    }


def detalhar_rascunho(db: Session, ident: Identidade, rascunho_id: int) -> dict:
    from app.errors import NaoEncontrado

    ident.exigir_operador()
    r = db.get(Rascunho, rascunho_id)
    if r is None:
        raise NaoEncontrado(f"Rascunho {rascunho_id} não existe.")

    dados = _resumo_rascunho(db, r)

    vinculos = db.scalars(
        select(TurmaQuestao)
        .options(
            selectinload(TurmaQuestao.questao).selectinload(Questao.alternativas),
            selectinload(TurmaQuestao.questao).selectinload(Questao.video),
            selectinload(TurmaQuestao.capitulo),
        )
        .where(TurmaQuestao.rascunho_id == r.id)
        .order_by(TurmaQuestao.numero)
    ).all()
    dados["questoes"] = [
        {
            "questao_id": v.questao_id,
            "numero": v.numero,
            "capitulo": v.capitulo.nome,
            "enunciado": v.questao.enunciado,
            "alternativas": {a.letra: a.texto for a in v.questao.alternativas},
            "gabarito": v.questao.gabarito if v.questao.alternativas else None,
            "completa": len(v.questao.alternativas) == len(LETRAS),
            "video": (
                {"vimeo_id": v.questao.video.vimeo_id, "titulo": v.questao.video.titulo}
                if v.questao.video
                else None
            ),
        }
        for v in vinculos
    ]

    simulado = db.scalar(
        select(Simulado).options(selectinload(Simulado.questoes)).where(Simulado.rascunho_id == r.id)
    )
    if simulado:
        dados["simulado"] = {
            "simulado_id": simulado.id,
            "titulo": simulado.titulo,
            "turma": simulado.turma.nome,
            "questoes": [
                {
                    "ordem": sq.ordem,
                    "questao_id": sq.questao_id,
                    "enunciado": sq.questao.enunciado,
                }
                for sq in simulado.questoes
            ],
        }

    dados["publicado"] = r.status == Status.PUBLICADO
    dados["aviso"] = (
        "Nada foi publicado. Revise e aprove para que o conteúdo chegue aos alunos."
        if r.status == Status.RASCUNHO
        else "Rascunho já publicado."
    )
    return dados
