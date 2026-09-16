# Materiais em PDF, com marcação do aluno

A apostila é o outro lado do curso: o vídeo explica, o material é onde o aluno
resolve. Hoje ele imprime ou abre num leitor qualquer, e o que ele riscou fica
fora da plataforma. Esta aba traz isso para dentro — o professor publica o PDF
para quem ele quiser, e o aluno lê e risca ali mesmo, com o que ele escreveu
salvando sozinho na conta dele.

A regra da POC vale aqui também: **material só chega ao aluno depois que um
humano publica** (§6). O que muda é que quem envia o arquivo já é o professor,
no portal — não há proposta de IA no meio.

## O que a aba entrega

* **Professor:** envia o PDF, decide quem vê (a turma inteira ou um aluno
  específico), publica, tira do ar e remove.
* **Aluno:** abre no portal, risca com a caneta no tablet ou com o mouse no
  computador, e continua de onde parou em qualquer aparelho.
* **Ninguém baixa o arquivo.** Não existe botão de baixar nem de imprimir.

## As decisões, e por quê

### O arquivo mora no Postgres, não num serviço de objetos

A biblioteca cresce menos de 1 GB por ano: cinco apostilas de uns 40 MB (a
maior tem 323 páginas) e vários arquivos de poucas páginas. Nesse tamanho, um
bucket de objetos seria mais uma peça para manter, mais uma credencial para
guardar e mais um backup para lembrar — sem ganho nenhum.

O detalhe que faz isso funcionar com 323 páginas é a coluna guardada como
`EXTERNAL`: sem compressão, o Postgres devolve **um pedaço** do arquivo com
`substring`, sem ler o resto. O leitor pede uma faixa de bytes, o backend
confere a sessão e responde só aquela fatia (HTTP 206). Abrir na página 180 não
baixa as 179 anteriores. PDF já vem comprimido por dentro, então desligar a
compressão do banco não custa espaço.

Quando trocar por armazenamento de objetos (R2, S3): quando a biblioteca passar
de dezenas de GB, ou quando o tráfego de saída pesar na conta. A troca mexe só
em quem lê e grava os bytes; acesso, anotação e leitor não mudam.

### O leitor é nosso; o pdf.js só desenha a página

O pdf.js tem um editor de anotações embutido, e ele não serve aqui: ele grava
as marcações **dentro** do PDF, e ao reabrir elas viram desenho fixo, que não dá
mais para editar nem apagar. Guardar o traço como dado, para continuar editando
depois, é
[assunto ainda em aberto](https://github.com/mozilla/pdf.js/discussions/18962)
no projeto.

Então o pdf.js faz o que faz melhor — desenhar a página — e a marcação fica numa
camada nossa por cima, em SVG. Isso dá, sem depender do que o projeto deles
ainda não decidiu:

* **caneta com pressão**, porque o evento de ponteiro traz a força do stylus;
* **rejeição de palma**: quando aparece uma caneta, o dedo rola e dá zoom em vez
  de riscar;
* **mouse e mesa digitalizadora** pelo mesmo caminho, sem código separado;
* **borracha que apaga o traço inteiro**, não pixel — é o que a pessoa espera de
  caneta, e o que mantém o dado pequeno;
* **salvar só a página que mudou**.

Um PDF de 323 páginas só roda liso no tablet se o leitor desenhar as páginas
perto da tela e descartar as distantes. É a mesma razão de guardar a marcação
por página.

### A marcação é privada, e por página

Cada anotação pertence a um aluno e a uma página. O backend não devolve a de
outra pessoa — nem para o professor. Se um dia o professor precisar ver o
caderno do aluno, isso é decisão dele e dos alunos, e vira uma escolha explícita,
não um efeito colateral.

### Acesso: a turma inteira, ou uma pessoa

Material publicado alcança quem estiver em uma das duas listas: as turmas
escolhidas ou os alunos escolhidos, um a um. A lista de alunos é onde uma compra
futura vai escrever — vender um material avulso é criar uma linha ali.

### Sem download: o que dá e o que não dá

Dá para dificultar, e é honesto dizer que **não existe garantia**: o que a tela
mostra, alguém fotografa. O que a plataforma faz:

* leitor próprio, sem a barra do navegador com baixar e imprimir;
* o arquivo só sai para quem tem sessão e acesso àquele material, conferido a
  cada faixa de bytes;
* o endereço do arquivo não é um link que se repassa: sem o cookie da sessão,
  ele responde 401;
* **imprimir não sai**: `Ctrl+P` não abre a caixa, e pelo menu do navegador a
  folha sai com um recado no lugar do material. Isso fecha a cópia fácil — o
  "imprimir para PDF", que geraria a apostila inteira, limpa, em dois cliques.

**Print de tela não dá para bloquear**, e é honesto dizer isso em vez de vender
o contrário: não existe API de navegador para impedir captura, nem no iPad nem
no Android, porque o print é do sistema e a página nem fica sabendo. Bloqueio de
verdade só dentro de um aplicativo instalado, e mesmo assim: no Android o
sistema recusa a captura, no iOS nem para app nativo existe. E sempre sobra a
foto com outro celular.

O passo seguinte, se o material virar produto, é entregar página por página com
marca d'água gravada pelo servidor — aí o vazamento tem dono. A marca d'água com
nome e e-mail do aluno está desenhada, mas **depende do "sim" do professor**,
porque ela aparece para o aluno.

## Modelo de dados

| tabela | o que guarda |
|---|---|
| `materials` | título, tipo, tamanho, situação (`RASCUNHO`/`PUBLICADO`), quem criou, e o PDF numa coluna `EXTERNAL` carregada só quando alguém lê o arquivo |
| `material_classes` | material × turma — acesso da turma inteira |
| `material_students` | material × aluno — acesso individual (é onde a venda futura escreve) |
| `material_annotations` | material × aluno × página → os traços daquela página, em JSON |

Remoção é lógica (`removido_em`), como no resto do sistema: material removido
some da tela do aluno e as anotações continuam guardadas.

## API

Professor (`/api/admin/materiais`):

| rota | o que faz |
|---|---|
| `GET /` | lista com situação, tamanho e quem alcança |
| `POST /` | envia o PDF e cria em rascunho |
| `GET /{id}` | detalhe, com as turmas e os alunos |
| `PATCH /{id}` | título, situação (publicar/tirar do ar), turmas e alunos |
| `DELETE /{id}` | remoção lógica |

Aluno (`/api/aluno/materiais`):

| rota | o que faz |
|---|---|
| `GET /` | os materiais que alcançam quem está pedindo |
| `GET /{id}/arquivo` | o PDF, aceitando `Range` (206 com a fatia pedida) |
| `GET /{id}/anotacoes` | as páginas que ele já riscou |
| `PUT /{id}/anotacoes/{pagina}` | grava a página (é o que o salvamento automático chama) |

## Formato da anotação

Coordenadas relativas ao tamanho da página (0 a 1), para zoom e rotação não
mexerem no dado:

```json
{
  "v": 1,
  "tracos": [
    {"t": "caneta", "cor": "#111827", "larg": 0.004,
     "p": [[0.12, 0.34, 0.6], [0.13, 0.35, 0.8]]},
    {"t": "marcatexto", "cor": "#fde047", "larg": 0.02, "p": [[0.2, 0.5, 1]]},
    {"t": "texto", "cor": "#111827", "x": 0.4, "y": 0.6, "tam": 0.02,
     "txt": "revisar"}
  ]
}
```

Cada ponto é `[x, y, pressão]`. O salvamento automático dispara cerca de 1,5 s
depois do último traço, ao virar de página e ao sair da tela, e manda só as
páginas que mudaram. Limite de 200 KB por página, o que dá muita tinta.

## Fases

1. **Aba Materiais** — envio, acesso por turma ou aluno, publicação, leitor com
   caneta, marca-texto, texto, borracha, cores, espessura, desfazer e refazer, e
   salvamento automático.
2. **Dentro do curso** — o mesmo material aparecendo como item do sub-módulo, ao
   lado dos vídeos.
3. **Venda** — acesso individual pago, entrega por página com marca d'água.

### Registro de leitura, para quando fizer falta

Guardar quem abriu qual material, quando e quantas páginas viu. Serve para duas
coisas: o professor saber quem nem tocou na apostila, e o padrão de quem está
fotografando o material inteiro aparecer — 323 páginas percorridas em dois
minutos não é leitura. Uma linha por abertura, com material, aluno, página e
horário, e a agregação feita na consulta. Não bloqueia nada; só torna visível.

## Fora de escopo por enquanto

Funcionar sem internet no tablet; professor ver o que o aluno riscou;
acompanhar quem leu o quê; anotação compartilhada entre alunos; impressão.
