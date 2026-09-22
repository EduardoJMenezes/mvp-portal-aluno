# Plataforma Educacional — POC com Vimeo + MCP

Demonstração de ponta a ponta de uma tese:

> **O professor opera a plataforma conversando com o Claude.** Ele pede pelos
> vídeos do Vimeo, manda cadastrar questões, monta simulados e pergunta como a
> turma foi — e o que ele aprova aparece na hora para os alunos certos.

O MCP é o carro-chefe. O frontend existe para tornar visível que as ações do
agente mudaram o sistema de verdade, e que o conteúdo está segregado por turma.

```
        PROFESSOR                                        ALUNO
            │                                              │
    ┌───────┴────────┐                                     │
    ▼                ▼                                     ▼
Portal Admin    Claude (claude.ai / Claude Code)    Portal do Aluno
    │                │  MCP                                │
    │                ▼                                     │
    │      ┌──────────────────┐  HTTP /comandos/*          │
    │      │ mvp-portal-mcp   │───────────┐                │
    │      │ (FastMCP, Python)│           ▼                │
    │      └──────────────────┘   ┌───────────────┐        │
    └────────────────────────────►│  API em Java  │◄───────┘
                                  │ (este repo)   │
                                  └───┬───────┬───┘
                                      ▼       ▼
                                PostgreSQL   Vimeo / Zoom
```

Este repositório é a **API em Spring Boot** — dona das regras, do schema
(Flyway) e do portal exportado pelo Next, que ela serve na raiz. O adaptador
MCP mora em [mvp-portal-mcp](https://github.com/EduardoJMenezes/mvp-portal-mcp)
e **não tem banco**: cada tool é um comando HTTP aqui, e um comando é uma
transação. O Python antigo está congelado em `mvp-portal-legado`.

## Documentação

| documento | para quê |
|---|---|
| [docs/MVP-ESPECIFICACAO.md](docs/MVP-ESPECIFICACAO.md) | **o norte** — a especificação original, íntegra |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | como está construído, e por quê |
| [docs/PADRAO-JAVA.md](docs/PADRAO-JAVA.md) | o padrão do lado Java |
| [docs/RAILWAY-PASSO-A-PASSO.md](docs/RAILWAY-PASSO-A-PASSO.md) | os dois serviços em produção |
| [docs/DEMO.md](docs/DEMO.md) | roteiro da apresentação, passo a passo |
| [CLAUDE.md](CLAUDE.md) | contexto para trabalhar neste repositório |

## Rodando

Pré-requisitos: Java 25, Node 22+, PostgreSQL 17+ (ou Docker, para os testes).

```bash
createdb plataforma_mvp
export DATABASE_URL=postgresql://usuario:senha@localhost:5432/plataforma_mvp
export SERVICO_TOKEN=$(python -c "import secrets; print(secrets.token_urlsafe(32))")

cd frontend && npm install && npm run build && cd ..
./mvnw spring-boot:run          # http://127.0.0.1:8080 — o Flyway cria o schema num banco vazio
```

Tudo em `http://127.0.0.1:8080`: portal na raiz, API em `/api`, comandos do
MCP em `/comandos`. Para mexer no frontend com recarregamento automático:
`npm run dev` em `frontend/` (Next.js na porta 3000, com `/api` reescrito
para o 8080). O portal é Next.js + Tailwind, exportado como site estático em
`frontend/out` — mesma origem para o cookie da sessão.

O conector do Claude é o outro repositório: suba-o com `API_BASE_URL` apontando
para cá e o mesmo `SERVICO_TOKEN`.

### Sessão e senhas

O login grava a sessão num cookie `httpOnly`, `SameSite=Strict`, restrito a
`/api` (e `Secure` com `SESSAO_COOKIE_SEGURO=true`, o padrão). Erro de senha
responde a mesma frase para conta inexistente; 5 falhas numa conta ou 20 num IP
travam o login por 15 minutos. Aluno cadastrado pelo professor recebe senha
temporária e só navega depois de trocá-la; trocar a senha derruba as outras
sessões. Contas e tokens do MCP se administram no portal (Turmas › Alunos e
Conectar ao Claude). `MODO_DEMO=true` lista as contas `@escola.demo` e
`@aluno.demo` na tela de entrada e entra nelas sem senha — nunca em ambiente
com gente de verdade.

### Vimeo e Zoom

Sem `VIMEO_ACCESS_TOKEN`, o acervo de demonstração embutido; sem as quatro
variáveis `ZOOM_*`, um Zoom de mentira. A POC roda inteira sem credencial
nenhuma. Ver [docs/VIMEO.md](docs/VIMEO.md) e
[docs/AULAS-AO-VIVO.md](docs/AULAS-AO-VIVO.md).

## A regra que sustenta tudo

> A IA propõe. O humano aprova. O backend publica.

E isso **não depende do modelo se comportar bem**: nenhum comando de escrita
aceita status (todos gravam rascunho), e publicar recusa qualquer rascunho sem
aprovação humana gravada no banco. Detalhe das quatro camadas em
[docs/ARQUITETURA.md](docs/ARQUITETURA.md#a-regra-que-sustenta-a-poc-6).

## Testes

```bash
./mvnw -B verify      # Postgres por Testcontainers; sem Docker, aponte SPRING_DATASOURCE_URL para um banco *de teste*
```

## Estrutura

```
src/main/java/br/com/plataforma/
  comum/       Identidade, erros de domínio, Rastreavel, Referencias
  contas/ catalogo/ estrutura/ acervo/ taxonomia/ questoes/ rascunhos/
  simulados/ analytics/ materiais/ aulas/ vimeo/ importacoes/   as regras, por feature
  comandos/    a borda do MCP: /comandos/<tool> e /interno/*
  portal/      a borda do navegador: /api/*, sessão, portal estático
  seguranca/   as quatro portas
src/main/resources/db/migration/   o schema (Flyway)
src/test/java/                     comandos e portal, contra Postgres real
frontend/                          Next.js + Tailwind: portal do professor e do aluno
docs/                              especificação, arquitetura, roteiro, produção
```
