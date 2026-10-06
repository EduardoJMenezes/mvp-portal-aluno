-- Questão na aula: a linha do módulo passa a ser vídeo, PDF ou questão do acervo.
-- O aluno responde ali mesmo, uma vez só, e a resposta fica gravada com a alternativa marcada —
-- é dela que sai a devolutiva por assunto.

ALTER TABLE items
    ADD COLUMN questao_id integer REFERENCES questions(id),
    DROP CONSTRAINT ck_items_video_ou_pdf,
    ADD CONSTRAINT ck_items_conteudo
        CHECK (video_id IS NOT NULL OR material_id IS NOT NULL OR questao_id IS NOT NULL);

-- A mesma questão não entra duas vezes no mesmo sub-módulo; em outro, pode.
CREATE UNIQUE INDEX uq_item_questao ON items (submodulo_id, questao_id)
    WHERE removido_em IS NULL AND questao_id IS NOT NULL;
CREATE INDEX ix_items_questao ON items (questao_id) WHERE questao_id IS NOT NULL;

-- Uma resposta por aluno por linha: marcou, valeu. A questão vai repetida aqui para a
-- estatística por assunto não depender de a linha continuar existindo.
CREATE TABLE item_answers (
    id serial PRIMARY KEY,
    item_id integer NOT NULL REFERENCES items(id),
    aluno_id integer NOT NULL REFERENCES users(id),
    questao_id integer NOT NULL REFERENCES questions(id),
    alternativa_marcada varchar NOT NULL CHECK (alternativa_marcada IN ('A', 'B', 'C', 'D', 'E')),
    correta boolean NOT NULL,
    respondido_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_item_answer UNIQUE (item_id, aluno_id)
);
CREATE INDEX ix_item_answers_aluno ON item_answers (aluno_id);
CREATE INDEX ix_item_answers_questao ON item_answers (questao_id);
