-- Vendas: o que se vende (plano), quem comprou (pedido) e qual matrícula veio de qual pedido.

CREATE TABLE planos (
    id serial PRIMARY KEY,
    nome varchar(120) NOT NULL,
    link varchar(60) NOT NULL,
    tipo varchar(10) NOT NULL CHECK (tipo IN ('MENSAL', 'UNICO')),
    preco_centavos integer NOT NULL CHECK (preco_centavos >= 500),
    parcelas_max integer NOT NULL DEFAULT 1 CHECK (parcelas_max BETWEEN 1 AND 12),
    acesso_ate date,
    ativo boolean NOT NULL DEFAULT true,
    criado_em timestamptz NOT NULL DEFAULT now(),
    alterado_em timestamptz
);
-- O link é o endereço público: dois planos não dividem o mesmo.
CREATE UNIQUE INDEX uq_planos_link ON planos (lower(link));

CREATE TABLE plano_turmas (
    plano_id integer NOT NULL REFERENCES planos (id),
    turma_id integer NOT NULL REFERENCES classes (id),
    PRIMARY KEY (plano_id, turma_id)
);

CREATE TABLE pedidos (
    id serial PRIMARY KEY,
    plano_id integer NOT NULL REFERENCES planos (id),
    -- O token vai no endereço de volta do checkout; aqui só o hash, como no link de envio.
    token_hash varchar(64) NOT NULL UNIQUE,
    nome varchar(200) NOT NULL,
    email varchar(200) NOT NULL,
    cpf varchar(11) NOT NULL,
    celular varchar(20),
    -- O Asaas exige endereço para o cartão (antifraude); rua e bairro saem do CEP.
    cep varchar(8),
    numero varchar(10),
    valor_centavos integer NOT NULL,
    status varchar(12) NOT NULL
        CHECK (status IN ('AGUARDANDO', 'PAGO', 'ATRASADO', 'CANCELADO', 'REEMBOLSADO', 'EXPIRADO')),
    asaas_checkout_id varchar(60) UNIQUE,
    asaas_cliente_id varchar(60),
    asaas_assinatura_id varchar(60),
    usuario_id integer REFERENCES users (id),
    -- A conta nasceu com este pedido: só então a página de volta pode definir a senha dela.
    conta_nova boolean NOT NULL DEFAULT false,
    senha_definida boolean NOT NULL DEFAULT false,
    acesso_liberado boolean NOT NULL DEFAULT false,
    -- Até quando vale o acesso: vazio enquanto a assinatura está em dia.
    acesso_ate timestamptz,
    pago_em timestamptz,
    criado_em timestamptz NOT NULL DEFAULT now(),
    alterado_em timestamptz
);
CREATE INDEX ix_pedidos_assinatura ON pedidos (asaas_assinatura_id);
CREATE INDEX ix_pedidos_cliente ON pedidos (asaas_cliente_id);

-- Matrícula dada por um pedido sai quando o pedido perde o acesso; a feita à mão, nunca.
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS pedido_id integer REFERENCES pedidos (id);
