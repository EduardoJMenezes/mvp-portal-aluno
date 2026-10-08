-- Aula assistida: o que cada aluno já viu do curso, uma linha por aluno por item.
-- Vale para vídeo e PDF. A questão não entra aqui: ela está feita quando tem resposta
-- em item_answers.

CREATE TABLE item_progress (
    id serial PRIMARY KEY,
    item_id integer NOT NULL REFERENCES items(id),
    aluno_id integer NOT NULL REFERENCES users(id),
    -- Onde o aluno parou no vídeo: é daqui que ele continua.
    posicao_segundos integer NOT NULL DEFAULT 0 CHECK (posicao_segundos >= 0),
    duracao_segundos integer CHECK (duracao_segundos > 0),
    -- Nulo: começou e não terminou. Preenche sozinho perto do fim, ou quando o aluno marca.
    concluido_em timestamptz,
    visto_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_item_progress UNIQUE (item_id, aluno_id)
);
CREATE INDEX ix_item_progress_aluno ON item_progress (aluno_id);
