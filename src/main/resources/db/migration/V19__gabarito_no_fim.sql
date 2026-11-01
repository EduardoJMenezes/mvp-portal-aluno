-- A folha de respostas (V18) saiu: o aluno responde as questões da apostila na plataforma, uma
-- por uma, como na aula comum, e o que muda é só quando a correção aparece — de uma vez, depois
-- da última questão do sub-módulo. A coluna passa a dizer isso.
ALTER TABLE submodules RENAME COLUMN folha_de_respostas TO gabarito_no_fim;
