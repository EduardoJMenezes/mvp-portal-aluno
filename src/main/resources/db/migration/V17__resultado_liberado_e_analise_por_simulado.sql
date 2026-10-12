-- O resultado do simulado deixa de sair sozinho no fechamento: sai quando o professor libera, depois
-- de gerar as análises dos alunos pelo Claude. Vale para o resultado inteiro — nota, gabarito,
-- gráficos e a análise escrita.
ALTER TABLE exams ADD COLUMN resultado_liberado_em timestamptz;
ALTER TABLE exams ADD COLUMN resultado_liberado_por_id integer REFERENCES users(id);
-- Os que já tinham fechado mostraram o resultado aos alunos: continuam liberados, desde o fechamento.
UPDATE exams SET resultado_liberado_em = fecha_em
 WHERE status = 'PUBLICADO' AND fecha_em IS NOT NULL AND fecha_em <= now();

-- A análise passa a ter recorte. Com simulado: é a daquela prova, escrita para o aluno, que a lê no
-- resultado. Sem simulado: é a geral, de tudo o que ele respondeu, escrita para o professor — o aluno
-- não vê. A fila automática e o "pedir outra" saíram: quem dispara é o professor, no Claude.
ALTER TABLE student_analyses ADD COLUMN simulado_id integer REFERENCES exams(id);
ALTER TABLE student_analyses
    DROP COLUMN refazer_pedido_em,
    DROP COLUMN refazer_pedido_por_id,
    DROP COLUMN refazer_orientacao;
DROP INDEX ix_student_analyses_aluno;
CREATE INDEX ix_student_analyses_aluno ON student_analyses (aluno_id, simulado_id, id DESC);
CREATE INDEX ix_student_analyses_simulado ON student_analyses (simulado_id, aluno_id) WHERE simulado_id IS NOT NULL;
