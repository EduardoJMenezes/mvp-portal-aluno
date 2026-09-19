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
