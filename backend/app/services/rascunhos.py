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
  simulado onde ela entra. O simulado e as questões novas dele nascem num
  rascunho só.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.errors import ErroDominio, RegraDeNegocio
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
from app.services.simulados import em_brasilia, ler_data_hora, pendencias_para_publicar
from app.vimeo.client import VideoVimeo


def _valida_alternativas(alternativas: dict[str, str], gabarito: str) -> tuple[dict[str, str], str]:
    if alternativas is not None and not isinstance(alternativas, dict):
        raise RegraDeNegocio('Alternativas vão como {"A": "...", "B": "...", ... "E": "..."}.')
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


def _video_da_entrada(db: Session, entrada: dict) -> Video:
    """O vídeo descrito como as tools descrevem: `vimeo_id` e o que mais vier."""
    vimeo_id = str(entrada["vimeo_id"]).strip()
    return _grava_video(
        db,
        VideoVimeo(
            id=vimeo_id,
            titulo=(entrada.get("titulo") or f"Vídeo {vimeo_id}").strip(),
            url=entrada.get("url"),
            embed_url=entrada.get("embed_url"),
            thumbnail_url=entrada.get("thumbnail_url"),
            duracao_segundos=entrada.get("duracao_segundos"),
            pasta=entrada.get("pasta"),
        ),
    )


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

        video = _video_da_entrada(db, entrada)

        try:
            estrutura.criar_item(
                db,
                ident,
                alvo_sub,
                video,
                nome=entrada.get("nome") or entrada.get("titulo") or video.titulo,
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


def _nova_questao(
    db: Session,
    ident: Identidade,
    rascunho: Rascunho,
    entrada: dict,
    resolucao: dict | None = None,
) -> Questao:
    """Uma questão nova, em rascunho, do jeito que o Claude transcreveu.

    `entrada` traz enunciado, alternativas (A a E) e gabarito; opcionais:
    assunto, subassunto, dificuldade, imagem_pendente e o vídeo da
    **resolução** — `resolucao` (o vídeo descrito, com `embed_url`) ou só o
    `vimeo_id`. Enunciado e alternativas vão como vieram: Markdown, com fórmula
    em LaTeX. O `resolucao` do parâmetro é o vídeo da pasta do Vimeo que casou
    com a questão pelo número, e só vale quando a entrada não diz outro.
    """
    enunciado = str(entrada.get("enunciado") or "").strip()
    if not enunciado:
        raise RegraDeNegocio("Enunciado vazio.")
    letras, gab = _valida_alternativas(entrada.get("alternativas"), entrada.get("gabarito"))

    resolucao = (
        entrada.get("resolucao")
        or ({"vimeo_id": entrada["vimeo_id"]} if entrada.get("vimeo_id") else None)
        or resolucao
    )
    video = _video_da_entrada(db, resolucao) if resolucao else None

    questao = Questao(
        enunciado=enunciado,
        gabarito=gab,
        dificuldade=_valida_dificuldade(entrada.get("dificuldade")),
        imagem_pendente=bool(entrada.get("imagem_pendente")),
        video=video,
        status=Status.RASCUNHO,
        rascunho_id=rascunho.id,
        criado_por_id=ident.usuario_id,
        alternativas=[Alternativa(letra=letra, texto=texto) for letra, texto in letras.items()],
    )
    db.add(questao)
    db.flush()

    _classificar(
        db, ident, questao, entrada.get("assunto"), entrada.get("subassunto"),
        taxonomia.classificar_questao,
    )
    return questao


def criar_questao_rascunho(
    db: Session,
    ident: Identidade,
    enunciado: str,
    alternativas: dict[str, str],
    gabarito: str,
    assunto: str | None = None,
    subassunto: str | None = None,
    dificuldade: str | None = None,
    resolucao: dict | None = None,
    imagem_pendente: bool = False,
) -> dict:
    """Propõe uma questão para o acervo de simulado.

    Sem turma: questão não pertence a turma nenhuma — quem pertence é o
    simulado onde ela entra. `resolucao`, quando vem, é o vídeo da resolução
    dela (`vimeo_id` e, de preferência, o `embed_url` que o Vimeo devolveu).
    """
    ident.exigir_operador()
    rascunho = Rascunho(
        tipo=TipoRascunho.QUESTOES,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()

    questao = _nova_questao(
        db,
        ident,
        rascunho,
        {
            "enunciado": enunciado,
            "alternativas": alternativas,
            "gabarito": gabarito,
            "assunto": assunto,
            "subassunto": subassunto,
            "dificuldade": dificuldade,
            "resolucao": resolucao,
            "imagem_pendente": imagem_pendente,
        },
    )

    etiqueta = f" — {assunto}" if assunto else ""
    rascunho.resumo = f"1 questão de simulado{etiqueta}: {questao.enunciado[:60]}"
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def criar_simulado_rascunho(
    db: Session,
    ident: Identidade,
    turmas: list[str | int],
    titulo: str,
    questoes: list[int | str | dict],
    abre_em: str | datetime | None = None,
    fecha_em: str | datetime | None = None,
    duracao_minutos: int | None = None,
    resolucoes: dict[int, dict] | None = None,
) -> dict:
    """Monta o simulado **e as questões novas dele** num rascunho só.

    `questoes` vem na ordem da prova, e cada uma é o id de uma questão
    publicada do acervo ou a questão nova inteira (ver `_nova_questao`) — que é
    o que o Claude transcreve do print ou do PDF. Um simulado de 15 questões é
    um preview e um ok, não dezesseis rascunhos.

    `resolucoes` casa pelo número: a questão nova de número N — o `numero` dela
    ou, sem ele, a posição na prova — recebe o vídeo N da pasta do Vimeo.

    Agenda e tempo de prova podem ficar para `editar_simulado`: o rascunho
    nasce sem eles, mas não publica sem eles.
    """
    ident.exigir_operador()
    titulo = (titulo or "").strip()
    if not titulo:
        raise RegraDeNegocio("O simulado precisa de um título.")
    if not questoes:
        raise RegraDeNegocio("Informe ao menos uma questão para o simulado.")
    if isinstance(turmas, (str, int)):
        turmas = [turmas]
    if not turmas:
        raise RegraDeNegocio("Informe ao menos uma turma para o simulado.")
    if duracao_minutos is not None and duracao_minutos <= 0:
        raise RegraDeNegocio("O tempo de prova precisa ser maior que zero.")

    alvos = list({t.id: t for t in (resolver_turma(db, ref) for ref in turmas)}.values())
    simulado = Simulado(
        titulo=titulo,
        turmas=alvos,
        abre_em=ler_data_hora(abre_em),
        fecha_em=ler_data_hora(fecha_em),
        duracao_minutos=duracao_minutos,
        status=Status.RASCUNHO,
        criado_por_id=ident.usuario_id,
    )
    if simulado.abre_em and simulado.fecha_em and simulado.abre_em >= simulado.fecha_em:
        raise RegraDeNegocio("O fechamento precisa vir depois da abertura.")

    rascunho = Rascunho(
        tipo=TipoRascunho.SIMULADO,
        # O rascunho aponta uma turma só; o simulado de várias vive em `turmas`.
        turma_id=alvos[0].id if len(alvos) == 1 else None,
        resumo="",
        origem=ident.canal,
        criado_por_id=ident.usuario_id,
    )
    db.add(rascunho)
    db.flush()
    simulado.rascunho_id = rascunho.id
    db.add(simulado)
    db.flush()

    prova = montar_prova(db, ident, questoes, rascunho, resolucoes=resolucoes)
    simulado.questoes = [
        SimuladoQuestao(questao=q, ordem=ordem) for ordem, q in enumerate(prova, start=1)
    ]
    novas = sum(1 for q in prova if q.rascunho_id == rascunho.id)

    rascunho.resumo = (
        f"Simulado '{titulo}' com {len(questoes)} questões ({novas} novas) — "
        + ", ".join(t.nome for t in alvos)
    )
    db.commit()
    return detalhar_rascunho(db, ident, rascunho.id)


def montar_prova(
    db: Session,
    ident: Identidade,
    entradas: list[int | str | dict],
    rascunho: Rascunho | None,
    atuais: dict[int, Questao] | None = None,
    resolucoes: dict[int, dict] | None = None,
) -> list[Questao]:
    """As questões da prova, na ordem, a partir do que a tool recebeu.

    Cada entrada é o id de uma questão — publicada no acervo, ou uma das
    `atuais` do simulado — ou a questão nova inteira, que nasce no `rascunho`.
    Sem rascunho (simulado já publicado), questão nova não entra: conteúdo novo
    só chega ao aluno por um rascunho aprovado.
    """
    atuais = atuais or {}
    prova: list[Questao] = []
    for ordem, entrada in enumerate(entradas, start=1):
        try:
            if isinstance(entrada, dict) and "enunciado" in entrada:
                if rascunho is None:
                    raise RegraDeNegocio(
                        "questão nova só entra em simulado ainda em rascunho. Cadastre com "
                        "criar_questao_rascunho, publique e use o id dela."
                    )
                numero = entrada.get("numero") or ordem
                if not str(numero).strip().isdigit():
                    raise RegraDeNegocio(f"número '{numero}' inválido; use o da prova, ex.: 12.")
                questao = _nova_questao(
                    db, ident, rascunho, entrada, (resolucoes or {}).get(int(numero))
                )
            else:
                referencia = entrada.get("questao_id") if isinstance(entrada, dict) else entrada
                texto = str(referencia).strip()
                questao = (atuais.get(int(texto)) if texto.isdigit() else None) or (
                    _questao_do_simulado(db, referencia)
                )
            if any(q.id == questao.id for q in prova):
                raise RegraDeNegocio(f"a questão {questao.id} já entrou antes nesta prova.")
        except ErroDominio as e:
            # Numa prova de 15, o erro sem a posição não diz qual corrigir.
            raise type(e)(f"Questão {ordem} da prova: {e}") from None
        prova.append(questao)
    return prova


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
            "dificuldade": q.dificuldade,
            "classificacao": taxonomia.assuntos_da_questao(q),
            "imagem_pendente": q.imagem_pendente,
            "video": (
                {"vimeo_id": q.video.vimeo_id, "titulo": q.video.titulo} if q.video else None
            ),
        }
        for q in questoes
    ]

    simulado = db.scalar(selecionar(Simulado).where(Simulado.rascunho_id == r.id))
    if simulado:
        dados["simulado"] = {
            "simulado_id": simulado.id,
            "titulo": simulado.titulo,
            "turmas": [t.nome for t in simulado.turmas],
            "abre_em": em_brasilia(simulado.abre_em),
            "fecha_em": em_brasilia(simulado.fecha_em),
            "duracao_minutos": simulado.duracao_minutos,
            "questoes": [
                {
                    "ordem": sq.ordem,
                    "questao_id": sq.questao_id,
                    "nova": sq.questao.rascunho_id == r.id,
                    "enunciado": sq.questao.enunciado,
                    "gabarito": sq.questao.gabarito,
                    "imagem_pendente": sq.questao.imagem_pendente,
                    "resolucao": sq.questao.video.titulo if sq.questao.video else None,
                }
                for sq in simulado.questoes
            ],
            # O que ainda barra a publicação — agenda, imagem, alternativas.
            "pendencias_para_publicar": pendencias_para_publicar(simulado),
        }

    dados["publicado"] = r.status == Status.PUBLICADO
    dados["aviso"] = (
        "Nada foi publicado. Revise e aprove para que o conteúdo chegue aos alunos."
        if r.status == Status.RASCUNHO
        else "Rascunho já publicado."
    )
    return dados
