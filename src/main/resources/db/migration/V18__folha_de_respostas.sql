-- O sub-módulo de questões pode virar folha de respostas: o aluno resolve no papel (a apostila) e
-- lança as letras de uma vez. Até enviar a folha inteira, nenhuma questão dela mostra o gabarito;
-- responder uma por uma, como na aula comum, é recusado.
ALTER TABLE submodules ADD COLUMN folha_de_respostas boolean NOT NULL DEFAULT false;
