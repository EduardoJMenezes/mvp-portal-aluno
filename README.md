# Plataforma Educacional — POC com Vimeo + MCP

Demonstração de ponta a ponta de uma tese:

> **O professor opera a plataforma conversando com o Claude.** Ele pede pelos
> vídeos do Vimeo, manda cadastrar questões, monta simulados e pergunta como a
> turma foi — e o que ele aprova aparece na hora para os alunos certos.

O MCP é o carro-chefe. O frontend existe para tornar visível que as ações do
agente mudaram o sistema de verdade, e que o conteúdo está segregado por turma.

```
        PROFESSOR                                   ALUNO
            │                                         │
    ┌───────┴────────┐                                │
    ▼                ▼                                ▼
Portal Admin    Claude / ChatGPT                Portal do Aluno
    │                │  MCP (HTTP, Bearer)            │
    │                ▼                                │
    │        ┌───────────────┐                        │
    └───────►│    Backend    │◄───────────────────────┘
             │  (FastAPI)    │
             └───┬───────┬───┘
                 ▼       ▼
           PostgreSQL   Vimeo API
```

REST e MCP entram no **mesmo processo** e chamam os **mesmos application
services**. O MCP não tem atalho para o banco.

## Documentação

| documento | para quê |
|---|---|
| [docs/MVP-ESPECIFICACAO.md](docs/MVP-ESPECIFICACAO.md) | **o norte** — a especificação original, íntegra |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | como está construído, e por quê |
| [docs/DEMO.md](docs/DEMO.md) | roteiro da apresentação, passo a passo |
| [docs/VIMEO.md](docs/VIMEO.md) | token, escopos, embed unlisted, filtro de rede |
| [CLAUDE.md](CLAUDE.md) | contexto para trabalhar neste repositório |

## Rodando

Pré-requisitos: Python 3.11+, Node 20+, PostgreSQL 17.

```bash
brew services start postgresql@17
createdb plataforma_mvp

python3 -m venv .venv
.venv/bin/pip install -e ".[dev]"
cp .env.example .env

.venv/bin/python -m app.seed --reset     # dados da demo + tokens do MCP
cd frontend && npm install && npm run build && cd ..

.venv/bin/python -m uvicorn app.main:app --port 8000
```

Tudo em `http://127.0.0.1:8000`: portal na raiz, MCP em `/mcp`, API em `/api`,
documentação da API em `/docs`.

Para mexer no frontend com recarregamento automático: `npm run dev` em
`frontend/` (porta 5173, com proxy para o backend).

### Contas da demonstração

Senha de todas: `demo1234`

| conta | papel | turma |
|---|---|---|
| `professor@escola.demo` | ADMIN | — |
| `gerenciador@escola.demo` | GERENCIADOR | — |
| `joao@aluno.demo` | ALUNO | Extensivo 2027 |
| `maria@aluno.demo` | ALUNO | Extensivo 2027 |
| `pedro@aluno.demo` | ALUNO | Extensivo 2026 |

### Conectando o Claude

O seed imprime os tokens de MCP do professor e do gerenciador. Para emitir
outro: `.venv/bin/python scripts/token_mcp.py professor@escola.demo`

```json
{
  "mcpServers": {
    "plataforma-educacional": {
      "type": "http",
      "url": "http://127.0.0.1:8000/mcp",
      "headers": { "Authorization": "Bearer pvm_SEU_TOKEN_AQUI" }
    }
  }
}
```

### Vimeo

Sem `VIMEO_ACCESS_TOKEN` no `.env`, o backend usa um acervo de demonstração
embutido e a POC roda inteira sem credencial. Para a integração real, ver
[docs/VIMEO.md](docs/VIMEO.md) — inclusive o filtro de DNS corporativo que
bloqueia `api.vimeo.com` e precisa ser resolvido antes da apresentação.

## A regra que sustenta tudo

> A IA propõe. O humano aprova. O backend publica.

E isso **não depende do modelo se comportar bem**: nenhuma tool de escrita
aceita status (todas gravam rascunho), e `publicar_rascunho` recusa qualquer
rascunho sem aprovação humana gravada no banco. Detalhe das quatro camadas em
[docs/ARQUITETURA.md](docs/ARQUITETURA.md#a-regra-que-sustenta-a-poc-6).

## Tools do MCP

| tool | o que faz |
|---|---|
| `listar_turmas` | turmas, com alunos e contagem de questões |
| `listar_capitulos` | capítulos disponíveis |
| `buscar_questoes` | questões por turma/capítulo/status |
| `listar_videos_vimeo` | pastas e vídeos do acervo no Vimeo |
| `listar_rascunhos` | propostas pendentes |
| `detalhar_rascunho` | o conteúdo de uma proposta, para revisão |
| `buscar_desempenho_aluno` | como um aluno foi, questão a questão |
| `buscar_estatisticas_simulado` | desempenho da turma e maior dificuldade |
| `listar_simulados` | simulados e suas tentativas |
| `criar_questao_rascunho` | cadastra uma questão **em rascunho** |
| `importar_questoes_vimeo` | uma questão por vídeo, **em rascunho** |
| `criar_simulado_rascunho` | monta um simulado **em rascunho** |
| `publicar_rascunho` | publica, após confirmação humana |

As tools aceitam nomes ("Extensivo 2027", "Estequiometria", "João") e resolvem
os ids sozinhas. Quando a referência é ambígua ou inexistente, o erro lista o
que existe — o modelo se corrige em vez de inventar.

## Testes

```bash
.venv/bin/python -m pytest backend/tests -q     # 38 testes, contra Postgres real

# ensaio geral: fala com o MCP como o Claude e com o portal como o aluno
.venv/bin/python -m app.seed --reset
.venv/bin/python scripts/verificar_fluxos.py <token-do-professor>
```

## Estrutura

```
backend/app/
  main.py            FastAPI + MCP no mesmo processo
  models.py          15 tabelas
  identidade.py      quem está pedindo, sem dizer por qual porta entrou
  services/          as regras — usadas por REST e MCP
  api/               controllers REST
  mcp_server/        instância, autenticação e tools do MCP
  vimeo/client.py    API REST do Vimeo + acervo de demonstração
frontend/src/        React + Vite: portal do professor e do aluno
backend/tests/       38 testes
scripts/             emissão de tokens e ensaio dos quatro fluxos
docs/                especificação, arquitetura, roteiro, Vimeo
```

## Estado

Os quatro critérios de sucesso (§21) funcionam de ponta a ponta e estão
cobertos por `scripts/verificar_fluxos.py`. A única parte sem verificação real
é a chamada à API do Vimeo, bloqueada pela rede de desenvolvimento — ver
[docs/VIMEO.md](docs/VIMEO.md#estado-da-integração).
