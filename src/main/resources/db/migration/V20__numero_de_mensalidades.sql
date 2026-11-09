-- Assinatura mensal com fim: quantas cobranças ela faz (ex.: 12, o ano do curso). Vazio é até cancelar.
ALTER TABLE planos ADD COLUMN mensalidades integer CHECK (mensalidades BETWEEN 2 AND 24);
