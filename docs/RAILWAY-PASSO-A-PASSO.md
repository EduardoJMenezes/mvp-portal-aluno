# Ligar os oito núcleos: o que fazer no Railway

O código já está no ar e **inerte**: sem nada configurado, o portal roda
exatamente como hoje. O que falta são dois serviços bem nomeados e dois
comandos de partida — este documento é a receita, clique a clique.

Ganho medido: **55 → 239 pedidos por segundo, p50 de 159 ms para 22 ms**
(docs/CARGA.md). Com isso, 600 alunos passam a usar um quarto da capacidade.

**Tempo:** uns 20 minutos, a maior parte esperando deploy.
**Volta atrás:** apagar o comando de partida do serviço `app`. Duas linhas.

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
e a mesma regra de publicação do portal.

**A Railway documenta este caminho.** Para repositório com código
compartilhado, a orientação é conectar o mesmo repositório ao projeto e criar
um serviço por componente, **cada um com seu comando de partida**
([docs.railway.com/deployments/monorepo](https://docs.railway.com/deployments/monorepo)).
É por isso que o papel não fica escondido numa variável: ele é o comando que
você lê no painel.

**E há produto de gente grande fazendo assim.** O `docker-compose` oficial do
Apache Airflow sobe `api-server`, `scheduler`, `worker` e `triggerer` a partir
da **mesma imagem** `apache/airflow`, mudando só o papel de cada um
([airflow.apache.org](https://airflow.apache.org/docs/apache-airflow/stable/howto/docker-compose/index.html)).

**O FastMCP fecha o raciocínio do nosso caso.** A documentação dele diz, sobre
rodar com vários workers: "rode com múltiplos workers em produção (exige modo
stateless)", porque "as sessões ficam na memória de cada instância, o que cria
dificuldade ao escalar horizontalmente" — e a sessão carrega "o canal de volta
que pedidos iniciados pelo servidor, como *elicitation*, usam"
([gofastmcp.com/deployment/http](https://gofastmcp.com/deployment/http)). É
justamente por esse canal que o `publicar_rascunho` pede a sua aprovação. Por
isso separamos em vez de tornar stateless.

---

## Os comandos de partida

São estes dois, e eles se explicam sozinhos no painel:

| serviço | comando de partida |
|---|---|
| `app` (portal e API) | `python -m app.servir portal --workers 4` |
| `mcp` (só o conector) | `python -m app.servir mcp` |

O comando aplica as migrações e sobe o servidor. E ele **recusa** o que não
pode: pedir vários processos sem ser no papel `portal` para o deploy com uma
mensagem dizendo por quê. A configuração perigosa não chega a subir.

---

## Passo 0 — O serviço da API em Java

Este passo é novo: desde a migração, quem grava e lê no banco pelas tools do
MCP é uma aplicação Spring Boot, em `api/`. O adaptador Python não abre mais
conexão com o Postgres — ele fala HTTP com este serviço, e é isso que faz a
invariante "o MCP não toca o banco" ser garantida pela arquitetura, e não pela
disciplina de quem escreve o código.

O portal do aluno continua no processo Python, com SQLAlchemy, contra o
**mesmo** Postgres. Os dois convivem de propósito: a API já tem todo o
caminho do MCP, e portar o portal é outra empreitada. O Hibernate sobe com
`ddl-auto: validate`, então se alguém mexer no `models.py` sem acertar as
entidades, o deploy da API falha alto em vez de divergir em silêncio.

### 0.1 — Criar o serviço

1. No projeto do Railway → **+ New** → **GitHub Repo** → o mesmo repositório.
2. **Settings** → **Service Name**: `api`.
3. **Settings** → seção **Build** → **Dockerfile Path**: `api/Dockerfile`.
   **Root Directory**: `api`.
4. **Settings** → **Healthcheck Path**: `/actuator/health`.
5. **Settings** → **Pre-Deploy Command**: **vazio**. O Flyway roda na partida,
   com `baseline-version: 1` — banco que já tem o schema é marcado sem
   executar nada; banco vazio recebe a V1. Migração que falha derruba a
   partida, e o Railway mantém a versão anterior no ar.

### 0.2 — Variáveis do serviço `api`

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
SERVICO_TOKEN=<gere 32+ caracteres aleatórios e guarde>
PORT=8080
```

O `PORT` não é enfeite: o Railway roteia e sonda o healthcheck pela porta que
essa variável diz, e o Spring **ignora** `PORT` — ele escuta em 8080 a menos que
alguém diga `SERVER_PORT`. Sem a linha, o Railway sortearia uma porta, bateria
nela e daria o deploy como morto. Com ela, os dois concordam em 8080, e é esse
o número que vai no `API_BASE_URL` dos outros serviços.

O `SERVICO_TOKEN` é o segredo que prova que o comando veio do adaptador. Sem
ele — ou com menos de 32 caracteres — a aplicação **não sobe**, de propósito:
é melhor um deploy que falha do que uma porta de comando aberta. Gere com:

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### 0.3 — Rede interna, não pública

1. Serviço `api` → **Settings** → **Networking**. **Não** gere domínio
   público: o único cliente desta API é o serviço `mcp`, que a alcança pela
   rede privada do projeto.
2. Anote o endereço interno que o Railway mostra — algo como
   `api.railway.internal:8080`. É ele que vai no `API_BASE_URL` do `mcp`.

Se o Railway não oferecer rede privada no seu plano, gere o domínio público
mesmo assim: o `X-Servico` continua sendo a tranca, e sem ele toda rota
responde 401. Mas prefira a rede interna quando houver.

### 0.4 — Watch Paths: cada serviço só reconstrói o que é dele

Três serviços apontam para o mesmo repositório, e sem isto **todo push
reconstrói os três** — inclusive um commit que só mexeu em doc. Pior que o
desperdício é o raio de explosão: uma mudança só no Java reinicia o portal, e
um Python quebrado derruba `app` e `mcp` juntos.

Em cada serviço, **Settings → Build → Watch Paths**, um padrão por linha
(estilo `.gitignore`, com a barra inicial ancorando na raiz do repositório):

| Serviço | Watch Paths |
|---|---|
| `api` | `/api/**` |
| `app` e `mcp` | `/mcp/**` `/frontend/**` `/scripts/**` `/pyproject.toml` `/Dockerfile` |

O `Dockerfile` da raiz copia `mcp/`, `frontend/` e `scripts/`, e instala a
partir do `pyproject.toml` — é exatamente essa a lista, nem mais nem menos.
`docs/`, `.github/` e `api/` ficam de fora dos dois serviços Python; `mcp/` e
`frontend/` ficam de fora do `api`.

`app` e `mcp` continuam construindo a mesma imagem duas vezes quando algo do
Python muda. Isso só se resolve construindo a imagem uma vez na CI e publicando
num registro (GHCR), com os dois serviços deployando da imagem — mais
maquinário, e só compensa quando minuto de build começar a pesar.

---

## Passo 1 — Criar o serviço do MCP

O objetivo é tirar o `/mcp` do serviço do portal, porque é a única peça que
não pode ser duplicada.

### 1.1 — Copiar as variáveis que já existem

1. Abra o projeto **mvp-portal-aluno** no [railway.com](https://railway.com).
2. Clique no serviço **`app`** → aba **Variables**.
3. Canto superior direito da lista, botão **Raw Editor**.
4. **Selecione tudo e copie** (Ctrl+A, Ctrl+C). Guarde num bloco de notas:
   você vai colar quase tudo no serviço novo.

### 1.2 — Criar o serviço

1. No canvas do projeto, botão **+ Create** (ou tecle `Cmd/Ctrl + K` →
   *Deploy from GitHub repo*).
2. Escolha **GitHub Repo** → **EduardoJMenezes/mvp-portal-aluno** — o mesmo
   repositório de sempre.
3. Railway cria o serviço e já começa um deploy. **Deixe-o terminar ou falhar,
   tanto faz**: nos próximos passos ele será reconfigurado e subirá de novo.
4. Clique no serviço novo → **Settings** → seção **Service** → campo
   **Service Name**: troque para **`mcp`**.

### 1.3 — Comando de partida e healthcheck

Ainda em **Settings** do serviço `mcp`:

1. Seção **Deploy** → campo **Custom Start Command**:

   ```
   python -m app.servir mcp
   ```

2. Seção **Deploy** → campo **Healthcheck Path**:

   ```
   /saude
   ```

   Repare: **não** é `/api/saude`. O portal não mora neste processo; este
   caminho existe só para a Railway aprovar o deploy.

3. Seção **Deploy** → **Pre-Deploy Command**: **deixe vazio**. O serviço `app`
   tem o seed ali; rodar o seed duas vezes não é o que a gente quer.

### 1.4 — Variáveis do serviço `mcp`

1. Aba **Variables** do serviço `mcp` → botão **Raw Editor**.
2. Cole tudo o que você copiou do `app` e então **ajuste três coisas**:
   * **apague** a linha `PAPEL` se ela existir (o comando de partida manda);
   * **apague** `WEB_CONCURRENCY` se existir;
   * **acrescente**, por enquanto sem valor definitivo:

     ```bash
     MCP_BASE_URL=https://trocar-depois
     ```

   * **acrescente** o endereço da API do Passo 0 e o mesmo segredo que você
     gerou lá — sem estes dois, toda tool responde "a API da plataforma não
     respondeu":

     ```bash
     API_BASE_URL=http://api.railway.internal:8080
     SERVICO_TOKEN=<o mesmo valor do serviço api>
     ```

   **As duas linhas vão também no serviço `app`.** Ele serve a página de
   envio (`/enviar/<token>`), e é ela que entrega o .docx e os prints à API.
   O resto do portal não depende do Java — sem as variáveis, só o envio e o
   MCP ficam sem resposta; aluno e professor continuam navegando.

3. Confira que ficaram lá, vindas do `app`: `DATABASE_URL`, `JWT_SECRET`,
   `MCP_OAUTH_GITHUB_CLIENT_ID`, `MCP_OAUTH_GITHUB_CLIENT_SECRET`,
   `MCP_OAUTH_OPERADORES`, `VIMEO_ACCESS_TOKEN`.

   O `JWT_SECRET` precisa ser **idêntico** ao do `app`: é ele que assina a
   sessão do portal, e o MCP confere sessão emitida por lá.

4. **Deploy** (o botão que aparece no topo quando há mudança pendente).

### 1.5 — Gerar o endereço e fechar o MCP_BASE_URL

1. Serviço `mcp` → **Settings** → seção **Networking** → **Generate Domain**.
2. Railway mostra algo como `mcp-production-a1b2.up.railway.app`. **Copie.**
3. Volte em **Variables** do `mcp` e troque a linha para o endereço de verdade:

   ```bash
   MCP_BASE_URL=https://mcp-production-a1b2.up.railway.app
   ```

4. **Deploy** de novo e espere ficar verde.

### 1.6 — Conferir

No navegador, abra `https://<domínio do mcp>/saude`. Tem que responder:

```json
{"status": "ok", "papel": "mcp"}
```

Se responder isso, o serviço do MCP está de pé.

### 1.7 — Reapontar o conector no claude.ai

1. Em [claude.ai](https://claude.ai) → **Settings** → **Connectors**.
2. **Remova** o conector antigo da plataforma.
3. **Add custom connector**, com o endereço novo:
   `https://<domínio do mcp>/mcp`
4. Faça o login do GitHub que ele pedir.
5. No chat, peça algo simples: *"liste as turmas"*. Se vier a lista, o passo 1
   está fechado.

> Remover e adicionar de novo não é frescura: o conector guarda a lista de
> ferramentas de quando foi criado, e reconectar não a atualiza.

---

## Passo 2 — O portal com quatro processos

**Só depois que o passo 1 estiver respondendo no chat.**

1. Serviço **`app`** → **Settings** → **Deploy** → **Custom Start Command**:

   ```
   python -m app.servir portal --workers 4
   ```

2. **Deploy**. Uns 3 minutos.
3. Confira, nesta ordem:
   * `https://app-production-e5b7.up.railway.app/api/saude` → **200**
   * `https://app-production-e5b7.up.railway.app/mcp` → **404**
     *(é o sinal de que o portal largou o MCP — antes respondia 401)*
   * Entre no portal e navegue: curso, materiais, simulados.
4. Em **Deployments** → **View Logs**, a primeira linha do app deve dizer:
   `papel: portal (sem /mcp) — pode rodar com vários processos`.

**Por que 4 e não 8:** cada processo carrega o app (~200 MB) e abre até 40
conexões no banco. Quatro dão 160 conexões, com folga nos 500 que o Postgres
aceita, e já entregam o salto medido. Oito é possível depois, olhando o
`railway metrics`.

**Se algo der errado:** apague o Custom Start Command do serviço `app` e faça
deploy. Ele volta a ser exatamente o que era, com MCP e portal juntos.

---

## Passo 3 — CDN na frente (quando houver domínio próprio)

Este não dá para fazer hoje: o portal está num endereço `.up.railway.app`, e
não se põe Cloudflare na frente do domínio da Railway.

Quando o domínio próprio existir:

1. Registre o domínio e aponte os *nameservers* para a Cloudflare (plano
   grátis serve).
2. Na Railway: serviço `app` → **Settings** → **Networking** → **Custom
   Domain** → digite o domínio. Ele mostra um CNAME.
3. Na Cloudflare, crie esse CNAME com a **nuvem laranja** (proxy ligado).
4. Na Railway, acrescente às variáveis do `app`:
   `CORS_ORIGINS=https://<seu domínio>`.

Isso tira do nosso servidor os 660 MB de arquivos estáticos que 600 alunos
baixam ao entrar na primeira aula.

---

## O que já está feito, e você não precisa tocar

* **Cache imutável** nos arquivos do portal: quem volta não rebaixa 1,1 MB.
* **ETag no `/api/aluno/conteudo`**: quem volta recebe 304 no lugar de 48,7 KB.
* **Fila do banco** de 20 + 20 conexões, espera de 5 s — 503 rápido em vez de
  meio minuto pendurado.
* **Trava de login no Postgres**: com quatro processos, cinco tentativas por
  conta continuam sendo cinco.
* **Log com o tempo de cada resposta**, para a próxima pergunta de desempenho
  ser respondida com dado de dia normal.

## Como saber se valeu

Em `railway metrics --service app`, sob carga, o pico de CPU deve passar de
**1,0 vCPU** — o teto de um processo, que foi o que medi antes — para mais do
que isso. É a confirmação de que os outros núcleos entraram no jogo.
