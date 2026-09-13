"""Migração leve: o schema acompanha o modelo sem apagar conteúdo.

Até o curso real entrar em produção, mudar `models.py` custava um `seed --reset`.
Não custa mais — o reset apagaria o curso montado com os vídeos do Vimeo. Então
cada mudança de schema ganha aqui a sua forma idempotente, e o Dockerfile roda
este módulo antes de subir o servidor, a cada deploy:

    python -m app.migracoes

`create_all` cria o que é tabela nova e não encosta nas existentes; o resto é
`ALTER ... IF NOT EXISTS`. Rodar duas vezes dá no mesmo que rodar uma.

Mudou `models.py`? Acrescente a alteração correspondente ao fim de
`ALTERACOES` — nunca edite uma que já foi para produção.
"""

from __future__ import annotations

from sqlalchemy import Engine, inspect, text

from app.models import Base

ALTERACOES = [
    # simulado: agenda, tempo de prova, imagem na questão, entrega automática
    "ALTER TABLE questions ADD COLUMN IF NOT EXISTS imagem_pendente boolean NOT NULL DEFAULT false",
    "ALTER TABLE questions ADD COLUMN IF NOT EXISTS imagem_id integer REFERENCES images(id)",
    "ALTER TABLE exams ADD COLUMN IF NOT EXISTS abre_em timestamptz",
    "ALTER TABLE exams ADD COLUMN IF NOT EXISTS fecha_em timestamptz",
    "ALTER TABLE exams ADD COLUMN IF NOT EXISTS duracao_minutos integer",
    "ALTER TABLE exam_attempts ADD COLUMN IF NOT EXISTS prazo_em timestamptz",
    "ALTER TABLE exam_attempts ADD COLUMN IF NOT EXISTS entregue_automaticamente boolean "
    "NOT NULL DEFAULT false",
]


def _simulado_para_varias_turmas(conexao) -> None:
    """O simulado deixou de ter uma turma só: a turma vai para `exam_classes`."""
    colunas = {c["name"] for c in inspect(conexao).get_columns("exams")}
    if "turma_id" not in colunas:
        return
    conexao.execute(
        text(
            "INSERT INTO exam_classes (simulado_id, turma_id) "
            "SELECT id, turma_id FROM exams WHERE turma_id IS NOT NULL "
            "ON CONFLICT DO NOTHING"
        )
    )
    conexao.execute(text("ALTER TABLE exams DROP COLUMN turma_id"))


def aplicar(motor: Engine) -> None:
    with motor.begin() as conexao:
        Base.metadata.create_all(conexao)
        for sql in ALTERACOES:
            conexao.execute(text(sql))
        _simulado_para_varias_turmas(conexao)


if __name__ == "__main__":
    from app.db import engine

    aplicar(engine)
    print("migracoes: schema em dia")
