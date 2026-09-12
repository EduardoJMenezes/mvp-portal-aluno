"""Criação de rascunhos.

Regra fundamental da seção 6: a IA propõe, o humano aprova, o backend publica.
Aqui está a metade "propõe" — e ela é estrutural, não uma recomendação de
prompt: nenhuma função deste módulo aceita status como parâmetro, e todas
gravam RASCUNHO. Não existe caminho, a partir das tools, que crie conteúdo já
publicado. A metade "publica" está em publicacao.py.

Duas famílias de proposta, que não se misturam mais:

* **itens** — vídeos entrando num sub-módulo do curso. É o que a importação do
  Vimeo produz, e o que o aluno acaba vendo na árvore do módulo;
* **questões e simulados** — o instrumento de avaliação, com enunciado,
  alternativas e gabarito. Questão não pertence a turma: quem pertence é o
  simulado onde ela entra.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.errors import RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    LETRAS,
    Alternativa,
    Dificuldade,
    Item,
    Questao,
    Rascunho,
    Simulado,
    SimuladoQuestao,
    Status,
    TipoRascunho,
    Video,
)
from app.services import estrutura, taxonomia
from app.services.catalogo import resolver_turma
from app.services.consultas import selecionar
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


def _grava_video(db: Session, vimeo: VideoVimeo | None) -> Video | None:
    """Espelha o vídeo do Vimeo localmente, sem duplicar.

    Um vídeo removido logicamente e reimportado volta à vida em vez de virar
    uma segunda linha: `vimeo_id` é identidade externa, não nome editável.
    """
    if vimeo is None:
        return None

    existente = db.scalar(select(Video).where(Video.vimeo_id == vimeo.id))
    if existente:
        existente.removido_em = None
        if vimeo.embed_url:
            existente.embed_url = vimeo.embed_url
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


def _classificar(db: Session, ident: Identidade, alvo, assunto: str | None, subassunto: str | None,
                 classificador) -> None:
    """Etiqueta o alvo, quando o professor disse do que aquilo trata.

    Assunto inexistente é erro, não criação silenciosa: um typo viraria um
    assunto novo e a taxonomia apodreceria sozinha. Criar é explícito, por
    `criar_assunto`.
    """
    if not assunto:
        return
    alvo_assunto = taxonomia.resolver_assunto(db, assunto)
    alvo_sub = (
        taxonomia.resolver_subassunto(db, alvo_assunto, subassunto) if subassunto else None
    )
    classificador(db, ident, alvo, alvo_assunto, alvo_sub)


# --- itens do curso ----------------------------------------------------------


def importar_videos_como_itens(
    db: Session,
    ident: Identidade,
    turma: str | int,
    modulo: str | int,
    submodulo: str | int,
    videos: list[dict],
) -> dict:
    """Cria, em rascunho, um item por vídeo informado.

    Cada item precisa de `vimeo_id`. `nome` é opcional: sem ele vale o título
    do vídeo, que é o que o professor reconhece ("Q04 — Estequiometria com
    pureza"). `assunto`/`subassunto`, também opcionais, etiquetam o **vídeo** —
    não o item —, porque a etiqueta é do conteúdo e atravessa turmas e anos.

    Módulo e sub-módulo precisam existir: criá-los no meio de uma importação
    esconderia do professor a decisão de como o curso está organizado.
    """
    ident.exigir_operador()
    if not videos:
        raise RegraDeNegocio("Nenhum vídeo informado para importar.")

    alvo_turma = resolver_turma(db, turma)
    alvo_modulo = estrutura.resolver_modulo(db, alvo_turma, modulo)
    alvo_sub = estrutura.resolver_submodulo(db, alvo_modulo, submodulo)

    rascunho = Rascunho(
        tipo=TipoRascunho.ITENS,
        turma_id=alvo_turma.id,
        submodulo_id=alvo_sub.id,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()

    criados, erros = 0, []
    for entrada in videos:
        vimeo_id = str(entrada.get("vimeo_id") or "").strip()
        if not vimeo_id:
            erros.append(f"item sem vimeo_id: {entrada}")
            continue

        titulo = (entrada.get("titulo") or f"Vídeo {vimeo_id}").strip()
        video = _grava_video(
            db,
            VideoVimeo(
                id=vimeo_id,
                titulo=titulo,
                url=entrada.get("url"),
                embed_url=entrada.get("embed_url"),
                thumbnail_url=entrada.get("thumbnail_url"),
                duracao_segundos=entrada.get("duracao_segundos"),
                pasta=entrada.get("pasta"),
            ),
        )

        try:
            estrutura.criar_item(
                db,
                ident,
                alvo_sub,
                video,
                nome=entrada.get("nome") or titulo,
                status=Status.RASCUNHO,
                rascunho_id=rascunho.id,
            )
            _classificar(
                db,
                ident,
                video,
                entrada.get("assunto"),
                entrada.get("subassunto"),
                taxonomia.classificar_video,
            )
        except RegraDeNegocio as e:
            erros.append(f"{vimeo_id}: {e}")
            continue
        criados += 1

    if criados == 0:
        db.rollback()
        raise RegraDeNegocio("Nenhum item pôde ser criado. " + " | ".join(erros))

    rascunho.resumo = (
        f"{criados} vídeo(s) para {alvo_turma.nome} / {alvo_modulo.nome} › {alvo_sub.nome}"
    )
    db.commit()

    detalhe = detalhar_rascunho(db, ident, rascunho.id)
    detalhe["erros"] = erros
    return detalhe


# --- questões e simulados ----------------------------------------------------


def criar_questao_rascunho(
    db: Session,
    ident: Identidade,
    enunciado: str,
    alternativas: dict[str, str],
    gabarito: str,
    assunto: str | None = None,
    subassunto: str | None = None,
    dificuldade: str | None = None,
    vimeo_id: str | None = None,
) -> dict:
    """Propõe uma questão para o acervo de simulado.

    Sem turma: questão não pertence a turma nenhuma — quem pertence é o
    simulado onde ela entra. `vimeo_id`, quando vem, é o vídeo da **resolução**
    dela.
    """
    ident.exigir_operador()
    if not (enunciado or "").strip():
        raise RegraDeNegocio("Enunciado vazio.")

    letras, gab = _valida_alternativas(alternativas, gabarito)

    rascunho = Rascunho(
        tipo=TipoRascunho.QUESTOES,
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
            video = _grava_video(db, VideoVimeo(id=str(vimeo_id), titulo=f"Vídeo {vimeo_id}"))

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

    _classificar(db, ident, questao, assunto, subassunto, taxonomia.classificar_questao)

    etiqueta = f" — {assunto}" if assunto else ""
    rascunho.resumo = f"1 questão de simulado{etiqueta}: {questao.enunciado[:60]}"
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def criar_simulado_rascunho(
    db: Session,
    ident: Identidade,
    turma: str | int,
    titulo: str,
    questoes: list[int],
) -> dict:
    """Monta um simulado em rascunho a partir de questões já publicadas.

    `questoes` são ids do acervo. Só entram questões publicadas e completas —
    uma prova com questão pela metade não é uma prova.
    """
    ident.exigir_operador()
    if not (titulo or "").strip():
        raise RegraDeNegocio("O simulado precisa de um título.")
    if not questoes:
        raise RegraDeNegocio("Informe ao menos uma questão para o simulado.")

    alvo_turma = resolver_turma(db, turma)

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
        questao = _questao_do_simulado(db, referencia)
        db.add(SimuladoQuestao(simulado_id=simulado.id, questao_id=questao.id, ordem=ordem))

    rascunho.resumo = (
        f"Simulado '{simulado.titulo}' com {len(questoes)} questões — {alvo_turma.nome}"
    )
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def _questao_do_simulado(db: Session, referencia: int | str) -> Questao:
    """Resolve uma questão do acervo pelo id, exigindo que esteja pronta."""
    disponiveis = list(
        db.scalars(
            selecionar(Questao)
            .options(selectinload(Questao.alternativas))
            .where(Questao.status == Status.PUBLICADO)
        )
    )

    texto = str(referencia).strip()
    candidatos = [q for q in disponiveis if str(q.id) == texto]
    if not candidatos:
        lista = ", ".join(f"{q.id} ({q.enunciado[:30]}…)" for q in disponiveis) or "(nenhuma)"
        raise RegraDeNegocio(
            f"Questão '{referencia}' não está no acervo publicado. Disponíveis: {lista}."
        )

    questao = candidatos[0]
    if len(questao.alternativas) < len(LETRAS):
        raise RegraDeNegocio(
            f"A questão {referencia} ainda está sem as alternativas A-E e não pode ir "
            "para um simulado."
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
    sub = r.submodulo
    return {
        "rascunho_id": r.id,
        "tipo": r.tipo,
        "status": r.status,
        "resumo": r.resumo,
        "turma": r.turma.nome if r.turma else None,
        "modulo": sub.modulo.nome if sub else None,
        "submodulo": sub.nome if sub else None,
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

    itens = list(
        db.scalars(
            selecionar(Item)
            .options(selectinload(Item.video))
            .where(Item.rascunho_id == r.id)
            .order_by(Item.ordem)
        )
    )
    dados["itens"] = [
        {
            "item_id": i.id,
            "nome": i.nome,
            "ordem": i.ordem,
            "status": i.status,
            "video": {"vimeo_id": i.video.vimeo_id, "titulo": i.video.titulo},
            "assuntos": taxonomia.assuntos_do_video(db, i.video_id),
        }
        for i in itens
    ]

    questoes = list(
        db.scalars(
            selecionar(Questao)
            .options(selectinload(Questao.alternativas), selectinload(Questao.video))
            .where(Questao.rascunho_id == r.id)
        )
    )
    dados["questoes"] = [
        {
            "questao_id": q.id,
            "enunciado": q.enunciado,
            "alternativas": {a.letra: a.texto for a in q.alternativas},
            "gabarito": q.gabarito if q.alternativas else None,
            "completa": len(q.alternativas) == len(LETRAS),
            "video": (
                {"vimeo_id": q.video.vimeo_id, "titulo": q.video.titulo} if q.video else None
            ),
        }
        for q in questoes
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
