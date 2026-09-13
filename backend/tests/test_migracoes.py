"""A migração leve é o que protege o curso real em produção.

O teste volta o schema do simulado ao formato antigo, com um simulado dentro,
roda a migração duas vezes e confere que o dado atravessou.
"""

from sqlalchemy import inspect, text

from app.migracoes import aplicar


def _colunas(motor, tabela: str) -> set[str]:
    return {c["name"] for c in inspect(motor).get_columns(tabela)}


def test_migracao_leva_o_schema_antigo_ao_atual_sem_perder_simulado(db, mundo):
    motor = db.get_bind()
    turma_id = mundo["turma_2027"].id
    professor_id = mundo["professor"].usuario_id
    db.close()  # a sessão não pode segurar lock enquanto o DDL roda

    with motor.begin() as c:
        c.execute(text("DROP TABLE exam_classes"))
        c.execute(text("DROP TABLE images, imports"))
        c.execute(text(
            "ALTER TABLE questions DROP COLUMN imagem_pendente, DROP COLUMN resolucao_comentada"
        ))
        c.execute(text(
            "ALTER TABLE exams DROP COLUMN abre_em, DROP COLUMN fecha_em, "
            "DROP COLUMN duracao_minutos, ADD COLUMN turma_id integer REFERENCES classes(id)"
        ))
        c.execute(text(
            "ALTER TABLE exam_attempts DROP COLUMN prazo_em, DROP COLUMN entregue_automaticamente"
        ))
        simulado_id = c.execute(
            text(
                "INSERT INTO exams (titulo, turma_id, status, criado_por_id) "
                "VALUES ('Simulado antigo', :turma, 'RASCUNHO', :prof) RETURNING id"
            ),
            {"turma": turma_id, "prof": professor_id},
        ).scalar_one()

    aplicar(motor)
    aplicar(motor)  # idempotente: roda a cada deploy

    assert "turma_id" not in _colunas(motor, "exams")
    assert {"abre_em", "fecha_em", "duracao_minutos"} <= _colunas(motor, "exams")
    assert {"imagem_pendente", "resolucao_comentada"} <= _colunas(motor, "questions")
    assert "imagem_id" not in _colunas(motor, "questions")
    assert {"questao_id", "parte"} <= _colunas(motor, "images")
    assert {"token_hash", "blocos", "relatorio"} <= _colunas(motor, "imports")
    assert {"prazo_em", "entregue_automaticamente"} <= _colunas(motor, "exam_attempts")

    with motor.connect() as c:
        vinculos = c.execute(
            text("SELECT turma_id FROM exam_classes WHERE simulado_id = :s"), {"s": simulado_id}
        ).scalars().all()
    assert vinculos == [turma_id], "a turma do simulado antigo não pode se perder"
