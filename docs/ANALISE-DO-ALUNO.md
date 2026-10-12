# A análise do aluno e a liberação do resultado

Toda questão tem assunto, sub-assunto e comentário por alternativa. Com isso, **qualquer conjunto de
questões respondidas sustenta uma análise escrita** de um aluno: o ponto principal, os padrões nos
erros, o que melhorou e os próximos passos. O conjunto lido é o *recorte*. Hoje há dois:

| Recorte | Para quem é escrita | Quem lê | Onde aparece |
| --- | --- | --- | --- |
| Um simulado | Para o aluno, em "você" | O aluno e o professor | No resultado da prova |
| Geral (tudo o que ele respondeu) | Para o professor, sobre o aluno | Só o professor | No acompanhamento do aluno |

Recorte novo (um módulo, uma lista, um período) é outro filtro sobre as mesmas respostas.

## Quem faz o quê

| Quem | Faz |
| --- | --- |
| A plataforma (`AnaliseServico`) | Monta o resumo do recorte, guarda o texto e mostra |
| O Claude do professor, pelo MCP | Lê o resumo e escreve |
| O professor | Pede, libera o resultado, oculta uma análise que não ficou boa |

**A plataforma não chama modelo nenhum.** Não há chave de IA nem custo de token no servidor: quem
escreve é o Claude de um operador, dentro da assinatura dele.

## O resultado do simulado espera o professor

O simulado fecha sozinho, na hora marcada. **O resultado, não:** nota, gabarito, gráficos e análise
só chegam ao aluno quando o professor libera (`exams.resultado_liberado_em`). Até lá, o aluno vê
"aguardando resultado", e o backend recusa por todas as portas: o resultado, o histórico e o
desempenho por assunto dele (`SimuladosServico.resultadoSaiu`). O professor vê a prova entregue a
qualquer momento, como sempre.

"Gere o resultado do Simulado 02", no Claude, é:

1. `listar_analises_pendentes` com o simulado: os alunos que fizeram a prova e ainda não têm
   análise dela, em lotes de 5 (até 10), cada um com o resumo. Avisa quantas questões estão sem
   comentário;
2. `gravar_analises` com o mesmo simulado, até não restar ninguém;
3. `liberar_resultado_simulado`, depois de mostrar ao professor quantos fizeram e quantas análises
   ficaram prontas, com o ok dele.

O que o aluno passa a ver no resultado: a nota e a posição, **a análise dele**, **os gráficos**
(como as respostas se dividem, e o acerto em cada assunto ao lado da média de quem fez), o mapa
questão a questão com o comentário da alternativa marcada, e onde revisar. Os gráficos não dependem
de IA: quem ficou sem análise vê todo o resto.

O professor também libera pelo portal, na página do simulado ("Liberar sem gerar as análises"). O
botão existe para o resultado não ficar preso quando ele não puder usar o Claude. Liberar não se
desfaz. Análise gravada depois da liberação aparece para o aluno na hora.

Os simulados que já tinham fechado quando esta regra entrou (V17) continuam liberados.

## A análise geral, do professor

"Faça a análise geral do João", no Claude, é `listar_analises_pendentes` só com o aluno (tudo o que
ele respondeu, em aula e em simulado, na visão do professor), o texto mostrado na conversa e, com o
ok, `gravar_analises` sem simulado. Fica guardada no acompanhamento do aluno, com as anteriores,
para reconsultar. O aluno não tem por onde lê-la.

O professor chega ao aluno por **Turmas → Desempenho por aluno**, que busca por nome ou e-mail.

## O que vai no resumo

- `por_assunto`: respostas, acertos e nível por assunto e sub-assunto, dentro do recorte.
- `erros_recentes`: até 30 erros. De cada um, onde foi ("Simulado 02, questão 4" ou a linha da
  aula), o que ele marcou, o gabarito e **o comentário que o professor aprovou para a alternativa
  marcada e para a correta**. Sem comentário, quem escreve só sabe que ele errou.
- `no_curso`: o que há no curso dele sobre cada ponto fraco, pelo nome que ele vê na tela.
- `outros_simulados`: como ele foi nas outras provas. Na análise do simulado entram só as de
  resultado já liberado, porque o texto é para o aluno.
- `analise_anterior`, quando já houve uma no mesmo recorte.

A qualidade depende dos comentários por alternativa: sem eles, o texto só repete os gráficos.

## Onde isso fica na regra da aprovação

A regra do projeto é "a IA propõe, o humano aprova, o backend publica". A análise não passa por
`drafts`, mas não chega ao aluno sem um sim do professor:

- **do simulado**: nada aparece antes de `liberar_resultado_simulado`, que o professor aprova na
  conversa (ou no botão do portal). É o mesmo regime de editar questão publicada: preview no chat,
  ok, e vale na hora. Depois de liberado, o professor ainda oculta a análise de um aluno;
- **geral**: nem chega ao aluno.

Quem escreve só recebe números e o que o professor já aprovou (os comentários das alternativas), e
a descrição de `gravar_analises` proíbe explicar a matéria por conta própria. O aluno lê, embaixo do
texto, que ele foi escrito por IA e a partir de quê. Fica registrado em qual Claude cada análise foi
escrita (`escrita_por_id`) e quem liberou o resultado (`resultado_liberado_por_id`).

Nada aqui publica conteúdo do curso. Questão, aula, simulado e material continuam passando por
rascunho e por `drafts.aprovado_por_id`.

## Onde está

- `student_analyses` (V16, V17): uma linha por escrita, com `simulado_id` (nulo na geral). Vale a
  mais recente de cada aluno em cada recorte; as outras são histórico.
- `exams.resultado_liberado_em` e `resultado_liberado_por_id` (V17).
- `analytics/AnaliseServico.java`: o resumo, gravar, liberar, o gráfico por assunto e a busca.
- `/comandos/listar_analises_pendentes`, `/comandos/gravar_analises` e
  `/comandos/liberar_resultado_simulado` (`AnalyticsComandos`).
- `/api/admin/alunos`, `/api/admin/alunos/{aluno}/analise`, `.../analise/visibilidade` e
  `/api/admin/simulados/{simulado}/resultado/liberar` (`DevolutivaPortal`).
- O aluno recebe a análise e o gráfico dentro de `/api/aluno/simulados/{simulado}/resultado`.
- `frontend/components/Analise.tsx` e `GraficosDoSimulado.tsx`.
- `AnaliseDoAlunoTest` trava a espera pelo professor, o resumo, quem lê o quê e a busca.

## Limites conhecidos

- O resultado depende de o professor liberar. Se ele esquecer, os alunos ficam em "aguardando";
  a página do simulado avisa em destaque enquanto não liberar.
- Uma alternativa marcada uma vez pode ter sido chute: a descrição da tool manda tratar como
  padrão só o motivo que se repete em dois erros ou mais.
- Não há recolher resultado nem antecipar o fechamento de um simulado aberto.
