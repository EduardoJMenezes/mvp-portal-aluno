"""Renumera as questões de um capítulo pelo número que está no título do vídeo.

Serve para consertar o que foi importado antes de a numeração pela apostila
existir: a plataforma tinha numerado 1, 2, 3… enquanto o vídeo se chamava Q04,
Q30, Q52. Com dois números para a mesma questão, professor e aluno falam
línguas diferentes.

Só mexe em `class_questions.numero` e no enunciado das questões do capítulo
indicado. Não apaga nada, não toca no Vimeo e é idempotente: rodar duas vezes
dá o mesmo resultado.

    DATABASE_URL=... python scripts/corrigir_numeracao_capitulo.py --capitulo "K03 - Estequiometria" --confirmo

Sem `--confirmo`, mostra o de-para e não grava.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))

from sqlalchemy import select  # noqa: E402

from app.db import SessionLocal  # noqa: E402
from app.models import Capitulo, Questao, TurmaQuestao, Video  # noqa: E402
from app.integracoes.vimeo.nomes import numero_do_titulo  # noqa: E402


def resolver_capitulo(db, referencia: str) -> Capitulo:
    if referencia.isdigit():
        capitulo = db.get(Capitulo, int(referencia))
        if capitulo:
            return capitulo
    capitulo = db.scalar(select(Capitulo).where(Capitulo.nome == referencia))
    if capitulo is None:
        disponiveis = ", ".join(c.nome for c in db.scalars(select(Capitulo).order_by(Capitulo.nome)))
        raise SystemExit(f"Capítulo '{referencia}' não existe. Existem: {disponiveis}")
    return capitulo


def main() -> int:
    parser = argparse.ArgumentParser(description="Renumera um capítulo pelo título do vídeo.")
    parser.add_argument("--capitulo", required=True, help="Id ou nome exato do capítulo")
    parser.add_argument("--confirmo", action="store_true", help="Sem isto, só mostra o de-para")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    with SessionLocal() as db:
        capitulo = resolver_capitulo(db, args.capitulo)
        linhas = db.execute(
            select(TurmaQuestao, Questao, Video)
            .join(Questao, Questao.id == TurmaQuestao.questao_id)
            .outerjoin(Video, Video.id == Questao.video_id)
            .where(TurmaQuestao.capitulo_id == capitulo.id)
            .order_by(TurmaQuestao.numero)
        ).all()

        print(f"\nCapítulo {capitulo.nome} (id {capitulo.id}): {len(linhas)} questão(ões)\n")
        planejado: list[tuple[TurmaQuestao, Questao, int, str]] = []
        destinos: dict[int, str] = {}
        colisoes: list[str] = []

        for turma_questao, questao, video in linhas:
            titulo = video.titulo if video else None
            novo = numero_do_titulo(titulo)
            if novo is None:
                print(f"  Q{turma_questao.numero:02d} -> mantém (sem número no título: {titulo!r})")
                continue
            if novo in destinos and destinos[novo] != titulo:
                colisoes.append(f"número {novo} sairia de dois vídeos: {destinos[novo]} e {titulo}")
            destinos[novo] = titulo or ""
            enunciado = f"Questão {novo} da apostila — resolução em vídeo"
            marca = "já ok" if turma_questao.numero == novo and questao.enunciado == enunciado else "muda"
            print(f"  Q{turma_questao.numero:02d} -> Q{novo:02d}  ({titulo})  [{marca}]")
            planejado.append((turma_questao, questao, novo, enunciado))

        if colisoes:
            print("\nConflitos, nada será gravado:")
            for conflito in colisoes:
                print(f"  - {conflito}")
            return 1

        if not args.confirmo:
            print("\nNada foi gravado. Rode de novo com --confirmo.")
            return 0

        alterados = 0
        for turma_questao, questao, novo, enunciado in planejado:
            if turma_questao.numero != novo or questao.enunciado != enunciado:
                turma_questao.numero = novo
                questao.enunciado = enunciado
                alterados += 1
        db.commit()
        print(f"\n{alterados} questão(ões) atualizadas.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
