# Produção no Railway: dois serviços, dois repositórios

```
                    ┌──────────────────────────┐
  navegador ───────►│ app  (este repositório)  │──┐
                    │ Java + portal estático   │  │
                    └──────────────────────────┘  │
                              ▲ HTTP privado      ├─► Postgres
                    ┌──────────────────────────┐  │
  claude.ai ───────►│ mcp  (mvp-portal-mcp)    │──┘ (só o registro OAuth)
  Claude Code       │ FastMCP + página de envio│
                    └──────────────────────────┘
```

| serviço | repositório | o que faz | build | healthcheck |
|---|---|---|---|---|
| `app` | `mvp-portal-aluno` (este) | a API em Java, o portal do aluno e do professor, o schema (Flyway) | `Dockerfile` na raiz | `/api/saude` |
| `mcp` | `mvp-portal-mcp` | o conector MCP do Claude e a página `/enviar/<token>` | `Dockerfile` na raiz | `/saude` |

O `mcp` **não tem banco**: toda tool bate em `http://app.railway.internal:8080/comandos/*`
com o `SERVICO_TOKEN`. O `DATABASE_URL` dele serve só para o proxy OAuth guardar
o registro do conector entre deploys (tabela `oauth_mcp_kv`).

O Python antigo (portal em FastAPI) está congelado em `mvp-portal-legado` e
não recebe deploy.

---

## Serviço `app`

1. **Source**: repositório `mvp-portal-aluno`, branch `main`, **sem** Root
   Directory (o `Dockerfile` está na raiz e constrói o Next e o Java).
2. **Settings → Healthcheck Path**: `/api/saude`.
3. **Settings → Pre-Deploy Command**: **vazio**. O Flyway roda na partida com
   `baseline-version: 1`: banco que já tem o schema é marcado sem executar
   nada; banco vazio recebe a V1. Migração que falha derruba a partida, e o
   Railway mantém a versão anterior no ar.
4. **Settings → Custom Start Command**: vazio (o `CMD` do Dockerfile serve).

### Variáveis

```bash
DATABASE_URL=${{Postgres.DATABASE_URL}}   # a aplicação entende o formato postgresql://usuario:senha@host/banco
SERVICO_TOKEN=<32+ caracteres aleatórios; o mesmo vai no mcp>
JWT_SECRET=<o mesmo de antes: os cookies dos professores continuam valendo>
MCP_BASE_URL=https://mcp-production-041f.up.railway.app   # o domínio público do serviço mcp
CORS_ORIGINS=https://app-production-e5b7.up.railway.app
VIMEO_ACCESS_TOKEN=...
ZOOM_ACCOUNT_ID=... ZOOM_CLIENT_ID=... ZOOM_CLIENT_SECRET=... ZOOM_HOST=...
```

`PORT` o Railway põe sozinho, e o Spring escuta nela (`server.port=${PORT}`).
`SESSAO_COOKIE_SEGURO` fica no padrão (`true`): o cookie só viaja por https.
`MODO_DEMO` **nunca** em produção.

`MCP_BASE_URL` importa por dois motivos: é para lá que o portal repassa o envio
do `.docx` e dos prints (`/api/importacoes/<token>/*`, que mora no `mcp`), e é
o domínio do link que o professor recebe no chat.

### Watch Paths

`Settings → Build → Watch Paths`: `src/**`, `pom.xml`, `frontend/**`,
`Dockerfile`. Assim um commit só de documentação não reconstrói o serviço.

---

## Serviço `mcp`

1. **Source**: repositório `mvp-portal-mcp`, branch `main`, sem Root Directory.
2. **Settings → Healthcheck Path**: `/saude`.
3. **Custom Start Command**: vazio (`python -m app.servir`, do Dockerfile).
   Um processo só: a sessão do conector mora na memória.

### Variáveis

```bash
API_BASE_URL=http://app.railway.internal:8080
SERVICO_TOKEN=<o mesmo do app>
MCP_BASE_URL=https://mcp-production-041f.up.railway.app
PORTAL_URL=https://app-production-e5b7.up.railway.app   # para onde vai o "aprove no portal"
VIMEO_ACCESS_TOKEN=...
MCP_OAUTH_GITHUB_CLIENT_ID=... MCP_OAUTH_GITHUB_CLIENT_SECRET=... MCP_OAUTH_OPERADORES=...
DATABASE_URL=${{Postgres.DATABASE_URL}}   # só o registro OAuth
```

O que **sai** do `mcp` em relação ao que ele tinha: `JWT_SECRET`, as variáveis
do Zoom, `APP_HOST` e `CORS_ORIGINS` (o conector não precisa de nenhuma).

---

## Pela linha de comando

Tudo acima cabe no CLI (`railway`), e foi assim que a migração foi aplicada:

```bash
railway variables --service app --set "SERVICO_TOKEN=..." --set "MCP_BASE_URL=..."
railway variables --service mcp --set "API_BASE_URL=http://app.railway.internal:8080" --set "SERVICO_TOKEN=..."
railway service source connect --service mcp --repo EduardoJMenezes/mvp-portal-mcp --branch main
railway environment edit --service-config app deploy.healthcheckPath /api/saude
railway environment edit --service-config app deploy.preDeployCommand ""
```

Conferir um deploy: `railway deployment list --service app` e
`railway logs --service app`. Na partida do `app`, procure
`Successfully validated 1 migration` (Flyway) e `portal servido de
/app/frontend/out`.

---

## Depois do primeiro deploy

1. `https://app-production-e5b7.up.railway.app/api/saude` → `{"ok": true, ...}`.
2. Entrar no portal com a conta do professor: a sessão antiga continua
   valendo (mesmo `JWT_SECRET`, mesmo formato de cookie).
3. No claude.ai, **remover e adicionar de novo** o conector do MCP se a lista
   de ferramentas estiver velha — só reconectar não atualiza.
4. Pedir um link de envio pelo chat e abrir: a página vem do domínio do `mcp`.

## Volta atrás

O `mvp-portal-legado` tem o Python inteiro como estava antes da migração.
Apontar o `app` para ele (Source → repositório) devolve o portal antigo; o
banco é o mesmo, e o schema não mudou.
