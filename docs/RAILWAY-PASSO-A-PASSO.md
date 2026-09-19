# Ligar os oito núcleos: o que fazer no Railway

O código já está pronto e no ar — e **inerte**: `PAPEL` nasce `tudo` e
`WEB_CONCURRENCY` nasce `1`, exatamente como o portal roda hoje. O que falta é
virar duas chaves, e este documento é a receita.

Ganho medido: **55 → 239 pedidos por segundo, p50 de 159 ms para 22 ms**
(docs/CARGA.md). Com isso, 600 alunos passam a usar um quarto da capacidade.

**Ordem importa.** O passo 1 tem que terminar antes do passo 2: se o portal
subir com vários processos enquanto ainda serve o `/mcp`, a sessão do seu
conector cai no processo errado e o Claude perde o servidor.

---

## Por que dois serviços e um repositório só

A pergunta é justa — e a resposta não é gosto, é o padrão documentado.

**Isto tem nome: *process types*.** O Twelve-Factor App, que é a referência
que Heroku, Railway e Render seguem, descreve exatamente este arranjo:
"o desenvolvedor pode arquitetar a aplicação para lidar com cargas diversas
atribuindo cada tipo de trabalho a um *process type*… requisições HTTP podem
ser atendidas por um processo `web`, e tarefas longas de fundo por um processo
`worker`". O conjunto de tipos e de quantas cópias de cada um é o que eles
chamam de *process formation*
([12factor.net/concurrency](https://12factor.net/concurrency)). É o mesmo
desenho: `portal` e `mcp` são dois tipos de processo da mesma aplicação.

**E o mesmo documento desaconselha o que parecia mais organizado.** O fator I
diz que "há sempre uma correlação de um para um entre o repositório e a
aplicação" e, textualmente, que "múltiplas aplicações compartilhando o mesmo
código é uma violação do twelve-factor — a solução é extrair o código
compartilhado para bibliotecas"
([12factor.net/codebase](https://12factor.net/codebase)). Dois repositórios
aqui cairiam justamente nisso: o MCP usa os mesmos services, os mesmos modelos
e a mesma regra de publicação do portal. Ou duplicaríamos a regra que não pode
divergir, ou montaríamos uma biblioteca versionada para um projeto de uma
pessoa só.

**A Railway documenta este caminho.** Para repositório com código
compartilhado, a orientação é conectar o mesmo repositório ao projeto e criar
um serviço por componente, cada um com seu *start command* e suas *watch paths*
([docs.railway.com/deployments/monorepo](https://docs.railway.com/deployments/monorepo)).

**E há produto de gente grande fazendo assim.** O `docker-compose` oficial do
Apache Airflow sobe `api-server`, `scheduler`, `worker` e `triggerer` a partir
da **mesma imagem** `apache/airflow`, mudando só o papel de cada um
([airflow.apache.org](https://airflow.apache.org/docs/apache-airflow/stable/howto/docker-compose/index.html)).

**O FastMCP fecha o raciocínio do nosso caso específico.** A documentação de
deployment dele diz, sobre rodar com vários workers: "rode com múltiplos
workers em produção (exige modo stateless)", porque "as sessões ficam na
memória de cada instância, o que cria dificuldade ao escalar horizontalmente".
E explica que a sessão carrega "o canal de volta que pedidos iniciados pelo
servidor, como *elicitation*, usam"
([gofastmcp.com/deployment/http](https://gofastmcp.com/deployment/http)). Ou
seja: as duas saídas possíveis são exatamente as duas que discutimos — separar
o MCP num processo só, ou torná-lo stateless e perder o canal que o
`publicar_rascunho` usa para pedir a aprovação do professor.

Por isso a recomendação é separar. Se um dia o `publicar_rascunho` não
depender mais de *elicitation*, o modo stateless junta tudo num serviço de novo.

---

## Passo 1 — Um serviço só para o MCP

O objetivo é tirar o `/mcp` do serviço do portal, porque é a única peça que não
pode ser duplicada (a sessão do conector mora na memória do processo).

1. No projeto `mvp-portal-aluno`, **New → GitHub Repo**, escolha o mesmo
   repositório. Dê o nome **`mcp`** ao serviço.
2. Em **Settings** do serviço `mcp`:
   * **Healthcheck Path**: `/saude`
     *(não é `/api/saude`: o portal não mora neste processo)*
   * **Start Command**: deixe como está — o Dockerfile já resolve.
3. Em **Variables** do serviço `mcp`, cole no Raw Editor:

   ```bash
   PAPEL=mcp
   DATABASE_URL=${{Postgres.DATABASE_URL}}
   JWT_SECRET=<o mesmo valor do serviço app>
   MCP_OAUTH_GITHUB_CLIENT_ID=<o mesmo do app>
   MCP_OAUTH_GITHUB_CLIENT_SECRET=<o mesmo do app>
   MCP_OAUTH_OPERADORES=<o mesmo do app>
   ```

   O `JWT_SECRET` precisa ser **o mesmo** dos dois lados: é ele que assina a
   sessão, e o MCP confere sessão emitida pelo portal.
4. Em **Settings → Networking → Generate Domain**, gere o domínio do serviço
   `mcp` e anote o endereço (algo como `mcp-production-xxxx.up.railway.app`).
5. Volte a **Variables** do `mcp` e acrescente, agora que o endereço existe:

   ```bash
   MCP_BASE_URL=https://<o domínio que você acabou de gerar>
   ```

6. Espere o deploy e confira: `https://<domínio do mcp>/saude` responde
   `{"status":"ok","papel":"mcp"}`.
7. No claude.ai, **remova o conector antigo e adicione de novo**, agora com
   `https://<domínio do mcp>/mcp`. (Isso você já faz quando uma tool muda de
   descrição — o conector guarda a lista de ferramentas de quando foi criado.)
8. Teste uma tool qualquer pelo chat, por exemplo "liste as turmas". Se
   responder, o passo 1 está fechado.

## Passo 2 — O portal com quatro processos

Só depois que o passo 1 estiver funcionando.

1. Em **Variables** do serviço **`app`**, acrescente:

   ```bash
   PAPEL=portal
   WEB_CONCURRENCY=4
   ```

2. Salve. O Railway redeploy sozinho, leva uns 3 minutos.
3. Confira: `https://app-production-e5b7.up.railway.app/api/saude` responde
   `200`, e `https://app-production-e5b7.up.railway.app/mcp` agora responde
   **404** — é o sinal de que o portal largou o MCP.
4. Entre no portal e navegue um pouco: curso, materiais, simulados.

Se algo sair errado, o retorno é imediato: apague `PAPEL` e `WEB_CONCURRENCY`
do serviço `app` e ele volta a ser exatamente o que era.

**Por que 4 e não 8:** cada processo carrega o app (~200 MB) e abre até 40
conexões no banco. Quatro dão 160 conexões, com folga nos 500 que o Postgres
aceita, e já entregam o salto medido. Oito é possível depois, olhando o
`railway metrics`.

## Passo 3 — CDN na frente (quando tiver domínio próprio)

Este não dá para fazer hoje: o portal está num endereço `.up.railway.app`, e
não se põe Cloudflare na frente do domínio da Railway.

Quando o domínio próprio existir:

1. Aponte o domínio para a Cloudflare (plano grátis serve).
2. Na Railway, **Settings → Networking → Custom Domain**, adicione o domínio e
   crie o CNAME que ele pedir, com a nuvem **laranja** (proxy ligado).
3. Acrescente `CORS_ORIGINS=https://<seu domínio>` nas variáveis do `app`.

Isso tira do nosso servidor os 660 MB de arquivos estáticos que 600 alunos
baixam ao entrar na primeira aula, e o egresso sai da conta.

---

## O que já está feito, e que você não precisa tocar

* **Cache imutável** nos arquivos do portal: quem volta não rebaixa 1,1 MB.
* **ETag no `/api/aluno/conteudo`**: quem volta recebe 304 no lugar de 48,7 KB.
* **Fila do banco** de 20 + 20 conexões, com espera de 5 s — 503 rápido em vez
  de meio minuto pendurado.
* **Trava de login no Postgres**: com quatro processos, cinco tentativas por
  conta continuam sendo cinco, não vinte.
* **Log com o tempo de cada resposta** (método, rota, status, ms), para a
  próxima pergunta de desempenho ser respondida com dado de dia normal.

## Como saber se valeu

Depois do passo 2, em `railway metrics --service app`, o pico de CPU deve
passar de **1,0 vCPU** (o teto de um processo, medido antes) para algo acima
disso quando houver carga. É a confirmação de que os outros núcleos entraram no
jogo.
