-- O assunto do material (PDF), na mesma forma do vídeo (video_subjects) e da questão
-- (question_subjects): uma tabela de ligação. Hoje a tela põe um assunto por conteúdo, mas a
-- ligação aceita vários de propósito — um mesmo vídeo ou PDF pode passar por mais de um assunto,
-- e quando isso for preciso não se mexe no banco, só na tela.

CREATE TABLE material_subjects (
    id serial PRIMARY KEY,
    material_id integer NOT NULL REFERENCES materials(id),
    assunto_id integer NOT NULL REFERENCES subjects(id),
    subassunto_id integer REFERENCES subtopics(id),
    CONSTRAINT uq_material_assunto UNIQUE (material_id, assunto_id, subassunto_id)
);
CREATE INDEX ix_material_subjects_assunto ON material_subjects (assunto_id);
