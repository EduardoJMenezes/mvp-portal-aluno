"""Simulados: agenda, prova, entrega, resultado e ranking.

As regras vêm de docs/MODELO-SIMULADO.md:

* **Uma janela só** (`abre_em` → `fecha_em`) e um **tempo de prova** contado de
  quando o aluno começa. O prazo dele é o que vier primeiro.
* **Estourou o prazo, entrega automática** com o que foi respondido; o resto
  fica em branco. Não há job: a tentativa vencida é consolidada na próxima
  consulta (`consolidar`).
* **O resultado só sai quando o simulado fecha.** O backend recusa antes — não
  é a tela que esconde.
* **Um ranking só**, entre os participantes de todas as turmas. Empate divide a
  posição (1º, 2º, 2º, 4º); quem não começou fica fora.
* **Depois que abre, questões e gabarito travam.** Só o fechamento pode ser
  estendido.

O relógio entra como parâmetro (`agora`) para as regras de tempo serem
testáveis sem mexer no relógio do sistema.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoAutorizado, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    LETRAS,
    Matricula,
    Rascunho,
    Resposta,
    Simulado,
    SimuladoQuestao,
    Status,
    Tentativa,
    Turma,
)
from app.services import acesso
from app.services.catalogo import exigir_acesso_a_turma, ids_das_turmas_do_aluno, resolver_turma
from app.services.consultas import remover, selecionar, tocar

# ponytail: offset fixo. O Brasil não tem horário de verão desde 2019; se voltar,
# troque por ZoneInfo("America/Sao_Paulo") — e instale `tzdata`, que o Windows
# não traz.
BRASILIA = timezone(timedelta(hours=-3), "Brasília")


class Situacao:
    RASCUNHO = "RASCUNHO"
    AGENDADO = "AGENDADO"
    ABERTO = "ABERTO"
    ENCERRADO = "ENCERRADO"


class EstadoDaProva:
    """Onde o aluno está em relação à prova — é o que a tela dele decide."""

    AGENDADO = "AGENDADO"
    EM_ANDAMENTO = "EM_ANDAMENTO"
    ENTREGUE = "ENTREGUE"
    ENCERRADO = "ENCERRADO"


# --- tempo -------------------------------------------------------------------


def _agora(agora: datetime | None) -> datetime:
    return agora or datetime.now(UTC)


def ler_data_hora(valor: str | datetime | None) -> datetime | None:
    """'2026-10-10T14:00', no horário de Brasília, para datetime em UTC.

    Sem fuso explícito, vale Brasília: é o que o professor digita e o que o
    Claude repete. Com fuso (`-03:00`, `Z`), vale o fuso informado.
    """
    if valor in (None, ""):
        return None
    if isinstance(valor, datetime):
        instante = valor
    else:
        try:
            instante = datetime.fromisoformat(str(valor).strip())
        except ValueError:
            raise RegraDeNegocio(
                f"Data e hora '{valor}' inválida. Use o formato 2026-10-10T14:00, "
                "no horário de Brasília."
            ) from None
    if instante.tzinfo is None:
        instante = instante.replace(tzinfo=BRASILIA)
    return instante.astimezone(UTC)


def em_brasilia(instante: datetime | None) -> str | None:
    """Para mensagem: '10/10/2026 às 14:00'."""
    return instante.astimezone(BRASILIA).strftime("%d/%m/%Y às %H:%M") if instante else None


def _iso(instante: datetime | None) -> str | None:
    """Para dado: ISO com o fuso de Brasília, que o front e o modelo leem igual."""
    return instante.astimezone(BRASILIA).isoformat(timespec="minutes") if instante else None


def situacao(simulado: Simulado, agora: datetime | None = None) -> str:
    agora = _agora(agora)
    if simulado.status != Status.PUBLICADO:
        return Situacao.RASCUNHO
    if simulado.abre_em is not None and agora < simulado.abre_em:
        return Situacao.AGENDADO
    if simulado.fecha_em is not None and agora >= simulado.fecha_em:
        return Situacao.ENCERRADO
    return Situacao.ABERTO


# --- carregar e enxergar -----------------------------------------------------


def resolver_simulado(db: Session, referencia: str | int) -> Simulado:
    todos = list(db.scalars(selecionar(Simulado).order_by(Simulado.criado_em.desc())))

    if isinstance(referencia, int) or str(referencia).strip().isdigit():
        alvo = [s for s in todos if s.id == int(referencia)]
        if alvo:
            return alvo[0]

    texto = str(referencia).strip().lower()
    exatos = [s for s in todos if s.titulo.lower() == texto]
    if exatos:
        return exatos[0]
    parciais = [s for s in todos if texto in s.titulo.lower()]
    if len(parciais) == 1:
        return parciais[0]
    if len(parciais) > 1:
        nomes = ", ".join(f"#{s.id} {s.titulo}" for s in parciais)
        raise NaoEncontrado(f"'{referencia}' corresponde a mais de um simulado: {nomes}.")

    disponiveis = ", ".join(f"#{s.id} {s.titulo}" for s in todos) or "(nenhum)"
    raise NaoEncontrado(f"Simulado '{referencia}' não existe. Simulados: {disponiveis}.")


def _carregar(db: Session, simulado_id: int | str) -> Simulado:
    return resolver_simulado(db, simulado_id)


def exigir_simulado_visivel(db: Session, ident: Identidade, simulado: Simulado) -> None:
    """Operador vê todos; aluno, só o publicado de alguma turma dele (seção 11)."""
    if ident.e_operador:
        return
    if simulado.status != Status.PUBLICADO:
        raise NaoAutorizado("Este simulado ainda não foi publicado.")
    turmas_do_aluno = set(ids_das_turmas_do_aluno(db, ident.usuario_id))
    if not {t.id for t in simulado.turmas} & turmas_do_aluno:
        raise NaoAutorizado(f"{ident.nome} não está em nenhuma turma deste simulado.")


def exigir_editavel(simulado: Simulado, agora: datetime | None = None) -> None:
    """Questões, gabarito, turmas e tempo de prova travam quando o simulado abre."""
    agora = _agora(agora)
    if situacao(simulado, agora) in (Situacao.ABERTO, Situacao.ENCERRADO):
        raise RegraDeNegocio(
            f"'{simulado.titulo}' já abriu em {em_brasilia(simulado.abre_em)}: questões, "
            "gabarito, turmas e tempo de prova travaram. Só dá para estender o fechamento."
        )


def _tentativa(db: Session, simulado: Simulado, aluno_id: int) -> Tentativa | None:
    return db.scalar(
        select(Tentativa)
        .options(selectinload(Tentativa.respostas))
        .where(Tentativa.simulado_id == simulado.id, Tentativa.aluno_id == aluno_id)
    )


def consolidar(tentativa: Tentativa, agora: datetime) -> bool:
    """Entrega automática da tentativa vencida. Devolve se ela está entregue.

    Quem chama faz o commit. Não há job agendado de propósito: a tentativa só
    importa quando alguém a consulta, e é aí que o prazo é conferido.
    """
    if tentativa.finalizado_em is not None:
        return True
    if tentativa.prazo_em is not None and agora >= tentativa.prazo_em:
        tentativa.finalizado_em = tentativa.prazo_em
        tentativa.entregue_automaticamente = True
        return True
    return False


def _resumo(simulado: Simulado, agora: datetime) -> dict:
    return {
        "simulado_id": simulado.id,
        "titulo": simulado.titulo,
        "status": simulado.status,
        "situacao": situacao(simulado, agora),
        "turmas": [t.nome for t in simulado.turmas],
        "abre_em": _iso(simulado.abre_em),
        "fecha_em": _iso(simulado.fecha_em),
        "duracao_minutos": simulado.duracao_minutos,
        "total_questoes": len(simulado.questoes),
    }


def _questao_da_prova(vinculo: SimuladoQuestao, com_gabarito: bool) -> dict:
    q = vinculo.questao
    dados = {
        "ordem": vinculo.ordem,
        "questao_id": q.id,
        "enunciado": q.enunciado,
        "alternativas": {a.letra: a.texto for a in q.alternativas},
        "tem_imagem": q.imagem_id is not None,
    }
    if com_gabarito:
        dados["gabarito"] = q.gabarito
        dados["imagem_pendente"] = q.imagem_pendente
    return dados


# --- listar e abrir ----------------------------------------------------------


def listar_simulados(
    db: Session,
    ident: Identidade,
    turma: str | int | None = None,
    agora: datetime | None = None,
) -> list[dict]:
    agora = _agora(agora)
    consulta = selecionar(Simulado).order_by(
        Simulado.abre_em.desc().nulls_last(), Simulado.criado_em.desc()
    )

    if turma is not None:
        alvo = resolver_turma(db, turma)
        exigir_acesso_a_turma(db, ident, alvo)
        consulta = consulta.where(Simulado.turmas.any(Turma.id == alvo.id))
    elif ident.e_aluno:
        permitidas = ids_das_turmas_do_aluno(db, ident.usuario_id) or [-1]
        consulta = consulta.where(Simulado.turmas.any(Turma.id.in_(permitidas)))

    if ident.e_aluno:
        consulta = consulta.where(Simulado.status == Status.PUBLICADO)

    saida = []
    for s in db.scalars(consulta):
        dados = _resumo(s, agora)
        if ident.e_aluno:
            t = _tentativa(db, s, ident.usuario_id)
            entregue = bool(t) and consolidar(t, agora)
            dados["minha_prova"] = {
                "iniciada": t is not None,
                "entregue": entregue,
                "prazo_em": _iso(t.prazo_em) if t else None,
            }
            dados["resultado_disponivel"] = dados["situacao"] == Situacao.ENCERRADO and t is not None
        else:
            dados["tentativas"] = int(
                db.scalar(select(func.count(Tentativa.id)).where(Tentativa.simulado_id == s.id))
                or 0
            )
        saida.append(dados)

    db.commit()  # entregas automáticas que a listagem consolidou
    return saida


def abrir_simulado(
    db: Session, ident: Identidade, simulado_id: int | str, agora: datetime | None = None
) -> dict:
    """A prova do ponto de vista de quem abre.

    Operador vê as questões com gabarito, a qualquer momento. Aluno recebe o
    estado dele — agendado, em andamento, entregue ou encerrado — e só vê as
    questões em andamento, sem gabarito. Abrir pela primeira vez é começar: é
    aqui que o prazo nasce.
    """
    agora = _agora(agora)
    s = _carregar(db, simulado_id)
    exigir_simulado_visivel(db, ident, s)
    base = _resumo(s, agora)

    if ident.e_operador:
        return {
            **base,
            "questoes": [
                {
                    **_questao_da_prova(sq, com_gabarito=True),
                    "resolucao": sq.questao.video.titulo if sq.questao.video else None,
                }
                for sq in s.questoes
            ],
            "pendencias_para_publicar": (
                pendencias_para_publicar(s, agora) if base["situacao"] == Situacao.RASCUNHO else []
            ),
        }

    sit = base["situacao"]
    if sit == Situacao.AGENDADO:
        return {**base, "estado": EstadoDaProva.AGENDADO}

    t = _tentativa(db, s, ident.usuario_id)
    if sit == Situacao.ENCERRADO:
        if t is not None:
            consolidar(t, agora)
            db.commit()
        return {**base, "estado": EstadoDaProva.ENCERRADO, "resultado_disponivel": t is not None}

    if t is None:
        t = Tentativa(
            simulado_id=s.id,
            aluno_id=ident.usuario_id,
            iniciado_em=agora,
            prazo_em=min(agora + timedelta(minutes=s.duracao_minutos or 0), s.fecha_em),
        )
        db.add(t)
        db.flush()

    entregue = consolidar(t, agora)
    db.commit()

    if entregue:
        return {
            **base,
            "estado": EstadoDaProva.ENTREGUE,
            "entregue_automaticamente": t.entregue_automaticamente,
            "resultado_em": _iso(s.fecha_em),
        }

    marcadas = {r.questao_id: r.alternativa_marcada for r in t.respostas}
    return {
        **base,
        "estado": EstadoDaProva.EM_ANDAMENTO,
        "prazo_em": _iso(t.prazo_em),
        "segundos_restantes": max(0, int((t.prazo_em - agora).total_seconds())),
        "questoes": [
            {**_questao_da_prova(sq, com_gabarito=False), "marcada": marcadas.get(sq.questao_id)}
            for sq in s.questoes
        ],
    }


# --- responder e entregar ----------------------------------------------------


def responder(
    db: Session,
    ident: Identidade,
    simulado_id: int | str,
    questao_id: int,
    alternativa: str,
    agora: datetime | None = None,
) -> dict:
    """Grava a resposta do aluno. A correção acontece aqui, e não é revelada."""
    agora = _agora(agora)
    if not ident.e_aluno:
        raise NaoAutorizado("Somente alunos respondem simulados.")

    letra = str(alternativa or "").strip().upper()
    if letra not in LETRAS:
        raise RegraDeNegocio(f"Alternativa '{alternativa}' inválida. Use A a E.")

    s = _carregar(db, simulado_id)
    exigir_simulado_visivel(db, ident, s)
    if situacao(s, agora) != Situacao.ABERTO:
        raise RegraDeNegocio(f"'{s.titulo}' não está aberto para respostas.")

    vinculo = next((sq for sq in s.questoes if sq.questao_id == questao_id), None)
    if vinculo is None:
        raise RegraDeNegocio(f"A questão {questao_id} não faz parte deste simulado.")

    t = _tentativa(db, s, ident.usuario_id)
    if t is None:
        raise RegraDeNegocio("Abra o simulado antes de responder: é ao abrir que o tempo começa.")
    if consolidar(t, agora):
        db.commit()
        raise RegraDeNegocio(
            "O tempo acabou: a prova foi entregue com o que estava respondido."
            if t.entregue_automaticamente
            else "Esta prova já foi entregue."
        )

    correta = letra == vinculo.questao.gabarito
    existente = next((r for r in t.respostas if r.questao_id == questao_id), None)
    if existente:
        existente.alternativa_marcada = letra
        existente.correta = correta
        existente.respondido_em = agora
    else:
        t.respostas.append(
            Resposta(questao_id=questao_id, alternativa_marcada=letra, correta=correta, respondido_em=agora)
        )
    db.commit()

    # Sem `correta`: o aluno só vê o resultado quando o simulado fechar.
    return {
        "registrado": True,
        "respondidas": len(t.respostas),
        "total": len(s.questoes),
        "segundos_restantes": max(0, int((t.prazo_em - agora).total_seconds())),
    }


def entregar(
    db: Session, ident: Identidade, simulado_id: int | str, agora: datetime | None = None
) -> dict:
    """O aluno entrega. O recibo diz quando o resultado sai — não o resultado."""
    agora = _agora(agora)
    if not ident.e_aluno:
        raise NaoAutorizado("Somente alunos entregam uma prova.")

    s = _carregar(db, simulado_id)
    exigir_simulado_visivel(db, ident, s)

    t = _tentativa(db, s, ident.usuario_id)
    if t is None:
        raise RegraDeNegocio("Você ainda não começou este simulado.")
    if not consolidar(t, agora):
        t.finalizado_em = agora
    db.commit()

    return {
        "simulado_id": s.id,
        "titulo": s.titulo,
        "entregue": True,
        "entregue_automaticamente": t.entregue_automaticamente,
        "respondidas": len(t.respostas),
        "total": len(s.questoes),
        "resultado_em": _iso(s.fecha_em),
        "mensagem": f"Prova entregue. O resultado sai em {em_brasilia(s.fecha_em)}.",
    }


# --- ranking e resultado -----------------------------------------------------


def _classificacao(db: Session, s: Simulado, agora: datetime) -> list[dict]:
    """Participantes em ordem, com a posição de cada um. Quem chama faz o commit."""
    tentativas = db.scalars(
        select(Tentativa)
        .options(selectinload(Tentativa.respostas), selectinload(Tentativa.aluno))
        .where(Tentativa.simulado_id == s.id)
    ).all()

    total = len(s.questoes)
    linhas = []
    for t in tentativas:
        consolidar(t, agora)
        linhas.append(
            {
                "tentativa": t,
                "aluno_id": t.aluno_id,
                "aluno": t.aluno.nome,
                "acertos": sum(1 for r in t.respostas if r.correta),
                "total": total,
            }
        )

    linhas.sort(key=lambda linha: (-linha["acertos"], linha["aluno"]))
    posicao = 0
    for indice, linha in enumerate(linhas):
        # Empate divide a posição, e a seguinte pula: 1º, 2º, 2º, 4º.
        if indice == 0 or linha["acertos"] != linhas[indice - 1]["acertos"]:
            posicao = indice + 1
        linha["posicao"] = posicao
    return linhas


def _percentual(acertos: int, total: int) -> float:
    return round(100 * acertos / total, 1) if total else 0.0


def ranking(
    db: Session, ident: Identidade, simulado_id: int | str, agora: datetime | None = None
) -> dict:
    """O ranking completo, que é só do professor.

    Antes de fechar ele sai marcado como parcial: a posição ainda muda.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    s = _carregar(db, simulado_id)
    linhas = _classificacao(db, s, agora)
    db.commit()

    turmas_do_simulado = {t.id: t.nome for t in s.turmas}
    turmas_por_aluno: dict[int, list[str]] = {}
    for usuario_id, turma_id in db.execute(
        select(Matricula.usuario_id, Matricula.turma_id).where(
            Matricula.usuario_id.in_([linha["aluno_id"] for linha in linhas] or [-1]),
            Matricula.turma_id.in_(list(turmas_do_simulado) or [-1]),
        )
    ):
        turmas_por_aluno.setdefault(usuario_id, []).append(turmas_do_simulado[turma_id])

    return {
        "simulado_id": s.id,
        "titulo": s.titulo,
        "situacao": situacao(s, agora),
        "parcial": situacao(s, agora) != Situacao.ENCERRADO,
        "participantes": len(linhas),
        "ranking": [
            {
                "posicao": linha["posicao"],
                "aluno": linha["aluno"],
                "turmas": turmas_por_aluno.get(linha["aluno_id"], []),
                "acertos": linha["acertos"],
                "total": linha["total"],
                "percentual": _percentual(linha["acertos"], linha["total"]),
                "entregue_automaticamente": linha["tentativa"].entregue_automaticamente,
            }
            for linha in linhas
        ],
    }


def resultado(
    db: Session, ident: Identidade, simulado_id: int | str, agora: datetime | None = None
) -> dict:
    """O resultado individual do aluno: nota, posição, gabarito, resolução e análise.

    Só depois do fechamento. O ranking completo não sai daqui — o aluno vê a
    própria posição e quantos participaram, e só.
    """
    from app.services.analytics import recomendar_videos

    agora = _agora(agora)
    if not ident.e_aluno:
        raise RegraDeNegocio(
            "Resultado individual é a visão do aluno. Para a turma, use o ranking e as estatísticas."
        )

    s = _carregar(db, simulado_id)
    exigir_simulado_visivel(db, ident, s)
    if situacao(s, agora) != Situacao.ENCERRADO:
        raise RegraDeNegocio(
            f"O resultado de '{s.titulo}' sai em {em_brasilia(s.fecha_em)}, quando o simulado fechar."
        )

    linhas = _classificacao(db, s, agora)
    db.commit()
    minha = next((linha for linha in linhas if linha["aluno_id"] == ident.usuario_id), None)
    if minha is None:
        raise RegraDeNegocio(f"Você não fez '{s.titulo}'.")

    t = minha["tentativa"]
    respostas = {r.questao_id: r for r in t.respostas}
    ids_resolucao = {sq.questao.video_id for sq in s.questoes if sq.questao.video_id}
    liberados = acesso.videos_liberados(db, ident, ids_resolucao, agora=agora)
    videos = {sq.questao.video_id: sq.questao.video for sq in s.questoes if sq.questao.video_id}

    questoes = []
    for sq in s.questoes:
        q, r = sq.questao, respostas.get(sq.questao_id)
        questoes.append(
            {
                **_questao_da_prova(sq, com_gabarito=False),
                "marcada": r.alternativa_marcada if r else None,
                "em_branco": r is None,
                "gabarito": q.gabarito,
                "correta": bool(r and r.correta),
                "resolucao": acesso.descrever_video(videos.get(q.video_id), q.video_id in liberados)
                if q.video_id
                else None,
            }
        )

    return {
        "simulado_id": s.id,
        "titulo": s.titulo,
        "acertos": minha["acertos"],
        "total": minha["total"],
        "percentual": _percentual(minha["acertos"], minha["total"]),
        "em_branco": sum(1 for q in questoes if q["em_branco"]),
        "entregue_automaticamente": t.entregue_automaticamente,
        "posicao": minha["posicao"],
        "participantes": len(linhas),
        "questoes": questoes,
        # Os sub-assuntos em que foi pior e os vídeos que explicam cada um.
        "analise": recomendar_videos(
            db, ident, [q["questao_id"] for q in questoes if not q["correta"]], agora=agora
        ),
    }


# --- manutenção --------------------------------------------------------------


def pendencias_para_publicar(simulado: Simulado, agora: datetime | None = None) -> list[str]:
    """O que impede o simulado de ir ao ar. Vazio quando está pronto."""
    agora = _agora(agora)
    pendencias = []
    if not simulado.turmas:
        pendencias.append("nenhuma turma")
    if not simulado.questoes:
        pendencias.append("nenhuma questão")
    if simulado.abre_em is None or simulado.fecha_em is None:
        pendencias.append("abertura e fechamento não definidos")
    elif simulado.abre_em >= simulado.fecha_em:
        pendencias.append("o fechamento vem antes da abertura")
    elif simulado.fecha_em <= agora:
        pendencias.append(f"o fechamento ({em_brasilia(simulado.fecha_em)}) já passou")
    if not simulado.duracao_minutos or simulado.duracao_minutos <= 0:
        pendencias.append("tempo de prova não definido")

    incompletas = [sq.ordem for sq in simulado.questoes if len(sq.questao.alternativas) < len(LETRAS)]
    if incompletas:
        pendencias.append(f"questões sem as alternativas A–E: {incompletas}")
    sem_imagem = [sq.ordem for sq in simulado.questoes if sq.questao.imagem_pendente]
    if sem_imagem:
        pendencias.append(f"questões com imagem pendente: {sem_imagem}")
    return pendencias


def editar_simulado(
    db: Session,
    ident: Identidade,
    simulado_id: int | str,
    titulo: str | None = None,
    abre_em: str | datetime | None = None,
    fecha_em: str | datetime | None = None,
    duracao_minutos: int | None = None,
    turmas: list[str | int] | None = None,
    questoes: list[int | str | dict] | None = None,
    agora: datetime | None = None,
) -> dict:
    """Altera o simulado direto — o preview é no chat, antes da chamada.

    Antes de abrir, tudo muda, e o simulado já publicado precisa continuar
    pronto para ir ao ar. Aberto, só título e fechamento, e o fechamento só
    para mais tarde: encurtar tiraria tempo de quem está fazendo a prova.
    Encerrado, só o título: reabrir devolveria a prova a quem já viu o gabarito.

    `questoes` é a lista completa, na ordem: id de questão do acervo ou do
    próprio simulado, ou — enquanto ele é rascunho — a questão nova inteira.
    """
    from app.services.rascunhos import montar_prova

    ident.exigir_operador()
    agora = _agora(agora)
    s = _carregar(db, simulado_id)
    antes = situacao(s, agora)
    novo_fechamento = ler_data_hora(fecha_em)

    if antes == Situacao.ENCERRADO:
        if any(v is not None for v in (abre_em, fecha_em, duracao_minutos, turmas, questoes)):
            raise RegraDeNegocio(
                f"'{s.titulo}' fechou em {em_brasilia(s.fecha_em)} e o resultado já saiu: só o "
                "título muda. Reabrir devolveria a prova a quem já viu o gabarito."
            )
    elif antes == Situacao.ABERTO:
        if any(v is not None for v in (abre_em, duracao_minutos, turmas, questoes)):
            exigir_editavel(s, agora)
        if novo_fechamento is not None and novo_fechamento < s.fecha_em:
            raise RegraDeNegocio(
                f"'{s.titulo}' já abriu: o fechamento só pode ser estendido, não antecipado."
            )
    else:
        if abre_em is not None:
            s.abre_em = ler_data_hora(abre_em)
        if duracao_minutos is not None:
            if duracao_minutos <= 0:
                raise RegraDeNegocio("O tempo de prova precisa ser maior que zero.")
            s.duracao_minutos = duracao_minutos
        if turmas is not None:
            if not turmas:
                raise RegraDeNegocio("O simulado precisa de ao menos uma turma.")
            s.turmas = list({t.id: t for t in (resolver_turma(db, ref) for ref in turmas)}.values())
        if questoes is not None:
            if not questoes:
                raise RegraDeNegocio("O simulado precisa de ao menos uma questão.")
            rascunho = (
                db.get(Rascunho, s.rascunho_id)
                if s.status == Status.RASCUNHO and s.rascunho_id
                else None
            )
            anteriores = [sq.questao for sq in s.questoes]
            prova = montar_prova(db, ident, questoes, rascunho, {q.id: q for q in anteriores})
            # A questão nova que saiu da prova sai do rascunho junto: sem isso,
            # ela entraria no acervo na publicação sem ninguém a ter revisado.
            for q in anteriores:
                if rascunho and q.rascunho_id == rascunho.id and q not in prova:
                    remover(db, ident, q)
            s.questoes.clear()
            db.flush()
            s.questoes.extend(
                SimuladoQuestao(questao=q, ordem=ordem) for ordem, q in enumerate(prova, 1)
            )

    if titulo is not None:
        if not titulo.strip():
            raise RegraDeNegocio("O simulado precisa de um título.")
        s.titulo = titulo.strip()
    if novo_fechamento is not None:
        s.fecha_em = novo_fechamento

    if s.abre_em and s.fecha_em and s.abre_em >= s.fecha_em:
        raise RegraDeNegocio("O fechamento precisa vir depois da abertura.")
    if antes == Situacao.AGENDADO:
        pendencias = pendencias_para_publicar(s, agora)
        if pendencias:
            raise RegraDeNegocio(
                f"'{s.titulo}' já está publicado, e assim não teria como ir ao ar: "
                f"{'; '.join(pendencias)}."
            )

    tocar(ident, s)
    db.commit()
    return _resumo(s, agora)


def remover_simulado(
    db: Session, ident: Identidade, simulado_id: int | str, agora: datetime | None = None
) -> dict:
    """Remoção lógica. Com a prova aberta, não: tiraria a prova da mão de quem faz.

    Simulado em rascunho leva junto as questões novas que nasceram com ele —
    elas eram parte da mesma proposta.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    s = _carregar(db, simulado_id)
    if situacao(s, agora) == Situacao.ABERTO:
        raise RegraDeNegocio(
            f"'{s.titulo}' está aberto agora, com alunos fazendo a prova. Espere fechar."
        )
    novas = []
    if s.status == Status.RASCUNHO and s.rascunho_id:
        novas = [sq.questao for sq in s.questoes if sq.questao.rascunho_id == s.rascunho_id]
    remover(db, ident, s, *novas)
    db.commit()
    return {
        "simulado": s.titulo,
        "situacao": situacao(s, agora),
        "questoes_novas_removidas": len(novas),
        "reversivel": True,
    }
