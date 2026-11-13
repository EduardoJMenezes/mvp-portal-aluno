-- O curso com matrícula: preço por data (Pix à vista ou cartão parcelado), até quando se vende,
-- os termos que o aluno aceitou e o endereço para entregar o material.

ALTER TABLE planos
    ADD COLUMN matricula_centavos integer CHECK (matricula_centavos >= 500),
    ADD COLUMN matricula_parcelas_max integer NOT NULL DEFAULT 1 CHECK (matricula_parcelas_max BETWEEN 1 AND 12),
    ADD COLUMN a_venda_ate date;

-- Do pagamento único: cada linha vale a partir de uma data (vazio: desde já) até a próxima.
CREATE TABLE plano_precos (
    id serial PRIMARY KEY,
    plano_id integer NOT NULL REFERENCES planos (id) ON DELETE CASCADE,
    a_partir_de date,
    pix_centavos integer CHECK (pix_centavos >= 500),
    -- O total no cartão; a parcela é o total dividido pelas parcelas.
    cartao_centavos integer CHECK (cartao_centavos >= 500),
    cartao_parcelas integer NOT NULL DEFAULT 1 CHECK (cartao_parcelas BETWEEN 1 AND 12),
    CHECK (pix_centavos IS NOT NULL OR cartao_centavos IS NOT NULL)
);
CREATE UNIQUE INDEX uq_plano_precos_data ON plano_precos (plano_id, coalesce(a_partir_de, DATE '1900-01-01'));

-- O pagamento único de antes vira a primeira linha: o mesmo preço no Pix e no cartão.
INSERT INTO plano_precos (plano_id, a_partir_de, pix_centavos, cartao_centavos, cartao_parcelas)
SELECT id, NULL, preco_centavos, preco_centavos, parcelas_max FROM planos WHERE tipo = 'UNICO';

ALTER TABLE pedidos
    ADD COLUMN forma varchar(10) CHECK (forma IN ('MENSAL', 'PIX', 'CARTAO')),
    ADD COLUMN curso_centavos integer,
    ADD COLUMN cartao_parcelas integer,
    ADD COLUMN matricula_centavos integer,
    ADD COLUMN matricula_checkout_id varchar(60) UNIQUE,
    ADD COLUMN matricula_paga_em timestamptz,
    ADD COLUMN rua varchar(200),
    ADD COLUMN complemento varchar(60),
    ADD COLUMN bairro varchar(120),
    ADD COLUMN cidade varchar(120),
    ADD COLUMN uf varchar(2),
    ADD COLUMN termos_versao varchar(40),
    ADD COLUMN termos_aceitos_em timestamptz,
    ADD COLUMN termos_ip varchar(64),
    ADD COLUMN envio1_em timestamptz,
    ADD COLUMN envio2_em timestamptz;
