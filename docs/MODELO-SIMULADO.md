# Modelo do simulado: questões, agenda, resultado e ranking

Decisões do debate sobre o simulado, escritas antes do código, como
[MODELO-CONTEUDO.md](MODELO-CONTEUDO.md) foi.

**Estado:** implementado — modelo e migração, services, tools do MCP, API REST
completa e o mínimo do aluno no portal (ver "Ordem de implementação"). As
seções "O que já existia" e "O que não existia" guardam o ponto de partida.

## O que já existia

Questão com enunciado, alternativas A–E, gabarito, dificuldade e vídeo de
resolução; simulado com questões em ordem; tentativa e resposta por aluno; a
correção no backend; estatísticas da turma e desempenho do aluno com
recomendação de vídeos por assunto — pelo MCP. No portal, o aluno lista os
simulados da turma, responde uma questão por vez e vê acertos e gabarito ao
finalizar.

O que **não** existia: agenda, tempo de prova, simulado para mais de uma turma,
ranking, imagem na questão, e a tela de análise do aluno (a recomendação só
aparece para o professor, pelo MCP).

## Decisões

### De onde vêm as questões

O professor manda print ou PDF numa conversa com o Claude, que transcreve e
cria na plataforma. Para cada questão o Claude propõe enunciado, alternativas,
gabarito, **assunto e sub-assunto** — está lendo o enunciado, então sabe do que
se trata — e o vídeo de resolução.

**O vídeo de resolução vem do Vimeo, e quem faz o pedido diz de onde**: "a
resolução está na pasta Simulado 30". O casamento questão ↔ vídeo é pelo
número, o mesmo gesto da importação do curso (`inferir_numero`).

**O simulado e as questões novas dele nascem num rascunho só.** Um simulado de
15 questões é um preview e um ok — não quinze rascunhos de questão mais um de
simulado.

### Imagem na questão

O Claude transcreve **tudo o que dá para replicar em texto**: fórmula, equação,
tabela. Enunciado e alternativas guardam texto formatado (Markdown, com fórmulas
em LaTeX), e o portal desenha.

O que não dá para replicar — figura sem letras nem números que importem — fica
**marcado como imagem pendente**: a transcrição põe `![](figura:pendente)` no
lugar exato da figura, e o professor anexa depois. Enquanto houver imagem
pendente, o simulado não publica.

Uma questão tem quantas figuras precisar — no enunciado, numa alternativa ou na
resolução comentada —, cada uma referenciada no texto onde aparece
(`![](figura:123)`). A da resolução só aparece para o aluno depois do
fechamento, como o vídeo. Quando o simulado já está num .docx, o importador
traz figuras e resoluções sozinho: ver [IMPORTADOR-SIMULADO.md](IMPORTADOR-SIMULADO.md).

A apostila já tira as imagens desnecessárias e mantém só as tabelas e gráficos
que importam para a questão, então a transcrição deve cobrir por volta de 90%
dos casos. Os outros 10% melhoram depois, com o uso real mostrando onde o MCP
erra.

O limite que obriga o anexo manual é concreto: o Claude enxerga o print, mas não
consegue repassar o arquivo para a ferramenta, porque a chamada é texto.

**Enquanto o portal não tem upload** (ver "O portal até o Next"), a imagem entra
por uma sessão do Claude Code: lá o Claude acessa o PDF salvo na máquina,
recorta a figura e envia pelo endpoint REST de anexo, com o mesmo token do MCP.
A trava continua a mesma.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN_DO_MCP" -F arquivo=@figura.png \
  -F parte=ENUNCIADO https://<servidor>/api/admin/questoes/<id>/figuras
```

A figura entra na primeira marca `figura:pendente` da parte (`ENUNCIADO`,
`ALTERNATIVA` ou `RESOLUCAO`); sem marca, no fim do enunciado ou da resolução.

Aceita PNG, JPEG, WEBP e GIF até 2 MB — o tipo é lido dos bytes, e SVG fica de
fora. O token do MCP abre a API com o canal MCP: anexa, mas não aprova nem
descarta rascunho.

### Agenda e tempo de prova

* **Uma janela só**: abre em data e hora, fecha em data e hora.
* **Tempo de prova**: contado a partir de quando o aluno começa. O prazo dele é
  o que vier primeiro — início + duração, ou o fechamento do simulado.
* **Uma ou mais turmas**, escolhidas no cadastro. O simulado deixa de pertencer
  a uma turma só.
* **Estourou o prazo, entrega automática** com o que foi respondido; o resto
  fica em branco, e em branco conta como erro.

A entrega automática não precisa de job agendado: a tentativa cujo prazo passou
é tratada como entregue na próxima vez que alguém a consulta.

* **Horário de Brasília** (`America/Sao_Paulo`) para digitar e mostrar agenda e
  prazos; o banco guarda em UTC.
* **Depois que o simulado abre, questões e gabarito travam.** Dá para estender o
  fechamento, mas não para trocar questão com gente fazendo a prova. Anular
  questão depois do resultado fica para quando aparecer o caso real.

### Resultado

**Sai quando o simulado fecha — não quando cada aluno termina.** Se saísse ao
terminar, quem fez primeiro repassaria o gabarito para quem ainda não fez, e o
ranking mudaria com a prova aberta. O backend recusa mostrar resultado antes do
fechamento; não é uma tela escondendo.

O aluno vê **só o próprio resultado e a posição dele no ranking**:

* nota e, por questão, o que marcou, o gabarito ou "em branco", e o vídeo de
  resolução;
* posição, ex.: "12º de 48";
* a análise: os sub-assuntos em que foi pior e os vídeos que explicam cada um —
  os de outra turma aparecem como "não incluído no seu plano", sem nada do
  Vimeo (ver [MODELO-CONTEUDO.md](MODELO-CONTEUDO.md#conteúdo-bloqueado)).

**O ranking completo é só do professor.**

### Ranking

* **Um ranking só por simulado**, com os participantes de todas as turmas dele —
  o aluno vê "12º de 48", não a posição dentro da turma.
* Empate fica na mesma posição, e a posição seguinte pula: 1º, 2º, 2º, 4º.
* Quem não fez fica fora. "Fez" é ter começado a prova.

### Editar e remover

Questão e simulado seguem a pegada do curso: alteração direta, com preview no
chat e o ok do professor antes. Publicar continua exigindo aprovação gravada no
backend.

### Tudo que o MCP faz, o portal também vai fazer

O MCP não pode ser o único caminho. Se ele não estiver atendendo, o professor
precisa conseguir criar e editar pelo portal — curso, questões, imagens,
simulados, agenda e turmas.

É também para isso que serve o rascunho: o professor avança o quanto der pelo
Claude e termina de ajustar na plataforma antes de publicar. **Rascunho tem que
ser editável no portal**, não só aprovável ou descartável.

**Mas não neste frontend.** O portal atual será reescrito em Next.js, e é lá que
esse CRUD vai morar. Até lá o portal fica só com visualização, e o trabalho
agora é deixar **a API REST completa no backend**: toda operação que o MCP faz
tem um endpoint equivalente, pronto para o front novo consumir. Os dois chamam
os mesmos services, como já é a regra da casa, e o `openapi.json` que o FastAPI
gera serve para o Next gerar um cliente tipado.

### Quem usa o quê

**O MCP é do professor**, para montar curso e simulado. **O aluno faz a prova só
pelo portal** — não há previsão de aluno usando o MCP.

### O portal até o Next

O lado do professor fica só com visualização. O lado do aluno ganha o mínimo
para as regras novas não quebrarem a prova, que hoje mostra o resultado assim
que o aluno finaliza e não tem cronômetro:

* cronômetro durante a prova;
* ao entregar, "prova entregue, o resultado sai dia X às Y";
* depois do fechamento, para ver: nota, posição no ranking, gabarito e vídeo de
  resolução por questão, e a análise com os vídeos recomendados;
* enunciado e alternativas desenhados como texto formatado, com tabela e
  fórmula, e a imagem anexada.

## Classificação dos vídeos do curso

Quem está usando o MCP diz quais módulos e sub-módulos pertencem a cada assunto
("K01 e K02 são Atomística, K03 é Estequiometria"), e `classificar_videos`
aplica em lote. A etiqueta fica no vídeo e vale para todas as turmas.

## O modelo, em mudanças

| onde | mudança |
|---|---|
| `questoes` | enunciado e alternativas em texto formatado; `imagem_pendente`; `imagem_id` |
| `imagens` | nova: o arquivo anexado, no próprio Postgres |
| `simulados` | `abre_em`, `fecha_em`, `duracao_minutos`; sai `turma_id` |
| `simulado_turmas` | nova: `simulado_id`, `turma_id` |
| `tentativas` | `prazo_em` e `entregue_automaticamente` |
| `respostas` | sem mudança: questão sem resposta é "em branco" |

A imagem fica no Postgres por ser o mais simples para a POC; um bucket é o
caminho quando o volume crescer.

### O banco de produção agora tem conteúdo real

Até aqui, mudar o modelo custava um `seed --reset`. **Não custa mais**: o curso
montado com os vídeos do Vimeo está lá, e o reset apagaria tudo. Mudança de
schema passa a ser migração leve — `ALTER TABLE ... IF NOT EXISTS`, idempotente —,
aplicada antes de o servidor subir, a cada deploy.

## Ordem de implementação

1. Modelo e migração leve.
2. Services: agenda, prazo e entrega automática, resultado após o fechamento,
   ranking, trava depois de aberto, imagem, publicação barrada por pendência,
   acesso ao vídeo de resolução.
3. Tools do MCP: montar simulado com as questões num rascunho só e a pasta de
   resolução do Vimeo; editar e remover questão e simulado; ranking.
4. API REST completa, espelhando o MCP, para o front novo.
5. Portal: o mínimo do lado do aluno descrito acima.
6. Deploy com a migração, e conferência em produção.

## Melhorias anotadas para depois

### Questão da apostila cadastrada junto do vídeo de resolução

Hoje o item de "Questões da apostila" é só o vídeo, chamado "Q04". A ideia é
transcrever também a questão, do PDF da apostila, e pendurá-la no item
(`itens.questao_id`, opcional).

* **Classificação pelo conteúdo.** Lendo o enunciado, o Claude sugere assunto e
  sub-assunto por questão — em vez do rótulo por módulo, que é o que dá para
  fazer com um vídeo chamado "Q04".
* **O aluno vê a questão junto com a resolução.**
* **Acervo reaproveitável.** A questão chega classificada e com vídeo de
  resolução, pronta para entrar num simulado se o professor quiser — com a
  ressalva de que o aluno pode já ter visto a resolução no curso.

Não desfaz a decisão de que o curso é CRUD de vídeo: o item continua sendo o
vídeo e só ganha uma questão opcional.

### Imagens que não dão para transcrever

Resolvido pelos importadores: o .docx traz a figura como arquivo, e o print
enviado pelo link tem a figura recortada de dentro dele. Ver
[IMPORTADOR-SIMULADO.md](IMPORTADOR-SIMULADO.md).
