# Modelo de conteúdo: módulos, itens e assuntos

Estrutura para organizar aula e questão na plataforma. Escrita antes do
código, como a [especificação](MVP-ESPECIFICACAO.md) foi, e **implementada** em
seguida: modelo, services, tools, REST e portal. O que sobrou de fora está na
seção "Em aberto", no fim.

## O que está errado hoje

A unidade de conteúdo da plataforma é a **questão**:

```
Turma ──< TurmaQuestao >── Questão ──── Vídeo
             │
             └── Capítulo
```

Três consequências que já apareceram na prática:

* **Vídeo só existe pendurado numa questão.** Não há caminho para exibir uma
  videoaula. Metade do acervo real — a pasta `AULAS EXTENSIVO ONLINE`, com um
  vídeo longo por capítulo — não tem onde entrar.
* **`Capitulo` tem duas colunas** (`id`, `nome`) e nasce implicitamente, numa
  única linha de `vimeo_importacao.py`, quando alguém importa uma pasta. Foi
  assim que "K03 - Estequiometria" passou a conviver com "Estequiometria".
* **`Capitulo` é global e único por nome**, mas o acervo prova que capítulo é
  por ano: `K03` é Estequiometria em 2026 e Tabela Periódica em 2025.

A §12 do MVP já previa que a taxonomia era provisória. Isto aqui é a evolução
que ela deixou em aberto.

## A estrutura

```
Turma "Extensivo 2026"
 └── Módulo  "K01 - Introdução à química orgânica"     ordem 1
      ├── Sub-módulo "Aulas"                  tipo VIDEO   ordem 1
      │    └── Item "Aula 1 — cadeias carbônicas"        ordem 1  → Vídeo
      └── Sub-módulo "Questões da apostila"   tipo VIDEO   ordem 2
           ├── Item "Q04"                                 ordem 1  → Vídeo
           └── Item "Q52"                                 ordem 2  → Vídeo
```

Dois níveis, e param aqui. A tentação de generalizar para uma árvore recursiva
(nó que contém nó) resolve qualquer hierarquia futura no papel e cobra caro em
toda consulta, toda ordenação e — pior — na publicação, que é a regra que
sustenta a POC: "publiquei o módulo" passaria a ser uma pergunta sobre netos e
bisnetos. Módulo › sub-módulo › item cobre o que existe e continua sendo um
SELECT com dois JOIN.

**Aulas e questões da apostila são os dois listas de vídeo.** O que as separa é
editorial (o nome que o professor deu), não estrutural. Por isso o mesmo `tipo`
serve às duas, e o `tipo` existe para o dia em que entrar `TEXTO` ou `PDF` —
não para distinguir aula de questão.

### Organização — pertence à turma

| tabela | colunas |
|---|---|
| `modulos` | `id`, `turma_id`, `nome`, `ordem`, `criado_em`, `alterado_por_id`, `alterado_em`, `removido_em` |
| `submodulos` | `id`, `modulo_id`, `nome`, `tipo`, `ordem`, `alterado_por_id`, `alterado_em`, `removido_em` |
| `itens` | `id`, `submodulo_id`, `nome`, `ordem`, `video_id`, `status`, `rascunho_id`, `alterado_por_id`, `alterado_em`, `removido_em` |

`tipo` é vocabulário fechado (`VIDEO` hoje; `TEXTO`, `PDF` depois), como
`Papel` e `Status` já são em `models.py`.

`itens.nome` nasce do título do vídeo no Vimeo ("Q04 — Estequiometria com
pureza") e é editável. Substitui o `TurmaQuestao.numero`, que hoje acumula dois
papéis incompatíveis: identidade na apostila e posição na lista. Aqui `nome`
carrega a identidade e `ordem` carrega a posição — dá para exibir a Q52 antes
da Q04 sem renumerar nada.

### Acervo — global, reaproveitável entre anos

| tabela | papel |
|---|---|
| `videos` | espelho do Vimeo, como já é hoje |
| `questoes` | enunciado, alternativas e gabarito — **só para simulado** |

Esta é a separação que o modelo atual não faz. Conteúdo de curso é vídeo;
questão com gabarito é instrumento de avaliação. Hoje `Questao` serve aos dois
e por isso não serve bem a nenhum.

**A questão da apostila nunca vira uma `Questao` aqui.** Ela mora na apostila;
o que a plataforma guarda é o vídeo da resolução, como item de sub-módulo. O
código atual já admitia isso sem querer — a importação cria questões com o
enunciado `f"Questão {numero} da apostila — resolução em vídeo"`, sem
alternativa nem gabarito. Era um vídeo disfarçado, para caber no modelo. Aqui
ele deixa de precisar do disfarce.

`questoes.video_id` continua existindo: é o vídeo de resolução daquela questão
do simulado.

### Taxonomia — global

| tabela | colunas |
|---|---|
| `assuntos` | `id`, `nome` |
| `subassuntos` | `id`, `assunto_id`, `nome` |
| `videos_assuntos` | `video_id`, `assunto_id`, `subassunto_id?` |
| `questoes_assuntos` | `questao_id`, `assunto_id`, `subassunto_id?` |

Substitui `Classificacao`, que hoje guarda tópico e subtópico como **strings
livres**, só na questão, sem cadastro — o que faz "Estequiometria" e
"estequiometria" serem coisas diferentes para o banco.

`subassunto_id` opcional permite classificar grosso ("este vídeo é de
Estequiometria") ou fino ("...› Pureza e rendimento") sem tabelas separadas.
Como o vínculo é N:N, classificar é opcional: sem linha, sem classificação.

**A classificação mora no acervo, não na organização.** O assunto é
propriedade do conteúdo: se o mesmo vídeo aparece em 2026 e 2027, ensina a
mesma coisa nos dois. Classificando em `videos`, cataloga-se uma vez; em
`itens`, seria uma vez por ano.

**O nome do assunto nunca carrega numeração de capítulo.** "K01" é a posição
na apostila de *uma* turma, e as apostilas mudam: no acervo real, K03 é
Estequiometria em 2026 e Tabela Periódica em 2025. Um assunto chamado
"K01 - Introdução à química orgânica" deixaria de ser global e obrigaria a criar
um assunto por turma — exatamente o que a etiqueta existe para evitar.

```
MÓDULO (da turma)                        ASSUNTO (global)
"K01 - Introdução à química orgânica"    "Química Orgânica" › "Isomeria"
"K03 - Estequiometria"        (2026)     "Estequiometria"   › "Pureza e rendimento"
"K03 - Tabela Periódica"      (2025)     "Tabela Periódica" › "Propriedades periódicas"
```

O módulo é o endereço, preso à turma. O assunto é a etiqueta, e atravessa
turmas e anos. São eixos independentes: um módulo contém vários assuntos, e o
mesmo assunto aparece em vários módulos.

### Avaliação — sem mudança

`simulados`, `simulado_questoes`, `tentativas`, `respostas` ficam como estão.

## O ciclo que isto fecha

```
aluno erra no simulado
   → resposta tem questão
      → questão tem sub-assunto "Pureza e rendimento"
         → vídeos com esse sub-assunto
            → "assista estes três"
```

Três dos quatro passos **já existem**: `analytics.py` calcula `erros_por_topico`
por aluno e `maior_dificuldade` por simulado. O que falta é a última seta, que
hoje é impossível porque vídeo não tem assunto.

## Conteúdo bloqueado

Quando a recomendação encontra um vídeo que não está no curso do aluno, ele
aparece **bloqueado** — o aluno vê que existe, não assiste. No futuro, compra.

Isso torna a segregação da §11 ternária:

| situação | o aluno |
|---|---|
| item publicado, em turma onde está matriculado | assiste |
| vídeo de outra turma, mesmo assunto de um erro dele | vê que existe |
| qualquer outro | não fica sabendo |

### Bloqueado não busca nada no Vimeo

O card diz **"não incluído no seu plano"** — neutro, sem nomear o curso de
onde o vídeo veio, para não expor a grade alheia. Quando existir venda, isso
é reaberto: aí o aluno precisa saber o que comprar.

O item bloqueado sai do backend com o **nome, e só**. Sem `embed_url`, sem
thumbnail, sem duração. No portal ele é o nome sobre um véu borrado — blur de
CSS, não imagem processada.

A simplicidade aqui é a própria segurança. `videos.embed_url` guarda a URL
**como o Vimeo devolve**, com o hash de privacidade: é ela que faz um vídeo
unlisted tocar. Serializar o objeto completo e esconder o player no frontend
transformaria o bloqueio em decoração, com o devtools liberando o acervo
inteiro. Não havendo nada no payload, não há o que vazar — e nenhum service
precisa lembrar de escolher entre duas formas de responder.

Quando existir compra, a vitrine vai precisar convencer, e aí vale reabrir
quanto mostrar (thumbnail, duração, prévia). Aí o custo se justifica; agora,
não.

### Um só lugar decide

Todo acesso passa por uma função única:

```python
def pode_assistir(db, ident, video) -> bool: ...
```

Hoje ela responde olhando `Matricula`. Quando existir pagamento, ganha mais uma
fonte — sem caçar `if matriculado` espalhado pelos services. A **unidade de
venda** (turma inteira? módulo? vídeo avulso?) fica em aberto de propósito; o
que não pode ficar em aberto é o número de lugares que precisarão mudar quando
ela for decidida.

## Publicação

**O status vive no item, e só nele.** Módulo e sub-módulo não têm `status`:
aparecem para o aluno quando têm pelo menos um item publicado, e somem quando
não têm. É o que evita o estado contraditório que trava qualquer um na hora de
implementar — módulo em rascunho com item publicado dentro, o aluno vê ou não?

Para liberar um K01 de vinte e um vídeos sem vinte e uma aprovações, existe a
tool de publicar em lote. O rascunho de importação já funciona assim hoje: ele
**é** o lote, e `publicar_rascunho` libera tudo que nasceu dele de uma vez.

### Edição em conteúdo publicado vai ao ar na hora

Sem rascunho no meio, mudar o nome de um item publicado muda para o aluno
imediatamente, e remover tira da tela imediatamente (logicamente — `removido_em`
preenchido, nada apagado). É justamente por isso que o preview no chat não é
opcional.

## Como a escrita acontece

Criar conteúdo continua nascendo como rascunho, com aprovação humana gravada
em `drafts.aprovado_por_id`. **Editar e remover são diretos** — rascunho de
edição exigiria guardar o "antes" e o "depois" e resolver conflito entre dois
rascunhos tocando o mesmo módulo, e isso não se paga para trocar duas aulas de
lugar.

Em troca, antes de chamar a tool o modelo **sempre** mostra no chat como vai
ficar — o antes, o depois e quantos itens publicados são afetados — e só chama
depois do ok do professor. A regra está na descrição de cada tool e nas
instruções do servidor, e um teste quebra se ela sair.

A primeira versão confirmava por formulário do próprio cliente (elicitation).
Não sobreviveu ao teste real: o app do Claude responde a esse formulário
sozinho, sem mostrá-lo a ninguém, então a trava bloqueava toda alteração sem
proteger coisa alguma.

Vale dizer o que se perde, para ninguém se surpreender depois: no publicar, a
garantia está no backend, que recusa publicar sem aprovação gravada. Na edição
direta a proteção mora no comportamento do modelo, que é a camada que o
`server.py` chama de "bom comportamento, útil e insuficiente por si só". É uma
escolha consciente do professor, compensada por duas coisas:

* **Rastro na linha.** `alterado_por_id` e `alterado_em` em tudo que se edita.
  Duas colunas, no lugar de uma tabela de auditoria que a §20 deixou fora.
* **Remoção é sempre lógica.** `removido_em` preenchido, nunca `DELETE`. Vale
  para todas as entidades de conteúdo.

### O preço do soft delete, que precisa estar escrito

Duas armadilhas conhecidas, e as duas mordem em silêncio:

1. **Toda consulta precisa filtrar `removido_em IS NULL`.** Esquecer em um
  lugar faz conteúdo removido reaparecer para o aluno. O filtro tem que nascer
  no service, num helper único — nunca repetido em cada `select`.
2. **Unicidade deixa de ser trivial.** `Capitulo.nome` era `unique`; com
  remoção lógica, um módulo "K01" removido impediria criar outro "K01". O
  índice passa a ser parcial (`WHERE removido_em IS NULL`).

## O que sai de cena

| hoje | vira |
|---|---|
| `Capitulo` | `Modulo` (por turma, com ordem) |
| `TurmaQuestao` | `Item` |
| `Classificacao` | `assuntos` + `subassuntos` + vínculos |

As três são o que a §12 chamou de provisório.

O projeto não tem migrações — o schema vem de `create_all`. Trocar isso custa
`python -m app.seed --reset`, ou seja, o banco de demonstração recriado do
zero. Quanto mais conteúdo real entrar na estrutura velha, mais caro fica.

## O que acontece com as 16 tools

| tool | destino |
|---|---|
| `listar_capitulos` | vira `listar_modulos`, por turma, com a árvore |
| `buscar_questoes` | passa a olhar só o acervo de simulado |
| `importar_pasta_vimeo_como_rascunho` | passa a criar módulo + sub-módulo + itens |
| `criar_questao_rascunho`, `criar_simulado_rascunho` | seguem, agora sem ambiguidade sobre o que é conteúdo |
| `listar_turmas`, `listar_*_vimeo`, `simular_importacao_vimeo`, `listar_rascunhos`, `detalhar_rascunho`, `listar_simulados`, `buscar_estatisticas_simulado`, `publicar_rascunho` | sem mudança |
| `buscar_desempenho_aluno` | ganha a recomendação de vídeos por assunto |

**Novas**, para o CRUD que hoje não existe em lugar nenhum do sistema (nem MCP,
nem REST, nem tela): criar/editar/reordenar/remover módulo, sub-módulo e item;
CRUD de assunto e sub-assunto; classificar vídeo e questão.

Editar e remover não se encaixam na regra "a IA propõe, o humano aprova"
como ela está escrita — ela foi desenhada olhando conteúdo *entrar*. Antes de
implementar, decidir se essas operações nascem como rascunho (minha
recomendação) ou são diretas.

## Quem diz o que vai onde

Não é a IA que infere: **o professor dita, por faixas**, e as tools montam.

> "da questão 1 até a 14 é módulo K01, sub-módulo Questões da apostila;
>  15, 18, 22 e 25 são do K02"

A faixa casa com o **número lido do título do vídeo** (Q04, Q52) — o que
`nomes_vimeo.py` já extrai, com grau de confiança. Não é a posição na lista.
Vídeo cujo título não tem número legível a tool pergunta, nunca chuta.

O assunto entra no mesmo gesto, quando o professor quiser: "Q01 a Q03 são
cadeias carbônicas, Q04 a Q08 nomenclatura". Sendo opcional, o que ele não
etiquetar simplesmente não aparece nas recomendações.

## Escopo

O portal entra junto. `AlunoConteudo.tsx` renderiza turma › capítulo ›
questões; trocar o modelo derruba a tela do aluno e as de admin, e é por elas
que a demonstração passa.

O banco será recriado (`seed --reset`): o projeto não tem migrações, e o que
está lá hoje é semente e teste.

## Em aberto

1. **Unidade de venda**, quando houver pagamento.
3. **Um sub-módulo pode ter item de mais de um tipo** (um PDF no meio dos
   vídeos), ou o tipo do sub-módulo manda em todos?
5. **Começar uma turma nova.** Com módulo pertencendo à turma, montar o
   Extensivo 2027 é recriar vinte módulos e centenas de itens. Uma tool que
   duplique a estrutura de outra turma resolve sem mexer no modelo — mas
   alguém precisa lembrar de escrevê-la antes de janeiro.
