# Importador de simulado: o .docx entra pelo chat

Decisões da conversa sobre trazer os simulados que a equipe já monta em Word,
escritas antes do código — como [MODELO-SIMULADO.md](MODELO-SIMULADO.md).

## O problema que o importador resolve

A transcrição pelo chat funciona para o texto, mas tem três limites que não se
resolvem com instrução melhor:

* **As figuras não chegam.** A chamada da ferramenta só carrega texto; o Claude
  vê a figura e não tem como entregá-la. No Simulado 05, metade das questões
  depende de figura (estruturas químicas, esquemas), e na questão 9 as cinco
  alternativas são figuras.
* **É uma cópia feita por um modelo.** Sai boa, mas não idêntica: no Simulado
  05 apareceu uma fórmula com erro de LaTeX e figuras descritas em texto que
  entregavam a resposta (questões 1, 9 e 10).
* **A resolução comentada se perde** — não havia onde guardá-la.

O .docx já traz tudo isso dentro: as figuras como arquivos e os índices e
expoentes como formatação. Ler o arquivo é mais fiel do que redigitá-lo.

## Os dois caminhos continuam

* **Chat** — print, PDF, questão de outra fonte: o Claude transcreve
  (`criar_simulado_rascunho`), como hoje.
* **Importador** — o .docx no padrão da casa: o servidor lê o arquivo.

## Tudo pelo chat, sem a API da Anthropic

A parte que exige julgamento fica com o Claude **na conversa**, dentro da
assinatura. O servidor faz só o trabalho mecânico — e quanto mais ele acerta
sozinho, menos a conversa gasta.

1. O professor pede no chat para importar; a tool `importar_simulado_docx`
   devolve um **link de envio** — uso único, expira em 30 minutos, preso a quem
   pediu.
2. O link abre uma página da plataforma com um campo de arquivo. Quem abre não
   precisa estar logado: o link é a credencial.
3. O servidor lê o .docx, separa as questões pelas regras, converte as figuras
   em formato antigo, confere cada questão e **cria o rascunho** — que nenhum
   aluno vê.
4. O professor avisa no chat ("enviei"). O Claude chama `revisar_importacao`,
   que devolve as questões, os avisos **e as figuras**, e mostra o preview.
5. Ajustes pelo chat. Questão que as regras não fecharam é completada
   **apontando os blocos do documento original** (`completar_questao_importada`),
   sem redigitar. O Claude propõe assunto e sub-assunto de cada questão.
6. Publicar continua exigindo a aprovação no portal — o link para ela sai do
   próprio chat.

O chat não é avisado quando o envio termina: o professor diz que enviou.

## Regras primeiro, o Claude revisa

Medido em três simulados reais (05, 03 e "Camada N"), um protótipo só com regras
separou **60 de 60 questões completas** — enunciado, alternativas A–E, gabarito
e resolução. O terceiro documento pediu dois ajustes: gabarito escrito como
"LETRA B" em vez de "GABARITO: B", e a questão seguinte começando sem que o
gabarito tivesse sido reconhecido.

As regras aceitam as variações vistas e as comuns:

* questão: `01.` ou `1)`, com número em sequência (o primeiro pode ser 91);
* alternativa: `a)` a `e)`, maiúscula ou minúscula, com texto ou só figura, e
  mais de uma na mesma linha;
* gabarito: `GABARITO: D`, `LETRA D`, `Resposta: D`, e uma tabela de gabarito no
  fim do documento;
* resolução: tudo entre o gabarito e a próxima questão — inclusive linhas que
  parecem alternativas ("a) Errada…") ou numeradas ("1. Metanol").

Cada questão passa por uma conferência (número, cinco alternativas, gabarito,
figuras convertidas). O que não fecha vira **aviso no preview**, nunca chute.

## Fidelidade do conteúdo

* **Índice e expoente** do Word viram caractere Unicode (CO₃²⁻, 10⁻¹⁰), que sai
  em pé como em livro; o que não tem forma Unicode vira LaTeX.
* **Equação do editor do Word** (OMML) vira LaTeX — notação nuclear, frações,
  setas com texto.
* **Tabela** vira tabela Markdown.
* **Figura** vira referência no texto, no ponto exato onde estava:
  `![](figura:123)`.
* **Formatos antigos** — EMF, WMF e objetos "Equação 3.0"/MathType, que só
  guardam uma prévia em WMF — são convertidos para PNG no servidor, com o
  LibreOffice. A imagem do servidor cresce algumas centenas de MB; foi a opção
  escolhida para documentos antigos entrarem sem ninguém mexer neles.

## Mudanças no modelo

| onde | mudança |
|---|---|
| `questions` | `resolucao_comentada`: texto formatado, mostrado ao aluno com o gabarito, depois do fechamento |
| `images` | `questao_id` e `parte` (enunciado, alternativa ou resolução): várias figuras por questão |
| `imports` | nova: o link de envio, o .docx original, os blocos lidos, o relatório e o rascunho criado |

A figura da resolução segue a regra do vídeo de resolução: o aluno só vê
depois que o simulado fecha, e só se fez a prova.

## Armazenamento

Sem bucket por enquanto. O arquivo é processado em memória — o disco do
Railway é zerado a cada deploy — e figuras e .docx original ficam no Postgres.
Um simulado como o 05 soma menos de 1 MB. Bucket (S3, R2) quando o volume
crescer de verdade.

## Assuntos

O Claude sugere assunto e sub-assunto por questão na revisão. Antes, uma
proposta de taxonomia de orgânica e físico-química, tirada dos simulados, vai
para aprovação — sem ela, o "Onde revisar" do aluno sai vazio.

## Fora desta etapa

* Tela de importação no portal — fica para o Next, com a API já pronta.
* Documento que é uma imagem só (questão colada como print dentro do Word): não
  há texto para ler; vai pelo caminho do chat.
