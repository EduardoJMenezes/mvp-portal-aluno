"""Estatísticas sobre respostas reais dos alunos (seção 15).

O objetivo aqui não é dashboard: é entregar números corretos e já agregados o
suficiente para o LLM transformar em análise em linguagem natural.

Questão em branco conta como erro (docs/MODELO-SIMULADO.md). Por isso as contas
partem das questões do simulado e de quem fez a prova — não só das respostas
gravadas, que não sabem da questão que ficou sem resposta.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoEncontrado
from app.identidade import Identidade
from app.models import Assunto, Matricula, Papel, QuestaoAssunto, SubAssunto, Tentativa, Usuario
from app.services import acesso, taxonomia
from app.services.simulados import Situacao, consolidar, resolver_simulado, situacao


def resolver_aluno(db: Session, referencia: str | int) -> Usuario:
    if isinstance(referencia, int) or str(referencia).isdigit():
        aluno = db.get(Usuario, int(referencia))
        if aluno and aluno.papel == Papel.ALUNO:
            return aluno

    texto = str(referencia).strip().lower()
    alunos = db.scalars(select(Usuario).where(Usuario.papel == Papel.ALUNO)).all()
    exatos = [a for a in alunos if a.nome.lower() == texto or a.email.lower() == texto]
    if exatos:
        return exatos[0]
    parciais = [a for a in alunos if texto in a.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]
    if len(parciais) > 1:
        nomes = ", ".join(a.nome for a in parciais)
        raise NaoEncontrado(f"'{referencia}' corresponde a mais de um aluno: {nomes}.")

    disponiveis = ", ".join(a.nome for a in alunos) or "(nenhum)"
    raise NaoEncontrado(f"Aluno '{referencia}' não encontrado. Alunos: {disponiveis}.")


def _etiqueta_da_questao(db: Session, questao_id: int) -> QuestaoAssunto | None:
    """A classificação da questão — assunto e, quando houver, sub-assunto."""
    return db.scalar(select(QuestaoAssunto).where(QuestaoAssunto.questao_id == questao_id))


def _topico_da_questao(db: Session, questao_id: int) -> str | None:
    """O rótulo que o aluno lê: o sub-assunto, se existir; senão o assunto."""
    etiqueta = _etiqueta_da_questao(db, questao_id)
    if etiqueta is None:
        return None
    if etiqueta.subassunto is not None:
        return etiqueta.subassunto.nome
    return etiqueta.assunto.nome


def _rotulo_da_etiqueta(db: Session, assunto_id: int, subassunto_id: int | None) -> str:
    if subassunto_id is not None:
        sub = db.get(SubAssunto, subassunto_id)
        if sub is not None:
            return sub.nome
    assunto = db.get(Assunto, assunto_id)
    return assunto.nome if assunto else "(sem assunto)"


def recomendar_videos(
    db: Session,
    ident: Identidade,
    questoes_erradas: Iterable[int],
    limite: int = 5,
    agora: datetime | None = None,
) -> list[dict]:
    """O elo que faltava: do erro do aluno para o vídeo que explica aquilo.

    Um tópico por etiqueta errada, do mais errado para o menos — é a análise
    que o aluno lê no resultado. O tópico entra mesmo sem vídeo: saber onde foi
    pior já é metade da análise.

    Vale o acervo inteiro, não só a turma dele. O vídeo de outro curso aparece
    bloqueado — nome e aviso, sem nada do Vimeo —, porque esconder o material
    que responde exatamente à dúvida seria pior do que mostrar que ele existe.
    """
    por_etiqueta: dict[tuple[int, int | None], int] = {}
    for questao_id in questoes_erradas:
        etiqueta = _etiqueta_da_questao(db, questao_id)
        if etiqueta is None:
            continue
        chave = (etiqueta.assunto_id, etiqueta.subassunto_id)
        por_etiqueta[chave] = por_etiqueta.get(chave, 0) + 1

    recomendacoes: list[dict] = []
    for (assunto_id, subassunto_id), erros in sorted(
        por_etiqueta.items(), key=lambda par: -par[1]
    ):
        videos = taxonomia.videos_que_explicam(db, assunto_id, subassunto_id, limite=limite)
        liberados = acesso.videos_liberados(db, ident, [v.id for v in videos], agora=agora)
        recomendacoes.append(
            {
                "topico": _rotulo_da_etiqueta(db, assunto_id, subassunto_id),
                "erros": erros,
                "videos": [acesso.descrever_video(v, v.id in liberados) for v in videos],
            }
        )
    return recomendacoes


def desempenho_aluno(
    db: Session,
    ident: Identidade,
    aluno: str | int,
    simulado: str | int | None = None,
    agora: datetime | None = None,
) -> dict:
    """Como um aluno foi — no último simulado que começou, ou num específico.

    É a visão do professor, e vale a qualquer momento. O aluno vê o próprio
    resultado por `simulados.resultado`, que só abre quando o simulado fecha.
    """
    ident.exigir_operador()
    agora = agora or datetime.now(UTC)
    alvo = resolver_aluno(db, aluno)

    consulta = (
        select(Tentativa)
        .options(selectinload(Tentativa.respostas))
        .where(Tentativa.aluno_id == alvo.id)
        .order_by(Tentativa.iniciado_em.desc())
    )
    if simulado is not None:
        consulta = consulta.where(Tentativa.simulado_id == resolver_simulado(db, simulado).id)

    tentativa = db.scalars(consulta).first()
    if tentativa is None:
        return {
            "aluno": alvo.nome,
            "encontrou_dados": False,
            "mensagem": f"{alvo.nome} ainda não fez nenhum simulado.",
        }

    s = tentativa.simulado
    entregue = consolidar(tentativa, agora)
    db.commit()

    marcadas = {r.questao_id: r for r in tentativa.respostas}
    questoes = []
    for sq in s.questoes:
        r = marcadas.get(sq.questao_id)
        questoes.append(
            {
                "ordem": sq.ordem,
                "questao_id": sq.questao_id,
                "enunciado": sq.questao.enunciado,
                "topico": _topico_da_questao(db, sq.questao_id),
                "marcada": r.alternativa_marcada if r else None,
                "gabarito": sq.questao.gabarito,
                "correta": bool(r and r.correta),
            }
        )

    erradas = [q for q in questoes if not q["correta"]]
    erros_por_topico: dict[str, int] = {}
    for q in erradas:
        if q["topico"]:
            erros_por_topico[q["topico"]] = erros_por_topico.get(q["topico"], 0) + 1

    total = len(questoes)
    acertos = total - len(erradas)
    return {
        "aluno": alvo.nome,
        "encontrou_dados": True,
        "simulado": s.titulo,
        "simulado_id": s.id,
        "turmas": [t.nome for t in s.turmas],
        "situacao": situacao(s, agora),
        "entregue": entregue,
        "entregue_automaticamente": tentativa.entregue_automaticamente,
        "acertos": acertos,
        "em_branco": sum(1 for q in questoes if q["marcada"] is None),
        "total_questoes": total,
        "percentual": round(100 * acertos / total, 1) if total else 0.0,
        "questoes": questoes,
        "erros_por_topico": sorted(erros_por_topico.items(), key=lambda kv: -kv[1]),
        # Onde o aluno vai para consertar o que errou. Vídeo que não é do
        # curso dele vem bloqueado, com nome e aviso — nada do Vimeo.
        "recomendacoes": recomendar_videos(
            db, ident, [q["questao_id"] for q in erradas], agora=agora
        ),
    }


def estatisticas_simulado(
    db: Session, ident: Identidade, simulado: str | int, agora: datetime | None = None
) -> dict:
    """Desempenho de quem fez o simulado, questão a questão.

    Antes do fechamento os números saem marcados como parciais: ainda há prova
    em andamento.
    """
    ident.exigir_operador()
    agora = agora or datetime.now(UTC)
    alvo = resolver_simulado(db, simulado)

    turma_ids = [t.id for t in alvo.turmas] or [-1]
    matriculados = int(
        db.scalar(
            select(func.count(func.distinct(Matricula.usuario_id))).where(
                Matricula.turma_id.in_(turma_ids)
            )
        )
        or 0
    )
    tentativas = db.scalars(
        select(Tentativa)
        .options(selectinload(Tentativa.respostas), selectinload(Tentativa.aluno))
        .where(Tentativa.simulado_id == alvo.id)
    ).all()
    for t in tentativas:
        consolidar(t, agora)
    db.commit()

    base = {
        "simulado": alvo.titulo,
        "simulado_id": alvo.id,
        "turmas": [t.nome for t in alvo.turmas],
        "situacao": situacao(alvo, agora),
        "parcial": situacao(alvo, agora) != Situacao.ENCERRADO,
        "alunos_matriculados": matriculados,
        "alunos_responderam": len(tentativas),
    }
    if not tentativas:
        return {
            **base,
            "encontrou_dados": False,
            "mensagem": "Nenhum aluno começou este simulado ainda.",
        }

    participantes = len(tentativas)
    total = len(alvo.questoes)
    respostas = [r for t in tentativas for r in t.respostas]

    por_questao = []
    for sq in alvo.questoes:
        do_item = [r for r in respostas if r.questao_id == sq.questao_id]
        acertos = sum(1 for r in do_item if r.correta)
        por_questao.append(
            {
                "ordem": sq.ordem,
                "questao_id": sq.questao_id,
                "enunciado": sq.questao.enunciado,
                "topico": _topico_da_questao(db, sq.questao_id),
                "gabarito": sq.questao.gabarito,
                "acertos": acertos,
                "em_branco": participantes - len(do_item),
                # A base é quem fez a prova: em branco conta como erro.
                "percentual_acerto": round(100 * acertos / participantes, 1),
                "distribuicao": {
                    letra: sum(1 for r in do_item if r.alternativa_marcada == letra)
                    for letra in sorted({r.alternativa_marcada for r in do_item})
                },
            }
        )

    por_aluno = []
    for t in tentativas:
        acertos = sum(1 for r in t.respostas if r.correta)
        por_aluno.append(
            {
                "aluno": t.aluno.nome,
                "acertos": acertos,
                "total": total,
                "percentual": round(100 * acertos / total, 1) if total else 0.0,
                "entregue": t.finalizado_em is not None,
            }
        )

    media = round(sum(a["percentual"] for a in por_aluno) / participantes, 1)
    pior = min(por_questao, key=lambda q: q["percentual_acerto"], default=None)

    return {
        **base,
        "encontrou_dados": True,
        "media_percentual": media,
        "por_questao": por_questao,
        "por_aluno": sorted(por_aluno, key=lambda a: -a["percentual"]),
        "maior_dificuldade": (
            {
                "questao_id": pior["questao_id"],
                "topico": pior["topico"],
                "enunciado": pior["enunciado"],
                "percentual_acerto": pior["percentual_acerto"],
            }
            if pior
            else None
        ),
    }
