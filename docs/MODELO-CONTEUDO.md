# Modelo de conteúdo: módulos, itens e assuntos

Estrutura para organizar aula e questão na plataforma. Escrita antes do
código, como a [especificação](MVP-ESPECIFICACAO.md) foi, e **implementada** em
seguida: modelo, services, tools, REST e portal. O que sobrou de fora está na
seção "Em aberto", no fim.

> **Mudou em 27/09/2026 (decisão 0011 do cofre, migração V6):** o módulo não
> pertence mais a uma turma. Ele mora numa **biblioteca**, e as turmas o
> **recebem** (`module_classes`): Q1, Q2, Q4 e Q5 recebem o mesmo K01, e o que
> muda nele vale para as quatro. Dentro do módulo, uma aula pode ser **só de
> algumas turmas** (`item_classes`). A regra do que o aluno vê: aula sem
> restrição aparece para toda turma que tem o módulo; aula restrita, só para
> as turmas dela; o módulo aparece quando sobra aula visível. Onde este texto
> diz "módulo da turma", leia "módulo que a turma recebe".

> **Mudou em 06/10/2026 (migração V10): a questão entrou na aula.** A linha de
> um sub-módulo é um vídeo, um PDF ou uma **questão do acervo**
> (`items.questao_id`), e o aluno responde ali mesmo. Vale o contrário do que
> este texto diz mais abaixo, em "Acervo": a questão da apostila **vira** uma
> `Questao`, e a resolução em vídeo é da questão (`questions.video_id`), não
> uma linha à parte. As regras:
>
> * **Uma resposta por aluno por linha** (`item_answers`): marcou, valeu. Fica
>   gravada a alternativa marcada, não só o acerto — é dela que vai sair a
>   devolutiva por assunto e sub-assunto.
> * **Gabarito, resolução comentada e vídeo só saem do backend depois da
>   resposta.** No simulado continuam saindo só quando a prova fecha.
> * **A questão tem de A a D; a E é opcional.** Vale para o acervo inteiro.
> * **A mesma questão pode estar em aula e em simulado.** Não é bloqueado: o
>   simulado e a questão mostram onde ela está (`aulas`, `avisos`), e a
>   decisão é do professor.
> * **O que o professor monta no portal já sai publicado** — vídeo, PDF e
>   questão. A proposta nasce, é aprovada por ele e publicada no mesmo ato
>   (`PublicacaoServico.publicarPeloPortal`), então a aprovação humana continua
>   gravada. O que chega pelo Claude segue em rascunho.
> * As linhas só de vídeo que já existiam ("Q04") continuam valendo como estão.

> **Mudou em 06/10/2026 (migração V11): o módulo tem capa.** É o que o aluno
> vê no cartão do módulo, em "Meu curso": um **ícone** de um catálogo fechado
> (`IconeDoModulo`, espelhado em `frontend/lib/icones.ts`) ou uma **foto** que
> o professor envia pelo portal. Com foto, vale a foto; sem as duas, o portal
> escolhe o ícone pelo nome do capítulo. Escolher um ícone tira a foto. A foto
> mora em `modules.foto`, fora da entidade, e só chega a quem alcança o módulo
> (`/api/aluno/modulos/{id}/foto`): professor, sempre; aluno, o da turma dele.
> Pelo Claude vai só o ícone (`criar_modulo`, `editar_modulo`) — arquivo não
> passa pelo chat.
>
> **Mudou em 07/10/2026: a ordem muda de uma vez.** A tela "Montar o curso"
> (`/admin/biblioteca`, em `frontend/components/curso/`) reordena arrastando, e
> manda a fila inteira numa chamada: `PUT /api/admin/biblioteca/ordem` (módulos),
> `/modulos/{id}/submodulos/ordem` e `/submodulos/{id}/itens/ordem`, todas com
> `{"ids": [...]}`. Quem a lista não cita fica onde estava — a tela pode estar
> olhando por uma turma, que não vê tudo —, e a ordem sai renumerada de 1 a n.
> O sub-módulo também ganhou nome editável (`PATCH /api/admin/submodulos/{id}`).
> Pelo Claude nada mudou: `editar_item` e `editar_modulo` seguem com `ordem`.
>
> **Mudou em 07/10/2026 (migração V12): aula assistida.** A plataforma guarda o
> que cada aluno já fez do curso (`item_progress`, pacote `progresso/`), e cada
> tipo de item se conclui do seu jeito:
>
> * **vídeo**: o player do Vimeo avisa a posição de tempos em tempos
>   (`POST /api/aluno/itens/{id}/progresso`) e, ao **cruzar 90%**, o vídeo fica
>   concluído. A posição fica guardada: o aluno continua de onde parou;
> * **PDF**: quando o aluno marca (`PUT .../concluido`) — o portal marca ao
>   abrir o PDF;
> * **questão**: quando ele responde. Não tem linha em `item_progress`: quem
>   diz é `item_answers`, e questão não se marca nem desmarca à mão.
>
> O aluno pode marcar e desmarcar vídeo e PDF. O total de cada aluno é o que a
> turma dele vê **hoje** (publicado, da turma, liberado pela agenda): publicar
> uma aula nova baixa o percentual de todo mundo, de propósito. A árvore do
> aluno (`/api/aluno/conteudo`) leva só `concluido` por item — a posição não,
> para o ETag dela não mudar a cada aviso do player. O professor vê por turma
> (`GET /api/admin/turmas/{turma}/progresso`) e por aluno, item a item
> (`GET /api/admin/alunos/{aluno}/progresso`).
>
> **Mudou em 07/10/2026 (migração V13): a devolutiva.** Duas peças:
>
> * **Desempenho por assunto, de qualquer questão** (`analytics/DevolutivaServico`).
>   "Tudo é questão": a resposta da aula (`item_answers`) e a do simulado
>   (`exam_answers`) viram a mesma `RespostaAvaliada` e entram na mesma conta,
>   organizada pela etiqueta da questão. Fonte nova de questão é só mais um
>   bloco em `respostasDe`. Não há corte fixo de "ponto fraco": cada assunto
>   leva uma nota **ajustada**, que parte do acerto geral de quem respondeu e
>   se afasta dele conforme chegam respostas
>   (`(acertos + 4 × geral) / (respostas + 4)`); é ela que ordena e que define
>   o nível (atenção < 50, em desenvolvimento < 75, bem). O aluno só conta
>   simulado que já fechou; o professor, prova entregue. Em branco em prova
>   entregue é erro. Questão sem assunto fica de fora, e a tela diz quantas.
>   Rotas: `GET /api/aluno/desempenho/assuntos`,
>   `/api/admin/alunos/{aluno}/assuntos` e `/api/admin/turmas/{turma}/devolutiva`
>   (esta com as questões das aulas e quantos marcaram cada letra); pelo
>   Claude, `buscar_desempenho_por_assunto`.
> * **Comentário por alternativa** (`question_options.comentario`): por que o
>   aluno marca aquela letra e onde está o erro. O professor escreve no
>   cadastro da questão (ou o Claude propõe, em rascunho, e ele aprova). O
>   aluno lê o da alternativa que marcou e o da correta — na aula, logo depois
>   de responder; no simulado, com o resultado. Como a resolução, muda mesmo
>   com a prova aberta. E continua valendo: o aluno responde **uma vez**.
>
> **Mudou em 09/10/2026: o Vimeo se navega pela tela.** Em "Montar o curso",
> "Adicionar › Vídeos" abre um off-canvas com o acervo como ele está guardado:
> o professor desce pelas pastas (ano › curso › capítulo), marca o que quer —
> um vídeo, vários, a pasta inteira (a caixa de seleção da própria pasta), de
> pastas diferentes — e grava tudo de uma vez, já publicado, na ordem em que
> marcou. A busca é uma só: acha pastas de qualquer nível pelo nome e vídeos
> do Vimeo inteiro pelo título, cada um com o caminho da pasta em que mora
> (`pasta_id` em `GET /api/admin/vimeo/videos`, lido de `parent_folder`). A
> lista de pastas passou a dizer onde cada uma mora (`pai_id` em
> `GET /api/admin/vimeo/pastas`), e `GET /api/admin/vimeo/pastas/{pasta}/videos`
> devolve a pasta em ordem: pelo número do título quando há ("Q04"), e o resto
> em ordem natural ("Aula 2" antes de "Aula 10"). Vídeo que o aluno não
> conseguiria assistir vem com aviso e fica fora do "marcar todos"; o que já
> está no sub-módulo não entra de novo. A gravação continua sendo a rota de
> sempre (`POST .../submodulos/{sub}/itens`). A importação por faixa de
> números, que distribui uma pasta por vários módulos e gera rascunho,
> continua em "Importar".
>
> **Mudou em 09/10/2026: modal e off-canvas são componentes da casa**
> (`frontend/components/Camadas.tsx`). `<Modal tamanho>` abre no meio da tela;
> `<OffCanvas lado tamanho>` nasce de uma borda (direita, esquerda, baixo,
> cima). Os tamanhos são os mesmos quatro nos dois: pequeno, medio, grande e
> tela. São o `<dialog>` nativo — foco preso, Esc, fundo inerte — e a
> confirmação (`useConfirmar`) já é um `Modal`. Tela nova que precise abrir
> algo por cima usa um dos dois, em vez de desenhar a própria camada. A
> vitrine, com todos os tamanhos e lados, está em `/admin/componentes/`.
>
> **Mudou em 09/10/2026: o carregamento tem um padrão**
> (`frontend/components/Esqueleto.tsx`). Enquanto os dados não chegam, a tela
> mostra blocos cinza-azulados, com um brilho que passa, no formato do que vem.
> `<Carregando forma>` traz os formatos prontos (lista, cartoes, tabela, texto,
> blocos) e `<Esqueleto>` é o bloco solto, para desenhar um formato sob medida,
> como o de "Montar o curso" e o das pastas do Vimeo. O `Estado` recebe `forma`
> ou `esqueleto`. Tela nova não escreve "Carregando…" nem usa `animate-pulse`:
> diz a forma. Com "reduzir movimento" o bloco fica parado. Também está na
> vitrine.
>
> **Mudou em 10/10/2026: dá para ver o vídeo ao montar o curso**
> (`frontend/components/PreviaDoVideo.tsx`). É o vídeo cru, só o player do
> Vimeo, e ele só é carregado quando o professor pede. Em "Montar o curso", o
> ícone de play da linha abre um off-canvas com o player e as setas de
> anterior e próximo dentro do sub-módulo. No painel "Adicionar vídeos", cada
> vídeo tem um botão de play: o player abre preso no alto do painel, com a
> caixa de marcar e as mesmas setas, e a lista continua embaixo. Para isso a
> árvore do professor passou a levar `embed_url` e `duracao_segundos` de cada
> linha de vídeo (`ItemNaArvore`). Esse endereço **não** sai para o aluno:
> `CatalogoServico.listarModulos` devolve a árvore `semPrevia()` a quem não é
> operador, e a tela do aluno continua passando pelo `AcessoServico`.
>
> **Mudou em 10/10/2026: a questão também abre ao lado.** O ícone de
> interrogação da linha faz o mesmo que o play: abre o off-canvas com a questão
> como o professor a confere (`PreviaDaQuestao.tsx`) — enunciado, alternativas
> com o gabarito marcado, resolução comentada e o vídeo de resolução, que só
> carrega se ele pedir. As setas passaram a andar por todas as linhas que têm o
> que ver, vídeo ou questão, na ordem do sub-módulo. O painel "Adicionar
> questão" usa as mesmas peças.
>
> **Mudou em 10/10/2026: o que o professor cria abre por cima da página.** Nas
> telas dele, o formulário deixou de expandir no meio da lista (ou de ficar
> fixo no alto dela) e passou a abrir num `Modal` ou `OffCanvas`: em "Montar o
> curso", a capa, a categoria e as turmas do módulo, copiar entre turmas, novo
> módulo, PDF, questão, aula ao vivo, classificar por assunto e as turmas de
> uma linha; na agenda, o evento; em aulas ao vivo, agendar; em materiais,
> enviar; em turmas, nova turma e matricular aluno; em vendas, o plano.
> Renomear continua no lugar, e o que é uma página inteira (questão, simulado)
> continua página. Duas peças sustentam isso: `useSaida`, para a camada que é
> montada só quando abre, e o recado ao pé da tela (`useRecado`), que passa a
> morar dentro da camada aberta — atrás dela, um erro ficaria no escuro. As
> telas do aluno não mudaram.
>
> **Mudou em 10/10/2026 (migração V14): cada linha do curso mostra e troca o
> assunto.** A regra é uma só: **o assunto é do conteúdo, nunca da linha**. A
> linha aponta para uma questão, um vídeo ou um PDF, e o assunto mora nele — a
> questão em `question_subjects` (é o campo do cadastro dela), o vídeo em
> `video_subjects` e, agora, o PDF em `material_subjects`. "Trocar o assunto da
> linha" troca o do conteúdo, e vale em todo lugar onde ele aparece; não existe
> uma segunda informação para a mesma coisa (`catalogo/AssuntoDasLinhas`).
>
> * **Um assunto por conteúdo, por enquanto.** Classificar passou a
>   **substituir** (antes só acrescentava, e um vídeo acabava com dois). As
>   três tabelas continuam sendo de ligação de propósito: quando um vídeo
>   precisar passar por mais de um assunto, muda a regra em
>   `TaxonomiaServico.classificarVideo` e a tela, não o banco. Por isso a
>   árvore já devolve `assuntos` em lista. O vídeo que já tinha dois continua
>   com os dois até alguém trocar.
> * **Rotas:** `PUT /api/admin/itens/{item}/assunto` (uma linha; vazio tira),
>   `POST /api/admin/submodulos/{id}/assunto` e
>   `POST /api/admin/modulos/{id}/assunto` (de uma vez, com `so_sem_assunto`
>   para não mexer em quem já tem). A árvore do professor traz o assunto de
>   cada linha em três consultas para o curso inteiro.
> * **"Onde revisar" do aluno** passou a trazer, para cada ponto fraco, o que
>   há no curso dele com o endereço da linha (`no_curso`): vídeos e PDFs
>   daquele assunto, as questões que ainda não respondeu e as que errou, que
>   abrem a resolução. Primeiro o que é exatamente do sub-assunto, depois o
>   que é só do assunto. Os demais vídeos do acervo continuam em `videos`.
>   Vídeo de resolução não recebe assunto: ele é da questão.
>
> **Mudou em 10/10/2026: a questão entra na aula por um painel feito para quem
> está com a apostila ao lado.** O off-canvas de "Adicionar › Questão"
> (`curso/AdicionarQuestao`) fica aberto de uma questão para a outra:
>
> * A busca é automática e aceita um trecho do enunciado ou o número
>   (`#46`, que o backend passou a entender em `QuestoesServico.buscar`). Cada
>   resultado mostra só o começo do enunciado, sem figura, e o que distingue a
>   questão; "Ver a questão" abre ali mesmo o enunciado inteiro, as
>   alternativas com o gabarito e a resolução.
> * **O nome da próxima linha anda sozinho**: depois de "Q04" vem "Q05"
>   (`proximoNome`). O campo fica no rodapé e vale tanto para a questão do
>   banco (a rota já aceitava `nome` com `questao_id`) quanto para a nova,
>   que recebe o nome pelo endereço do editor (`&nome=`).
> * No editor, vindo de um sub-módulo, **"Criar e escrever a próxima"** grava,
>   limpa o formulário e avança o nome, mantendo assunto e dificuldade.
>
> **Mudou em 10/10/2026: vídeo se escolhe no explorador, não pelo número.** O
> explorador do Vimeo saiu de "Montar o curso" e virou `ExploradorDoVimeo`, com
> dois modos: `varios` (as linhas de um sub-módulo, como antes) e `um`. O campo
> `CampoDeVideo` usa o segundo: mostra o vídeo escolhido (miniatura, título,
> pasta, duração), com ver, trocar e tirar, e é ele que está no "Vídeo de
> resolução" da questão. Duas coisas valem para os dois modos e existem por
> causa de nome repetido ("Q01" há em toda pasta): a busca feita de dentro de
> uma pasta lista **primeiro o que há nela**, e o modo `um` reabre na pasta do
> último vídeo escolhido. Colar o link ou o número continua possível.

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
| `questoes` | enunciado, alternativas e gabarito — do simulado e, desde a V10, da linha de aula |

Esta é a separação que o modelo atual não faz. Conteúdo de curso é vídeo;
questão com gabarito é instrumento de avaliação. Hoje `Questao` serve aos dois
e por isso não serve bem a nenhum.

*(Superado em 06/10/2026 — ver a nota no topo.)* **A questão da apostila nunca vira uma `Questao` aqui.** Ela mora na apostila;
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
2. **Começar uma turma nova.** Com módulo pertencendo à turma, montar o
   Extensivo 2027 é recriar vinte módulos e centenas de itens. Uma tool que
   duplique a estrutura de outra turma resolve sem mexer no modelo — mas
   alguém precisa lembrar de escrevê-la antes de janeiro.

Decidido desde a primeira versão: **um sub-módulo é de um tipo só** — nada de
PDF no meio dos vídeos.

## Melhoria anotada

**Questão da apostila cadastrada junto do vídeo de resolução**, com o item
ganhando uma questão opcional (`itens.questao_id`). Resolve a classificação dos
vídeos pelo conteúdo, em vez de por módulo. Detalhes em
[MODELO-SIMULADO.md](MODELO-SIMULADO.md#questão-da-apostila-cadastrada-junto-do-vídeo-de-resolução).
