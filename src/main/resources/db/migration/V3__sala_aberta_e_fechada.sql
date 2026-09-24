-- Quando a sala do Zoom abriu e fechou de verdade (avisos meeting.started e meeting.ended). O
-- relógio da agenda continua mandando; estas colunas só antecipam o "ao vivo" e o "encerrada".
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS iniciada_em timestamptz;
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS encerrada_em timestamptz;
