-- Decisão 0012 do cofre: a agenda é uma lista de eventos por turma. O evento pode ligar a uma
-- aula, um módulo, uma aula ao vivo ou um simulado; a aula e o módulo ligados ficam escondidos
-- para aquelas turmas até a hora do evento.

CREATE TABLE agenda_events (
    id serial PRIMARY KEY,
    titulo varchar(200) NOT NULL,
    descricao text,
    inicio_em timestamptz NOT NULL,
    fim_em timestamptz,
    categoria varchar(60),
    modulo_id integer REFERENCES modules (id),
    item_id integer REFERENCES items (id),
    aula_id integer REFERENCES live_classes (id),
    simulado_id integer REFERENCES exams (id),
    criado_por_id integer REFERENCES users (id),
    criado_em timestamptz NOT NULL DEFAULT now(),
    alterado_por_id integer REFERENCES users (id),
    alterado_em timestamptz,
    removido_em timestamptz,
    CONSTRAINT ck_evento_uma_ligacao CHECK (num_nonnulls(modulo_id, item_id, aula_id, simulado_id) <= 1),
    CONSTRAINT ck_evento_fim CHECK (fim_em IS NULL OR fim_em >= inicio_em)
);
CREATE INDEX ix_agenda_events_inicio ON agenda_events (inicio_em) WHERE removido_em IS NULL;

CREATE TABLE agenda_event_classes (
    evento_id integer NOT NULL REFERENCES agenda_events (id),
    turma_id integer NOT NULL REFERENCES classes (id) ON DELETE CASCADE,
    PRIMARY KEY (evento_id, turma_id)
);
CREATE INDEX ix_agenda_event_classes_turma ON agenda_event_classes (turma_id);

-- A agenda também vira botão do menu do aluno.
ALTER TABLE menu_buttons DROP CONSTRAINT menu_buttons_funcionalidade_check;
ALTER TABLE menu_buttons ADD CONSTRAINT menu_buttons_funcionalidade_check
    CHECK (funcionalidade IN ('CURSO', 'AULAS', 'SIMULADOS', 'MATERIAIS', 'AGENDA'));
