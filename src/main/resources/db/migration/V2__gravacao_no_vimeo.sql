-- A gravação da aula no Vimeo. Também é a trava contra aviso repetido: o Zoom reenvia o evento
-- quando não recebe 200 a tempo, e só quem preenche esta coluna primeiro sobe o vídeo.
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS gravacao_vimeo_id varchar(40);
