-- PDF na aula (decisão 0013): a aula ao vivo e a linha do módulo apontam para um material.
-- Quem vê a aula abre o PDF; a linha do módulo passa a ser vídeo, PDF ou os dois.

ALTER TABLE live_classes
    ADD COLUMN material_id integer REFERENCES materials(id),
    ADD COLUMN material_no_dia boolean NOT NULL DEFAULT false;

ALTER TABLE items
    ALTER COLUMN video_id DROP NOT NULL,
    ADD COLUMN material_id integer REFERENCES materials(id),
    ADD CONSTRAINT ck_items_video_ou_pdf CHECK (video_id IS NOT NULL OR material_id IS NOT NULL);
