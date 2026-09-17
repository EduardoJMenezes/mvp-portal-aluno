# Aulas ao vivo pelo Zoom

Planejamento, escrito antes do código, como o resto desta POC. O pedido é
simples de dizer: **o professor agenda a aula no portal, diz quem pode entrar
(turma inteira ou aluno a aluno), e no horário marcado o aluno entra pelo
portal.** O que vem abaixo é como isso encaixa no Zoom, onde o Zoom impõe
regras que a gente não escolhe, e em que ordem construir.

Fonte: documentação oficial do Zoom, consultada em 17/09/2026. Os links estão
no fim.

## Antes de tudo: quantos alunos cabem numa sala

Esta é a primeira decisão, e ela é de plano, não de código:

| plano / licença | cabem numa reunião |
|---|---|
| Basic (grátis) e **Pro** | **100** |
| Business | 300 |
| Enterprise | 500 |
| Complemento *Large Meeting* | 500 ou 1.000 (até 5.000 nas faixas maiores) |
| **Zoom Webinar** | centenas de milhares, em modo só-assistir |

Com 500 a 600 alunos, **o plano Pro não serve** — a aula lota em 100 e o aluno
101 recebe "a reunião está cheia". Dois caminhos:

* **Reunião com o complemento Large Meeting (1.000).** Todo mundo entra como
  participante comum. Dá interação, dá bagunça: 600 pessoas que podem abrir
  microfone e câmera.
* **Webinar.** O aluno entra como *attendee*: sem microfone, sem câmera, sem
  compartilhar tela, e sem ver a lista dos outros. Perguntas pelo Q&A. Traz
  registro e relatório de presença prontos.

**Recomendo o Webinar** para aula expositiva de turma grande: é o produto
desenhado para isso, e resolve de graça o problema de moderação que 600
microfones criam. A API das duas coisas é quase a mesma (`/meetings` vira
`/webinars`), então **o código sai preparado para as duas** e a escolha vira um
campo da aula. Preço atual dos complementos: confirmar com a Zoom, porque muda.

## Como as peças se ligam

```
Portal (professor)  ──agenda──►  nosso backend  ──API──►  Zoom (cria a sala)
                                      │
Portal (aluno)  ──"Entrar"──►  nosso backend  ──API──►  Zoom (inscreve o aluno)
                                      │                   devolve link pessoal
                                      ▼
                              redireciona o aluno
                                      ▲
Zoom  ──webhook──►  nosso backend  (começou, terminou, fulano entrou/saiu)
```

Três coisas que valem escrever, porque decidem o resto:

1. **Quem manda em quem pode entrar é o nosso banco, não o Zoom.** O Zoom só
   hospeda a sala. A regra de acesso é a mesma dos materiais (§11): aula
   publicada, endereçada à turma do aluno ou a ele mesmo. O link nunca aparece
   numa listagem — ele é gerado no clique, para aquela pessoa.
2. **A credencial é de servidor, não do professor.** App do tipo
   *Server-to-Server OAuth* na conta Zoom da escola: o backend troca
   `account_id` + `client_id` + `client_secret` por um token de 1 hora e
   nenhum humano precisa autorizar nada. (O tipo JWT, que muito tutorial antigo
   ensina, foi descontinuado pelo Zoom.)
3. **O aluno sai do portal para entrar na aula.** É uma decisão, e está
   argumentada mais abaixo.

## O que o Zoom cobra em troca

Os limites que moldam o desenho — não são detalhe de implementação:

| limite | o que ele obriga |
|---|---|
| **100 criações/atualizações de reunião por dia, por usuário** | Aula semanal vira **uma** reunião recorrente (tipo 8), não 40 reuniões. E editar aula não pode chamar a API a cada tecla. |
| **3 inscrições por dia para o mesmo inscrito na mesma reunião** | O link pessoal do aluno é gerado **uma vez** e guardado no nosso banco. Nunca se pede de novo. |
| Inscrição em lote (`batch_registrants`): **3 chamadas por dia** | Inútil para 600 alunos. Esqueça o lote: a inscrição é uma por aluno, na hora do clique. |
| Cota geral (plano Pro): ~20 req/s nas rotas médias | Inscrever no clique espalha as chamadas pelos minutos que antecedem a aula, em vez de 600 de uma vez. |
| `start_url` do professor **expira em 2 horas** | Nunca guardar o link de iniciar. O botão "Iniciar aula" busca um fresco no `GET /meetings/{id}` na hora. |
| Webhook precisa de resposta em **3 segundos** | O endpoint grava o evento e responde; o trabalho pesado, se houver, fica para depois. |

## Chamadas que a gente vai usar

| quando | chamada |
|---|---|
| a cada hora, em memória | `POST https://zoom.us/oauth/token` (`grant_type=account_credentials`, Basic com client id/secret) |
| professor agenda | `POST /users/{host}/meetings` (ou `/webinars`) — tipo 2 (data marcada) ou 8 (recorrente) |
| professor edita | `PATCH /meetings/{id}` |
| professor cancela | `DELETE /meetings/{id}` |
| professor clica em "Iniciar" | `GET /meetings/{id}` → `start_url` novo |
| aluno clica em "Entrar", primeira vez | `POST /meetings/{id}/registrants` → `join_url` pessoal |
| aula acabou (fase 2) | webhooks `meeting.started`, `meeting.ended`, `meeting.participant_joined`, `meeting.participant_left` |
| gravação pronta (fase 3) | webhook `recording.completed` |

Escopos do app: `meeting:write:admin`, `meeting:read:admin` (e os equivalentes
de `webinar:` se for webinar), `report:read:admin` para presença,
`recording:read:admin` na fase 3.

Variáveis de ambiente novas: `ZOOM_ACCOUNT_ID`, `ZOOM_CLIENT_ID`,
`ZOOM_CLIENT_SECRET`, `ZOOM_WEBHOOK_SECRET`, `ZOOM_HOST` (o e-mail da conta que
hospeda). Sem elas, o backend cai num **Zoom de mentira em memória** — o mesmo
truque do acervo de demonstração do Vimeo, que faz a POC rodar inteira e os
testes não dependerem de rede.

## O modelo, que é o dos materiais outra vez

A pergunta "quem alcança isto" já foi respondida uma vez nesta POC. Repete-se a
forma:

| tabela | o que guarda |
|---|---|
| `live_classes` | título, descrição, `inicio_em` (UTC), duração, fuso, status (RASCUNHO/PUBLICADO), tipo (REUNIAO/WEBINAR), `zoom_meeting_id`, `join_url` genérico, se grava, quem criou, `removido_em` |
| `live_class_classes` | aula → turma (a turma inteira entra) |
| `live_class_students` | aula → aluno (fulano entra, e mais ninguém) |
| `live_class_attendance` | aula → aluno: `registrant_id`, link pessoal, entrou em, saiu em, minutos |

`start_url` **não** tem coluna: expira em duas horas, então é sempre buscado na
hora. O link pessoal do aluno tem coluna porque o Zoom recusa gerá-lo de novo.

Migração idempotente no fim de `app/migracoes.py`, como manda a casa.

## O que acontece na tela

**Professor** (`/admin/aulas`): formulário com título, data, hora, duração,
turmas e alunos avulsos, "gravar na nuvem" e o tipo. Publicar cria a sala no
Zoom. A lista mostra as próximas aulas com um botão **Iniciar** — que busca o
`start_url` fresco e abre o Zoom. Editar hora e duração dá `PATCH`; cancelar dá
`DELETE` e some do portal do aluno.

**Aluno** (`/aulas`): as aulas que o alcançam, em ordem de horário, com o
estado em letras grandes:

* *Faltam 2 dias* — só a data, sem botão;
* *Começa em 12 minutos* — botão **Entrar** ligado (abre 15 min antes);
* *Ao vivo agora* — botão ligado, e o webhook `meeting.started` é quem acende;
* *Encerrada* — e, quando existir, o link da gravação.

O botão não leva a um link guardado em `href`: ele chama
`POST /api/aluno/aulas/{id}/entrar`, que confere o acesso e a janela de horário,
inscreve o aluno no Zoom na primeira vez, guarda o link pessoal e responde com
o endereço. Link que não passa pelo backend é link que vaza numa listagem.

## Por que o aluno sai do portal (e quando eu mudaria de ideia)

Existe o **Meeting SDK Web**, que embute a sala numa página nossa. Estudei, e
não recomendo para esta plataforma:

* Ele quer **isolamento de origem** (`Cross-Origin-Opener-Policy: same-origin`
  e `Cross-Origin-Embedder-Policy: require-corp`) para ter galeria, 720p e
  fundo virtual. Esses dois cabeçalhos **quebram o player do Vimeo** que o
  portal embute hoje — e a saída (`credentialless`) não existe no iOS, que é
  metade dos nossos tablets.
* A *Component View*, a que permite embutir bonitinho, é declaradamente para
  desktop: "não para ambientes móveis". Nossos alunos estão no iPad.
* É WebAssembly pesado numa página que já carrega pdf.js.

Mandar para o app do Zoom dá melhor vídeo no tablet, entra no modo picture-in-
picture, e não custa uma linha de CSP. **Mudaria de ideia** se um dia a
exigência for "o aluno não pode sair do portal de jeito nenhum" — e aí a página
da aula vira uma rota isolada, com os cabeçalhos dela, sem Vimeo junto.

## Sobre o link vazar — o que dá e o que não dá

Vale ser honesto aqui, como foi com o download da apostila:

* **Dá para amarrar o link a uma pessoa.** Com inscrição ligada, cada aluno tem
  um `join_url` só dele. Se aparecer gente estranha, o relatório do Zoom diz
  qual inscrição foi usada — e dá para negar aquele inscrito.
* **Dá para fechar a porta na hora certa.** O botão só existe na janela da
  aula, e o link pessoal não é reaproveitável por quem não tem sessão no portal
  para pedi-lo.
* **Não dá para impedir que um aluno mande o link dele no grupo.** Impediria
  "só usuários autenticados no Zoom", que exigiria conta Zoom para os 600 —
  inviável. O que sobra é rastro, e rastro resolve na prática: o link é
  nominal, e quem o espalha aparece.
* **Senha na sala, sim; sala de espera, não.** Admitir 600 pessoas na mão é
  impossível. A senha vai embutida no link.

## Fases

1. **Agendar e entrar** — credencial, cliente do Zoom, modelo, rotas de
   professor e de aluno, as duas telas, e o Zoom de mentira para os testes.
   É o que atende ao pedido inteiro.
2. **Presença** — webhook com validação de assinatura (HMAC SHA-256 sobre
   `v0:timestamp:corpo`) e o desafio de validação da URL; "ao vivo agora" no
   portal; quem entrou, quando e por quantos minutos, na tela do professor.
3. **Gravação** — `recording.completed` traz o arquivo; a aula ganha o vídeo,
   e o professor decide se ele vira item do curso no Vimeo.
4. **Pelo chat** — tool MCP `agendar_aula`, com a regra de sempre: propõe
   rascunho, o professor aprova, o backend publica.

Fase 1 é o que eu começaria amanhã. As outras três são incrementos que não
mexem no que a 1 entrega.

## O que eu preciso que você decida

1. **Plano do Zoom.** Hoje é qual? Para 600 alunos, é Large Meeting 1.000 ou
   Webinar? (Recomendo Webinar.)
2. **Uma conta hospeda todas as aulas** (a sua), ou cada professor tem a sua?
   Muda quem é o `host` na chamada e se precisa de `alternative_hosts`.
3. **Gravar na nuvem** por padrão? E a gravação vira aula gravada no curso?
4. **Janela de entrada**: 15 minutos antes está bom? E até quando depois do
   fim?

Com essas quatro respostas, a fase 1 sai sem mais perguntas.

## Fontes

* [Server-to-Server OAuth](https://developers.zoom.us/docs/internal-apps/s2s-oauth/)
* [Meetings API](https://developers.zoom.us/docs/api/meetings/)
* [Limites de uso da API](https://developers.zoom.us/docs/api/rate-limits/)
* [Webhooks: validação e assinatura](https://developers.zoom.us/docs/api/webhooks/)
* [Meeting SDK para Web](https://developers.zoom.us/docs/meeting-sdk/web/) e
  [SharedArrayBuffer](https://developers.zoom.us/docs/meeting-sdk/web/sharedarraybuffer/)
* [Limite de participantes por plano](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0068002)
* [Reunião x Webinar](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0062404)
