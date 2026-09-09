"""Simulados: o que o aluno vê, responde e o que fica persistido."""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.errors import NaoAutorizado, NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import (
    LETRAS,
    Questao,
    Resposta,
    Simulado,
    SimuladoQuestao,
    Status,
    Tentativa,
)
from app.services.catalogo import (
    exigir_acesso_a_turma,
    ids_das_turmas_do_aluno,
    resolver_turma,
)


def resolver_simulado(db: Session, referencia: str | int) -> Simulado:
    if isinstance(referencia, int) or str(referencia).isdigit():
        simulado = db.get(Simulado, int(referencia))
        if simulado:
            return simulado

    texto = str(referencia).strip().lower()
    todos = db.scalars(select(Simulado).order_by(Simulado.criado_em.desc())).all()
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


def _exigir_simulado_visivel(db: Session, ident: Identidade, simulado: Simulado) -> None:
    exigir_acesso_a_turma(db, ident, simulado.turma)
    if ident.e_aluno and simulado.status != Status.PUBLICADO:
        raise NaoAutorizado("Este simulado ainda não foi publicado.")


def listar_simulados(
    db: Session, ident: Identidade, turma: str | int | None = None
) -> list[dict]:
    consulta = select(Simulado).order_by(Simulado.criado_em.desc())

    if turma is not None:
        alvo = resolver_turma(db, turma)
        exigir_acesso_a_turma(db, ident, alvo)
        consulta = consulta.where(Simulado.turma_id == alvo.id)
    elif ident.e_aluno:
        consulta = consulta.where(
            Simulado.turma_id.in_(ids_das_turmas_do_aluno(db, ident.usuario_id) or [-1])
        )

    if ident.e_aluno:
        consulta = consulta.where(Simulado.status == Status.PUBLICADO)

    saida = []
    for s in db.scalars(consulta).all():
        item = {
            "simulado_id": s.id,
            "titulo": s.titulo,
            "turma": s.turma.nome,
            "status": s.status,
            "questoes": len(s.questoes),
        }
        if ident.e_aluno:
            tentativa = db.scalar(
                select(Tentativa).where(
                    Tentativa.simulado_id == s.id, Tentativa.aluno_id == ident.usuario_id
                )
            )
            item["respondido"] = bool(tentativa and tentativa.finalizado_em)
            item["iniciado"] = bool(tentativa)
        else:
            item["tentativas"] = len(
                db.scalars(select(Tentativa.id).where(Tentativa.simulado_id == s.id)).all()
            )
        saida.append(item)
    return saida


def abrir_simulado(db: Session, ident: Identidade, simulado_id: int) -> dict:
    """Devolve as questões para responder. Sem gabarito — ele fica no backend."""
    simulado = db.get(Simulado, simulado_id)
    if simulado is None:
        raise NaoEncontrado(f"Simulado {simulado_id} não existe.")
    _exigir_simulado_visivel(db, ident, simulado)

    tentativa = None
    if ident.e_aluno:
        tentativa = db.scalar(
            select(Tentativa).where(
                Tentativa.simulado_id == simulado.id, Tentativa.aluno_id == ident.usuario_id
            )
        )
        if tentativa is None:
            tentativa = Tentativa(simulado_id=simulado.id, aluno_id=ident.usuario_id)
            db.add(tentativa)
            db.commit()

    respondidas: dict[int, str] = {}
    if tentativa:
        respondidas = {
            r.questao_id: r.alternativa_marcada
            for r in db.scalars(
                select(Resposta).where(Resposta.tentativa_id == tentativa.id)
            ).all()
        }

    questoes = db.scalars(
        select(SimuladoQuestao)
        .options(selectinload(SimuladoQuestao.questao).selectinload(Questao.alternativas))
        .where(SimuladoQuestao.simulado_id == simulado.id)
        .order_by(SimuladoQuestao.ordem)
    ).all()

    return {
        "simulado_id": simulado.id,
        "titulo": simulado.titulo,
        "turma": simulado.turma.nome,
        "status": simulado.status,
        "finalizado": bool(tentativa and tentativa.finalizado_em),
        "questoes": [
            {
                "ordem": sq.ordem,
                "questao_id": sq.questao_id,
                "enunciado": sq.questao.enunciado,
                "alternativas": {a.letra: a.texto for a in sq.questao.alternativas},
                "marcada": respondidas.get(sq.questao_id),
                **({"gabarito": sq.questao.gabarito} if ident.e_operador else {}),
            }
            for sq in questoes
        ],
    }


def responder(
    db: Session, ident: Identidade, simulado_id: int, questao_id: int, alternativa: str
) -> dict:
    """Grava a resposta do aluno. A correção acontece aqui, no backend."""
    if not ident.e_aluno:
        raise NaoAutorizado("Somente alunos respondem simulados.")

    letra = str(alternativa or "").strip().upper()
    if letra not in LETRAS:
        raise RegraDeNegocio(f"Alternativa '{alternativa}' inválida. Use A a E.")

    simulado = db.get(Simulado, simulado_id)
    if simulado is None:
        raise NaoEncontrado(f"Simulado {simulado_id} não existe.")
    _exigir_simulado_visivel(db, ident, simulado)

    vinculo = db.scalar(
        select(SimuladoQuestao).where(
            SimuladoQuestao.simulado_id == simulado.id, SimuladoQuestao.questao_id == questao_id
        )
    )
    if vinculo is None:
        raise RegraDeNegocio(f"A questão {questao_id} não faz parte deste simulado.")

    tentativa = db.scalar(
        select(Tentativa).where(
            Tentativa.simulado_id == simulado.id, Tentativa.aluno_id == ident.usuario_id
        )
    )
    if tentativa is None:
        tentativa = Tentativa(simulado_id=simulado.id, aluno_id=ident.usuario_id)
        db.add(tentativa)
        db.flush()
    if tentativa.finalizado_em:
        raise RegraDeNegocio("Este simulado já foi finalizado; não é possível alterar respostas.")

    correta = letra == vinculo.questao.gabarito
    existente = db.scalar(
        select(Resposta).where(
            Resposta.tentativa_id == tentativa.id, Resposta.questao_id == questao_id
        )
    )
    if existente:
        existente.alternativa_marcada = letra
        existente.correta = correta
        existente.respondido_em = datetime.now(UTC)
    else:
        db.add(
            Resposta(
                tentativa_id=tentativa.id,
                questao_id=questao_id,
                alternativa_marcada=letra,
                correta=correta,
            )
        )
    db.commit()

    total = len(simulado.questoes)
    respondidas = len(
        db.scalars(select(Resposta.id).where(Resposta.tentativa_id == tentativa.id)).all()
    )
    # Não devolvemos `correta`: o aluno vê o resultado ao finalizar.
    return {"registrado": True, "respondidas": respondidas, "total": total}


def finalizar(db: Session, ident: Identidade, simulado_id: int) -> dict:
    if not ident.e_aluno:
        raise NaoAutorizado("Somente alunos finalizam uma tentativa.")

    simulado = db.get(Simulado, simulado_id)
    if simulado is None:
        raise NaoEncontrado(f"Simulado {simulado_id} não existe.")
    _exigir_simulado_visivel(db, ident, simulado)

    tentativa = db.scalar(
        select(Tentativa).where(
            Tentativa.simulado_id == simulado.id, Tentativa.aluno_id == ident.usuario_id
        )
    )
    if tentativa is None:
        raise RegraDeNegocio("Você ainda não começou este simulado.")

    if tentativa.finalizado_em is None:
        tentativa.finalizado_em = datetime.now(UTC)
        db.commit()

    respostas = db.scalars(
        select(Resposta)
        .options(selectinload(Resposta.questao))
        .where(Resposta.tentativa_id == tentativa.id)
    ).all()
    acertos = sum(1 for r in respostas if r.correta)
    total = len(simulado.questoes)

    return {
        "simulado_id": simulado.id,
        "titulo": simulado.titulo,
        "acertos": acertos,
        "total": total,
        "percentual": round(100 * acertos / total, 1) if total else 0.0,
        "respostas": [
            {
                "questao_id": r.questao_id,
                "marcada": r.alternativa_marcada,
                "gabarito": r.questao.gabarito,
                "correta": r.correta,
            }
            for r in respostas
        ],
    }
