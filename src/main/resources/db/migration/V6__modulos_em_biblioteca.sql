-- Decisão 0011 do cofre: o módulo sai da turma e vai para uma biblioteca. A turma recebe os
-- módulos que o professor atribui, e uma aula pode ser só de algumas turmas.

CREATE TABLE module_classes (
    modulo_id integer NOT NULL REFERENCES modules (id),
    turma_id integer NOT NULL REFERENCES classes (id) ON DELETE CASCADE,
    PRIMARY KEY (modulo_id, turma_id)
);
CREATE INDEX ix_module_classes_turma ON module_classes (turma_id);

-- Cada módulo que existia continua com a turma dele: o aluno não percebe a mudança.
INSERT INTO module_classes (modulo_id, turma_id) SELECT id, turma_id FROM modules;

-- Aula sem linha aqui aparece para toda turma que tem o módulo; com linha, só para as dela.
CREATE TABLE item_classes (
    item_id integer NOT NULL REFERENCES items (id),
    turma_id integer NOT NULL REFERENCES classes (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, turma_id)
);
CREATE INDEX ix_item_classes_turma ON item_classes (turma_id);

-- O nome único por turma (uq_modulo_nome) sai junto com a coluna; quem confere agora é o serviço.
ALTER TABLE modules DROP COLUMN turma_id;
