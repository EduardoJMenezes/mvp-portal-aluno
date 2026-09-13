"""Manutenção da questão de simulado: detalhar, editar, remover e as figuras.

Editar e remover são diretos, com o preview no chat antes — a mesma pegada do
curso. A trava vem do simulado: depois que abre uma prova com esta questão,
enunciado, alternativas, gabarito e figuras da prova não mudam mais.
Classificação, dificuldade, resolução comentada e vídeo de resolução continuam
editáveis, porque não mexem na prova de ninguém.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import NaoAutorizado, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    LETRAS,
    Alternativa,
    Imagem,
    ParteDaQuestao,
    Questao,
    Simulado,
    SimuladoQuestao,
    Status,
    Tentativa,
)
from app.services import catalogo, taxonomia
from app.services.consultas import remover, selecionar, tocar
from app.services.rascunhos import (
    _classificar,
    _valida_alternativas,
    _valida_dificuldade,
    _video_da_entrada,
)
from app.services.simulados import Situacao, em_brasilia, situacao

# A marca que a transcrição deixa onde a figura vai entrar, e a referência que
# a figura anexada deixa no lugar dela.
PENDENTE = "figura:pendente"
REFERENCIA = re.compile(r"figura:(\d+)")

# ponytail: 2 MB por imagem, dentro do Postgres. A figura recortada de uma
# questão cabe com folga; foto de celular, não — e não deveria.
LIMITE_DA_IMAGEM = 2 * 1024 * 1024

# Quem decide o tipo é a assinatura dos bytes, não o que o cliente declarou.
# SVG fica de fora de propósito: é documento com script, servido pelo nosso
# domínio.
_ASSINATURAS = (
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
)


def tipo_da_imagem(conteudo: bytes) -> str | None:
    if conteudo[:4] == b"RIFF" and conteudo[8:12] == b"WEBP":
        return "image/webp"
    return next((tipo for assinatura, tipo in _ASSINATURAS if conteudo.startswith(assinatura)), None)


def _agora(agora: datetime | None) -> datetime:
    return agora or datetime.now(UTC)


def resolver_questao(db: Session, questao_id: int | str) -> Questao:
    texto = str(questao_id).strip()
    questao = (
        db.scalar(selecionar(Questao).where(Questao.id == int(texto))) if texto.isdigit() else None
    )
    if questao is None:
        raise NaoEncontrado(
            f"Questão {questao_id} não existe no acervo. Use buscar_questoes para achar o id."
        )
    return questao


def _simulados_da_questao(db: Session, questao: Questao) -> list[Simulado]:
    return list(
        db.scalars(
            selecionar(Simulado)
            .join(SimuladoQuestao, SimuladoQuestao.simulado_id == Simulado.id)
            .where(SimuladoQuestao.questao_id == questao.id)
            .order_by(Simulado.id)
        )
    )


def _exigir_prova_fechada_para_mudancas(db: Session, questao: Questao, agora: datetime) -> None:
    ja_abriram = [
        s
        for s in _simulados_da_questao(db, questao)
        if situacao(s, agora) in (Situacao.ABERTO, Situacao.ENCERRADO)
    ]
    if ja_abriram:
        nomes = ", ".join(f"'{s.titulo}' (abriu em {em_brasilia(s.abre_em)})" for s in ja_abriram)
        raise RegraDeNegocio(
            f"A questão {questao.id} está em simulado que já abriu: {nomes}. Enunciado, "
            "alternativas, gabarito e imagem travaram; classificação, dificuldade e vídeo de "
            "resolução ainda mudam."
        )


def detalhar_questao(
    db: Session, ident: Identidade, questao_id: int | str, agora: datetime | None = None
) -> dict:
    """A questão inteira, e em que simulados ela está — o "antes" do preview."""
    ident.exigir_operador()
    agora = _agora(agora)
    q = resolver_questao(db, questao_id)
    return {
        **catalogo.descrever_questao(db, q, incluir_gabarito=True),
        "resolucao_comentada": q.resolucao_comentada,
        "figuras": [
            {"figura_id": f.id, "parte": f.parte}
            for f in db.scalars(select(Imagem).where(Imagem.questao_id == q.id).order_by(Imagem.id))
        ],
        "resolucao": {"vimeo_id": q.video.vimeo_id, "titulo": q.video.titulo} if q.video else None,
        "simulados": [
            {"simulado_id": s.id, "titulo": s.titulo, "situacao": situacao(s, agora)}
            for s in _simulados_da_questao(db, q)
        ],
    }


def editar_questao(
    db: Session,
    ident: Identidade,
    questao_id: int | str,
    enunciado: str | None = None,
    alternativas: dict[str, str] | None = None,
    gabarito: str | None = None,
    dificuldade: str | None = None,
    imagem_pendente: bool | None = None,
    assunto: str | None = None,
    subassunto: str | None = None,
    resolucao: dict | None = None,
    resolucao_comentada: str | None = None,
    agora: datetime | None = None,
) -> dict:
    """Altera a questão direto — o preview é no chat, antes da chamada.

    `alternativas` pode vir parcial ({"C": "..."}): só as letras informadas
    mudam. `assunto` troca a classificação inteira; vazio ("") tira a
    classificação. `resolucao` é o vídeo, como as tools o descrevem (`vimeo_id`
    e o que mais vier); com `vimeo_id` vazio, a questão fica sem resolução.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    q = resolver_questao(db, questao_id)

    if any(v is not None for v in (enunciado, alternativas, gabarito, imagem_pendente)):
        _exigir_prova_fechada_para_mudancas(db, q, agora)

    if enunciado is not None:
        if not enunciado.strip():
            raise RegraDeNegocio("Enunciado vazio.")
        q.enunciado = enunciado.strip()

    if gabarito is not None:
        letra = str(gabarito).strip().upper()
        if letra not in LETRAS:
            raise RegraDeNegocio(f"Gabarito '{gabarito}' inválido. Use uma letra de A a E.")
        q.gabarito = letra

    if alternativas is not None:
        if not isinstance(alternativas, dict):
            raise RegraDeNegocio('Alternativas vão como {"C": "novo texto"}.')
        atuais = {a.letra: a for a in q.alternativas}
        juntas = {letra: a.texto for letra, a in atuais.items()} | {
            str(k).strip().upper(): str(v) for k, v in alternativas.items()
        }
        letras, _ = _valida_alternativas(juntas, q.gabarito)
        for letra, texto in letras.items():
            if letra in atuais:
                atuais[letra].texto = texto
            else:
                q.alternativas.append(Alternativa(letra=letra, texto=texto))

    if dificuldade is not None:
        q.dificuldade = _valida_dificuldade(dificuldade)

    if imagem_pendente is not None:
        q.imagem_pendente = bool(imagem_pendente)

    if assunto is not None:
        q.assuntos.clear()  # vínculo é ligação: trocar a etiqueta não deixa entulho
        db.flush()
        _classificar(db, ident, q, assunto, subassunto, taxonomia.classificar_questao)
    elif subassunto is not None:
        raise RegraDeNegocio("Informe o assunto junto do sub-assunto.")

    if resolucao is not None:
        q.video = _video_da_entrada(db, resolucao) if resolucao.get("vimeo_id") else None

    if resolucao_comentada is not None:
        q.resolucao_comentada = resolucao_comentada.strip() or None

    tocar(ident, q)
    db.commit()
    return detalhar_questao(db, ident, q.id, agora)


def remover_questao(
    db: Session, ident: Identidade, questao_id: int | str, agora: datetime | None = None
) -> dict:
    """Remoção lógica: a questão sai do acervo, e as provas que já a usaram, não.

    Com simulado ainda por acontecer, não remove — a prova ficaria com um
    buraco. Tire a questão da prova antes, em `editar_simulado`.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    q = resolver_questao(db, questao_id)

    pendentes = [
        s for s in _simulados_da_questao(db, q) if situacao(s, agora) != Situacao.ENCERRADO
    ]
    if pendentes:
        nomes = ", ".join(f"'{s.titulo}'" for s in pendentes)
        raise RegraDeNegocio(
            f"A questão {q.id} está em simulado que ainda não terminou: {nomes}. Tire-a da "
            "prova com editar_simulado antes de remover."
        )

    remover(db, ident, q)
    db.commit()
    return {"questao_id": q.id, "enunciado": q.enunciado[:80], "reversivel": True}


def validar_figura(conteudo: bytes) -> str:
    """O tipo da figura, lido dos bytes. Recusa o que não for imagem de verdade."""
    if not conteudo:
        raise RegraDeNegocio("Arquivo vazio.")
    if len(conteudo) > LIMITE_DA_IMAGEM:
        raise RegraDeNegocio(
            f"A imagem tem {len(conteudo) / 1024 / 1024:.1f} MB; o limite é "
            f"{LIMITE_DA_IMAGEM // 1024 // 1024} MB. Recorte só a figura da questão."
        )
    tipo = tipo_da_imagem(conteudo)
    if tipo is None:
        raise RegraDeNegocio("Formato não aceito. Envie PNG, JPEG, WEBP ou GIF.")
    return tipo


def _textos(q: Questao) -> list[str]:
    return [q.enunciado, q.resolucao_comentada or "", *(a.texto for a in q.alternativas)]


def anexar_figura(
    db: Session,
    ident: Identidade,
    questao_id: int | str,
    conteudo: bytes,
    nome: str | None = None,
    parte: str = ParteDaQuestao.ENUNCIADO,
    agora: datetime | None = None,
) -> dict:
    """Anexa uma figura à questão e a põe no texto.

    Onde houver a marca `![](figura:pendente)` — que a transcrição deixa no
    lugar da figura que não deu para transcrever —, a primeira marca da parte
    recebe a figura. Sem marca, ela entra no fim do enunciado ou da resolução.
    Anexar tira a pendência quando não sobra marca nenhuma.

    A figura da prova trava quando o simulado abre; a da resolução, não.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    parte = str(parte or "").strip().upper()
    if parte not in ParteDaQuestao.TODAS:
        raise RegraDeNegocio(f"Parte '{parte}' inválida. Use {', '.join(ParteDaQuestao.TODAS)}.")
    q = resolver_questao(db, questao_id)
    if parte != ParteDaQuestao.RESOLUCAO:
        _exigir_prova_fechada_para_mudancas(db, q, agora)

    figura = Imagem(
        conteudo=conteudo, tipo=validar_figura(conteudo), nome=(nome or "").strip()[:200] or None,
        questao_id=q.id, parte=parte,
    )
    db.add(figura)
    db.flush()
    referencia = f"figura:{figura.id}"

    if parte == ParteDaQuestao.RESOLUCAO:
        atual = q.resolucao_comentada or ""
        q.resolucao_comentada = (
            atual.replace(PENDENTE, referencia, 1) if PENDENTE in atual
            else f"{atual}\n\n![]({referencia})".strip()
        )
    elif parte == ParteDaQuestao.ALTERNATIVA:
        alvo = next((a for a in q.alternativas if PENDENTE in a.texto), None)
        if alvo is None:
            raise RegraDeNegocio(
                "Nenhuma alternativa tem a marca ![](figura:pendente). Ponha a marca na "
                "alternativa certa (editar_questao) antes de anexar."
            )
        alvo.texto = alvo.texto.replace(PENDENTE, referencia, 1)
    else:
        q.enunciado = (
            q.enunciado.replace(PENDENTE, referencia, 1) if PENDENTE in q.enunciado
            else f"{q.enunciado}\n\n![]({referencia})"
        )

    q.imagem_pendente = any(PENDENTE in t for t in _textos(q))
    tocar(ident, q)
    db.commit()
    return {
        "questao_id": q.id,
        "figura_id": figura.id,
        "parte": parte,
        "tipo": figura.tipo,
        "bytes": len(conteudo),
        "imagem_pendente": q.imagem_pendente,
    }


def figura(
    db: Session, ident: Identidade, figura_id: int, agora: datetime | None = None
) -> Imagem:
    """A figura, para quem pode vê-la.

    Operador, sempre. Aluno, só depois de começar uma prova publicada que tenha
    a questão — antes disso, a figura adiantaria a prova. A da resolução, só
    depois que essa prova fechar, como o vídeo de resolução.
    """
    img = db.get(Imagem, figura_id)
    if img is None:
        raise NaoEncontrado(f"A figura {figura_id} não existe.")
    if ident.e_operador:
        return img
    if img.questao_id is None:
        raise NaoAutorizado("Esta figura ainda não faz parte de nenhuma questão.")

    fechamentos = list(
        db.scalars(
            select(Simulado.fecha_em)
            .join(Tentativa, Tentativa.simulado_id == Simulado.id)
            .join(SimuladoQuestao, SimuladoQuestao.simulado_id == Simulado.id)
            .where(
                Tentativa.aluno_id == ident.usuario_id,
                SimuladoQuestao.questao_id == img.questao_id,
                Simulado.status == Status.PUBLICADO,
                Simulado.removido_em.is_(None),
            )
        )
    )
    if not fechamentos:
        raise NaoAutorizado("Esta figura é de uma prova que você não começou.")
    if img.parte == ParteDaQuestao.RESOLUCAO and not any(
        f is not None and f <= _agora(agora) for f in fechamentos
    ):
        raise NaoAutorizado("A resolução aparece quando o simulado fechar.")
    return img
