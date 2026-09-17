# Aulas ao vivo: as opções, e a que eu escolheria

Planejamento escrito antes do código, como o resto desta POC. O pedido:
**o professor agenda a aula no portal, diz quem entra (turma inteira ou aluno a
aluno), o aluno entra pelo portal no horário — e a aula fica gravada, de
preferência caindo sozinha no lugar certo da plataforma.**

Com um número que muda tudo: **no máximo 100 pessoas ao vivo**. Nem todo mundo
assiste ao vivo; a maioria vê depois.

Fontes oficiais consultadas em 17/09/2026, listadas no fim.

## O bloqueio que existia sumiu

A primeira versão deste documento travava num ponto: reunião do Zoom no plano
**Pro cabe 100 pessoas**, e a turma tem 600. A saída era complemento *Large
Meeting* ou Webinar — dinheiro.

Com no máximo 100 ao vivo, **o Pro serve exatamente**. O blocker era do tamanho
errado, não do desenho.

Fica o alerta honesto: 100 é parede, não elástico. O aluno 101 ouve "a reunião
está cheia". Se um dia a aula bombar, a saída está no fim deste documento e não
obriga a refazer nada.

## O que realmente importa aqui não é a sala

Se a maioria assiste depois, **o produto é a gravação** — a aula ao vivo é o
evento que a produz. E a gravação chegar sozinha na plataforma é um **cano**,
não uma plataforma:

```
sala ao vivo ──grava──► arquivo ──► Vimeo (onde o curso já mora) ──► item do módulo
```

Esse cano é **o mesmo** qualquer que seja a sala, desde que ela entregue um
arquivo com endereço. Zoom entrega. Cloudflare entrega. YouTube **não** entrega
(não há download da sua própria transmissão pela API). Isso já elimina uma das
suas ideias, e é o critério que organiza o resto.

## As quatro opções

| | ao vivo | quem pode assistir | gravação | custo | trabalho novo |
|---|---|---|---|---|---|
| **Zoom Pro** | até 100, com microfone e câmera | link nominal, portão nosso | nuvem do Zoom, webhook avisa | o Pro que você já paga | integração + cano |
| **Transmissão (Cloudflare Stream)** | ilimitado, só assistir | **link assinado por aluno**, expira | automática, vira arquivo | **US$ 12** por aula de 2 h com 100 pessoas | integração + cano + OBS |
| **YouTube ao vivo** | ilimitado, só assistir | só "não listado": quem tem o link, vê | automática, mas **presa lá** | zero | integração + cano manual |
| **Fazer o nosso** | o que aguentarmos | total | nossa | servidor + banda | um produto inteiro |

Três observações que a tabela não cabe:

**Cloudflare Stream** cobra US$ 1 por 1.000 minutos entregues. Ao vivo para 100
pessoas por 2 h dá 12.000 minutos — **US$ 12 por aula**, aceitável. Mas a
gravação assistida depois por 600 alunos dá 72.000 minutos: **US$ 72 por aula**,
todo mês, para sempre. É caro no lugar errado — e é exatamente por isso que o
curso já está no Vimeo, onde assistir é de graça dentro do plano.

**YouTube** é grátis e some no critério do cofre: "não listado" é segredo de
link, sem trava por domínio, e a gravação fica no YouTube — não dá para trazer
o arquivo pela API para o Vimeo. Depois de tanto cuidado com a apostila que não
baixa, guardar a aula num link que qualquer um abre seria incoerente.

**Fazer o nosso**: 100 pessoas assistindo a 2,5 Mbps são **250 Mbps
sustentados** e uns 110 GB por aula de 2 h. Isso é trabalho de CDN. E o
servidor que mediu 60 pedidos por segundo ontem à noite (docs/CARGA.md) não tem
nada que fazer transcodificando vídeo. Um SFU (LiveKit, Jitsi) é um produto,
não um item de backlog.

## A escolha

**Zoom Pro para a sala + gravação automática no Vimeo.** Porque:

* o número fecha: 100 ao vivo é o teto do Pro e o seu teto;
* não entra fornecedor novo nem conta nova — o Zoom você já tem, o Vimeo já
  está integrado nesta POC, com token e tudo;
* o aluno pode **falar**: perguntar no microfone, abrir a câmera na hora da
  dúvida. Transmissão não faz isso, e aula de cursinho vive disso;
* o professor não precisa aprender OBS;
* o único código realmente novo é o cano da gravação — que você precisaria
  construir de qualquer jeito, em qualquer das quatro opções.

**Eu mudaria de ideia se:** passarem de 100 ao vivo com frequência (aí o Zoom
vira estúdio e transmite por RTMP para a Cloudflare, e o portal troca o botão
"Entrar" por um player — o resto continua igual); ou se a interação não valer
nada e a prioridade for o cofre (aí Cloudflare com link assinado por aluno, que
é a única opção aqui com controle de verdade).

## O cano da gravação, em detalhe

É a parte que resolve "cair sozinha no lugar certo", e ela é bonita porque
**nenhum byte passa pelo nosso servidor**:

1. A aula termina. O Zoom grava na nuvem e dispara `recording.completed`, com
   `download_url` e um `download_token` de 24 horas.
2. Nosso backend confere a assinatura do webhook, acha a aula pelo id da
   reunião e monta o endereço do arquivo com o token na query.
3. Chama o Vimeo no modo **pull**: "busque o vídeo neste endereço". O Vimeo
   baixa do Zoom direto, servidor a servidor. Railway não vê o arquivo.
4. O vídeo pronto entra como item do sub-módulo que o professor escolheu **na
   hora de agendar a aula** — e a aula, no portal do aluno, passa a mostrar
   "assistir à gravação".
5. Com o vídeo no Vimeo, a gravação sai da nuvem do Zoom (o Pro dá pouco
   espaço, e ele não pode entupir).

Sobre a regra da casa — *a IA propõe, o humano aprova, o backend publica*: aqui
não há IA propondo nada. O professor deu a aula e escolheu o destino quando a
agendou; publicar é consequência do que ele já decidiu. Ainda assim o
formulário terá **"revisar antes de publicar"**, para a aula que ele preferir
olhar antes de soltar.

## O que fica de pé do plano anterior

Tudo o que não era sobre o tamanho da sala:

* **Credencial**: app *Server-to-Server OAuth* na conta Zoom (o tipo JWT que os
  tutoriais antigos ensinam foi descontinuado). Token de 1 h, guardado em
  memória.
* **Modelo**: `live_classes`, `live_class_classes`, `live_class_students`,
  `live_class_attendance` — a mesma forma dos materiais, que já responde "quem
  alcança isto". Mais duas colunas agora: destino da gravação (sub-módulo) e se
  publica sozinha.
* **Limites do Zoom que moldam o desenho**: 100 criações de reunião por dia
  (aula semanal vira **uma** reunião recorrente, não 40); o link pessoal do
  aluno só pode ser gerado 3 vezes por dia (então é gerado no clique e
  guardado); o `start_url` do professor expira em 2 h (então nunca é guardado —
  o botão "Iniciar" busca um fresco).
* **O aluno sai do portal para entrar na aula.** Embutir o Meeting SDK exige
  isolamento de origem (`COOP`/`COEP`), que quebra o player do Vimeo do portal
  e não tem saída no iOS dos tablets. Para **assistir à gravação**, aí sim ele
  fica no portal: é o player do Vimeo de sempre.
* **Sobre o link vazar**: dá para amarrar o link a uma pessoa e fechar a porta
  fora do horário; não dá para impedir que um aluno mande o link dele no grupo.
  O que sobra é rastro nominal — e é o mesmo acordo que já fizemos com a
  apostila.

## Fases

1. **Agendar, entrar, gravar** — credencial, cliente do Zoom, modelo, rotas,
   as duas telas, e o **cano da gravação até o Vimeo**. É o pedido inteiro.
2. **Presença** — webhook de entrada e saída: quem assistiu ao vivo, quanto
   tempo, na tela do professor. E "ao vivo agora" no portal do aluno.
3. **Pelo chat** — tool MCP `agendar_aula`, com rascunho e aprovação.

Fase 1 é o que eu começaria. A 2 é barata depois que o webhook já existe.

## O que eu preciso que você decida

1. **Zoom Pro, hoje, já é seu?** Se sim, a fase 1 não custa assinatura nova.
2. **Uma conta hospeda todas as aulas** (a sua) ou cada professor a sua?
3. **A gravação publica sozinha** no sub-módulo escolhido, ou espera sua
   revisão por padrão?
4. **Janela de entrada**: 15 minutos antes está bom? E até quando depois?
5. **Espaço no Vimeo**: cada aula de 2 h chega a uns 2 GB. Confirmar o limite
   do seu plano antes de a primeira aula subir.

## Fontes

* [Server-to-Server OAuth](https://developers.zoom.us/docs/internal-apps/s2s-oauth/) ·
  [Meetings API](https://developers.zoom.us/docs/api/meetings/) ·
  [Limites de uso](https://developers.zoom.us/docs/api/rate-limits/) ·
  [Webhooks](https://developers.zoom.us/docs/api/webhooks/)
* [Limite de participantes por plano](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0068002)
* [Meeting SDK para Web](https://developers.zoom.us/docs/meeting-sdk/web/) e
  [SharedArrayBuffer](https://developers.zoom.us/docs/meeting-sdk/web/sharedarraybuffer/)
* [Cloudflare Stream: preços](https://developers.cloudflare.com/stream/pricing/) e
  [links assinados](https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/)
* [YouTube: arquivo das transmissões](https://support.google.com/youtube/answer/6247592)
* [Vimeo: upload por pull](https://developer.vimeo.com/api/upload/videos) ·
  [Live API exige Enterprise](https://help.vimeo.com/hc/en-us/articles/12427830091921-Live-API-access)
