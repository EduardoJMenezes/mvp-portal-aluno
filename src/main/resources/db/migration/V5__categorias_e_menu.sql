-- Decisão 0009 do cofre: cada feature funciona sozinha, com uma categoria livre em cada item,
-- e o professor monta o menu do aluno, por turma, com botões "feature + categoria".

ALTER TABLE modules ADD COLUMN IF NOT EXISTS categoria varchar(60);
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS categoria varchar(60);
ALTER TABLE exams ADD COLUMN IF NOT EXISTS categoria varchar(60);
ALTER TABLE materials ADD COLUMN IF NOT EXISTS categoria varchar(60);

-- O menu é trocado inteiro a cada gravação: não tem histórico nem remoção lógica.
CREATE TABLE menu_buttons (
    id serial PRIMARY KEY,
    turma_id integer NOT NULL REFERENCES classes (id) ON DELETE CASCADE,
    rotulo varchar(40) NOT NULL,
    funcionalidade varchar(20) NOT NULL CHECK (funcionalidade IN ('CURSO', 'AULAS', 'SIMULADOS', 'MATERIAIS')),
    categoria varchar(60),
    ordem integer NOT NULL
);
CREATE INDEX ix_menu_buttons_turma ON menu_buttons (turma_id, ordem);
