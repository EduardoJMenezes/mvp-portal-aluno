-- A análise do aluno: o retrato dele em texto, escrito pelo Claude do professor (pelo MCP) a partir
-- do que o aluno respondeu e dos comentários que o professor aprovou em cada alternativa. Não passa
-- por rascunho: vai direto para o aluno, e em troca o professor pode ocultar ou pedir outra.
-- Cada escrita é uma linha nova; vale a mais recente de cada aluno, e as anteriores ficam de histórico.
CREATE TABLE student_analyses (
    id serial PRIMARY KEY,
    aluno_id integer NOT NULL REFERENCES users(id),
    ponto_principal text NOT NULL,
    padroes text,
    melhorou text,
    proximos_passos text,
    -- Quantas respostas do aluno existiam quando ela foi escrita.
    respostas integer NOT NULL CHECK (respostas >= 0),
    -- O operador em cujo Claude ela foi escrita.
    escrita_por_id integer NOT NULL REFERENCES users(id),
    escrita_em timestamptz NOT NULL DEFAULT now(),
    -- Preenchido: o aluno deixa de ver. Uma análise nova nasce visível.
    oculta_em timestamptz,
    oculta_por_id integer REFERENCES users(id),
    -- O professor pediu outra: o aluno volta para a fila, com a orientação dele para quem escreve.
    refazer_pedido_em timestamptz,
    refazer_pedido_por_id integer REFERENCES users(id),
    refazer_orientacao text
);
CREATE INDEX ix_student_analyses_aluno ON student_analyses (aluno_id, id DESC);
