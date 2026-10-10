-- A questão publicada pode ser corrigida mesmo com simulado aberto ou encerrado. Em troca, cada
-- mudança no que o aluno lê (enunciado, alternativas, gabarito) deixa rastro: quem, quando, como
-- estava e como ficou, e quantas respostas tiveram o acerto recalculado.
CREATE TABLE question_changes (
    id serial PRIMARY KEY,
    questao_id integer NOT NULL REFERENCES questions(id),
    alterado_por_id integer REFERENCES users(id),
    alterado_em timestamptz NOT NULL DEFAULT now(),
    resumo varchar(500) NOT NULL,
    antes text NOT NULL,
    depois text NOT NULL,
    respostas_recalculadas integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_question_changes_questao ON question_changes (questao_id, alterado_em DESC);
