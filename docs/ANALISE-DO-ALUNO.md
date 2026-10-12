# A análise do aluno

O retrato de cada aluno em texto: o ponto principal agora, os padrões nos erros, o que melhorou e
os próximos passos. Junta as questões das aulas e os simulados encerrados, e se renova quando ele
responde mais. O aluno lê no alto de **Meu desempenho**; o professor lê a mesma no acompanhamento
do aluno.

## Quem faz o quê

| Quem | Faz |
| --- | --- |
| A plataforma (`AnaliseServico`) | Decide quem está na fila, monta o resumo de cada aluno, guarda o texto e mostra |
| O Claude do professor, pelo MCP | Lê o resumo e escreve a análise |
| O professor | Lê a análise de qualquer aluno, oculta ou pede outra |

**A plataforma não chama modelo nenhum.** Não há chave de IA nem custo de token no servidor: quem
escreve é o Claude de um operador, dentro da assinatura dele, por duas tools:

- `listar_analises_pendentes` entrega os próximos alunos da fila (5 por vez, até 10), cada um com o
  resumo;
- `gravar_analises` guarda os textos (até 10 por chamada).

Uma tarefa agendada no Claude do professor chama as duas uma vez por dia, em lotes, até a fila
acabar ou chegar a 100 alunos. O professor também pode pedir na conversa ("gere as análises", "faz
a análise do João").

## A fila

Entra o aluno matriculado em alguma turma que tem **ao menos 5 respostas** e:

- ainda não tem análise; ou
- respondeu **5 questões novas** desde a última; ou
- teve um **simulado encerrado** desde a última (com qualquer número de questões); ou
- o professor **pediu outra**.

Sai primeiro quem o professor pediu, depois quem tem mais novidade. A conta da fila usa duas
consultas para a turma inteira (`movimentos`); o resumo completo só é montado para quem sai dela.

## O que vai no resumo

Só o que o aluno já pode ver — é a devolutiva como ele a vê (`DevolutivaServico.comoOAlunoVe`), então
**simulado aberto não entra**: a análise não pode contar o resultado antes da hora.

- `por_assunto`: respostas, acertos e nível por assunto e sub-assunto, com o que chegou depois da
  última análise (`novas_respostas`, `novos_acertos`). É daí que sai "o que melhorou".
- `erros_recentes`: os 20 erros mais recentes. De cada um, onde foi ("Simulado 02, questão 4" ou a
  linha da aula), o que ele marcou, o gabarito e **o comentário que o professor aprovou para a
  alternativa marcada e para a correta**. Sem comentário, quem escreve só sabe que ele errou.
- `no_curso`: o que há no curso dele sobre cada ponto fraco, pelo nome que ele vê na tela.
- `analise_anterior` e, quando o professor pediu outra, `orientacao_do_professor`.

A qualidade da análise depende dos comentários por alternativa: sem eles ela só repete o que o
desempenho por assunto já mostra.

## A exceção à regra da aprovação

A regra do projeto é "a IA propõe, o humano aprova, o backend publica". A análise é a exceção
escrita: vai ao aluno sem um professor aprovar uma por uma, porque sai todo dia e para a turma
inteira. O que compensa:

- quem escreve só recebe material que o professor já aprovou (os comentários das alternativas) e
  números; a descrição de `gravar_analises` proíbe explicar a matéria por conta própria;
- o aluno lê, embaixo do texto, que ele foi escrito por IA e a partir de quê;
- o professor vê a análise de qualquer aluno e pode **ocultar** (o aluno deixa de ver) ou **pedir
  outra**, com uma orientação para quem escreve; pedir outra também oculta a atual;
- só operador escreve, e só pelo MCP: não há rota do portal que grave análise, e fica registrado
  em qual Claude ela foi escrita (`escrita_por_id`).

Nada aqui publica conteúdo do curso. Questão, aula, simulado e material continuam passando por
rascunho e por `drafts.aprovado_por_id`.

## Onde está

- `student_analyses` (V16): uma linha por escrita. Vale a mais recente de cada aluno; as outras são
  histórico.
- `analytics/AnaliseServico.java`: a fila, o resumo, gravar, ler, ocultar e pedir outra.
- `/comandos/listar_analises_pendentes` e `/comandos/gravar_analises` (`AnalyticsComandos`).
- `/api/aluno/analise`, `/api/admin/alunos/{aluno}/analise`, `.../analise/visibilidade` e
  `.../analise/refazer` (`DevolutivaPortal`).
- `frontend/components/Analise.tsx`.
- `AnaliseDoAlunoTest` trava a fila, o resumo, quem lê o quê e o que o professor pode fazer.

## Limites conhecidos

- Depende do Claude de um operador rodar. A tarefa agendada do aplicativo só roda com ele aberto;
  parou de rodar, as análises param de se renovar (as que existem continuam na tela).
- O aluno espera até a rodada seguinte; a tela avisa quando há respostas que a análise ainda não
  considera.
- Uma alternativa marcada uma vez pode ter sido chute: a descrição da tool manda tratar como
  padrão só o motivo que se repete em dois erros ou mais.
