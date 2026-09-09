"""Estatísticas sobre respostas reais dos alunos (seção 15).

O objetivo aqui não é dashboard: é entregar números corretos e já agregados o
suficiente para o LLM transformar em análise em linguagem natural.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoAutorizado, NaoEncontrado
from app.identidade import Identidade
from app.models import (
    Classificacao,
    Matricula,
    Papel,
    Questao,
    Resposta,
    Simulado,
    SimuladoQuestao,
    Tentativa,
    TurmaQuestao,
    Usuario,
)
from app.services.catalogo import exigir_acesso_a_turma
from app.services.simulados import resolver_simulado


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


def _topico_da_questao(db: Session, questao_id: int) -> str | None:
    c = db.scalar(select(Classificacao).where(Classificacao.questao_id == questao_id))
    if c is None:
        return None
    return c.subtopico or c.topico


def _numero_na_turma(db: Session, turma_id: int, questao_id: int) -> int | None:
    v = db.scalar(
        select(TurmaQuestao).where(
            TurmaQuestao.turma_id == turma_id, TurmaQuestao.questao_id == questao_id
        )
    )
    return v.numero if v else None


def desempenho_aluno(
    db: Session, ident: Identidade, aluno: str | int, simulado: str | int | None = None
) -> dict:
    """Como um aluno foi — no último simulado respondido, ou num específico."""
    alvo = resolver_aluno(db, aluno)

    # Aluno só consulta o próprio desempenho; operador consulta qualquer um.
    if ident.e_aluno and ident.usuario_id != alvo.id:
        raise NaoAutorizado("Você só pode consultar o seu próprio desempenho.")

    consulta = (
        select(Tentativa)
        .options(selectinload(Tentativa.simulado))
        .where(Tentativa.aluno_id == alvo.id)
        .order_by(Tentativa.iniciado_em.desc())
    )
    if simulado is not None:
        consulta = consulta.where(Tentativa.simulado_id == resolver_simulado(db, simulado).id)

    tentativas = db.scalars(consulta).all()
    if not tentativas:
        return {
            "aluno": alvo.nome,
            "encontrou_dados": False,
            "mensagem": f"{alvo.nome} ainda não respondeu nenhum simulado.",
        }

    tentativa = tentativas[0]
    exigir_acesso_a_turma(db, ident, tentativa.simulado.turma)

    respostas = db.scalars(
        select(Resposta)
        .options(selectinload(Resposta.questao))
        .where(Resposta.tentativa_id == tentativa.id)
    ).all()
    acertos = sum(1 for r in respostas if r.correta)
    total = len(tentativa.simulado.questoes)

    return {
        "aluno": alvo.nome,
        "encontrou_dados": True,
        "simulado": tentativa.simulado.titulo,
        "simulado_id": tentativa.simulado_id,
        "turma": tentativa.simulado.turma.nome,
        "finalizado": bool(tentativa.finalizado_em),
        "acertos": acertos,
        "total_questoes": total,
        "percentual": round(100 * acertos / total, 1) if total else 0.0,
        "questoes": [
            {
                "questao_id": r.questao_id,
                "numero": _numero_na_turma(db, tentativa.simulado.turma_id, r.questao_id),
                "enunciado": r.questao.enunciado,
                "topico": _topico_da_questao(db, r.questao_id),
                "marcada": r.alternativa_marcada,
                "gabarito": r.questao.gabarito,
                "correta": r.correta,
            }
            for r in respostas
        ],
        "erros_por_topico": sorted(
            {
                t: sum(
                    1
                    for r in respostas
                    if not r.correta and _topico_da_questao(db, r.questao_id) == t
                )
                for t in {
                    _topico_da_questao(db, r.questao_id) for r in respostas if not r.correta
                }
                if t
            }.items(),
            key=lambda kv: -kv[1],
        ),
    }


def estatisticas_simulado(db: Session, ident: Identidade, simulado: str | int) -> dict:
    """Desempenho da turma inteira, questão a questão."""
    ident.exigir_operador()
    alvo = resolver_simulado(db, simulado)
    exigir_acesso_a_turma(db, ident, alvo.turma)

    tentativas = db.scalars(select(Tentativa).where(Tentativa.simulado_id == alvo.id)).all()
    matriculados = len(db.scalars(select(Matricula.id).where(Matricula.turma_id == alvo.turma_id)).all())

    questoes = db.scalars(
        select(SimuladoQuestao)
        .options(selectinload(SimuladoQuestao.questao).selectinload(Questao.classificacoes))
        .where(SimuladoQuestao.simulado_id == alvo.id)
        .order_by(SimuladoQuestao.ordem)
    ).all()

    if not tentativas:
        return {
            "simulado": alvo.titulo,
            "turma": alvo.turma.nome,
            "alunos_matriculados": matriculados,
            "alunos_responderam": 0,
            "encontrou_dados": False,
            "mensagem": "Nenhum aluno respondeu este simulado ainda.",
        }

    ids_tentativas = [t.id for t in tentativas]
    respostas = db.scalars(
        select(Resposta).where(Resposta.tentativa_id.in_(ids_tentativas))
    ).all()

    por_questao = []
    for sq in questoes:
        do_item = [r for r in respostas if r.questao_id == sq.questao_id]
        acertos = sum(1 for r in do_item if r.correta)
        por_questao.append(
            {
                "ordem": sq.ordem,
                "questao_id": sq.questao_id,
                "numero": _numero_na_turma(db, alvo.turma_id, sq.questao_id),
                "enunciado": sq.questao.enunciado,
                "topico": _topico_da_questao(db, sq.questao_id),
                "respostas": len(do_item),
                "acertos": acertos,
                "percentual_acerto": round(100 * acertos / len(do_item), 1) if do_item else None,
                "distribuicao": {
                    letra: sum(1 for r in do_item if r.alternativa_marcada == letra)
                    for letra in sorted({r.alternativa_marcada for r in do_item})
                },
            }
        )

    por_aluno = []
    for t in tentativas:
        do_aluno = [r for r in respostas if r.tentativa_id == t.id]
        acertos = sum(1 for r in do_aluno if r.correta)
        por_aluno.append(
            {
                "aluno": t.aluno.nome,
                "acertos": acertos,
                "total": len(questoes),
                "percentual": round(100 * acertos / len(questoes), 1) if questoes else 0.0,
                "finalizado": bool(t.finalizado_em),
            }
        )

    media = round(sum(a["percentual"] for a in por_aluno) / len(por_aluno), 1) if por_aluno else 0.0

    com_dados = [q for q in por_questao if q["percentual_acerto"] is not None]
    pior = min(com_dados, key=lambda q: q["percentual_acerto"], default=None)

    return {
        "simulado": alvo.titulo,
        "simulado_id": alvo.id,
        "turma": alvo.turma.nome,
        "encontrou_dados": True,
        "alunos_matriculados": matriculados,
        "alunos_responderam": len(tentativas),
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
