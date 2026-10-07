-- A capa do módulo no cartão do aluno: um ícone do catálogo ou uma foto enviada pelo professor.
-- Sem nenhum dos dois, o portal escolhe o ícone pelo nome do capítulo.
--
-- A foto mora na própria linha, mas fora da entidade: quem lista módulos não carrega imagem.
-- `foto_em` diz que há foto e serve de versão no endereço dela, que pode então ser guardado
-- para sempre pelo navegador.
ALTER TABLE modules
    ADD COLUMN icone varchar(40),
    ADD COLUMN foto bytea,
    ADD COLUMN foto_tipo varchar(40),
    ADD COLUMN foto_em timestamptz;
