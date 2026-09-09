# Contexto do projeto

POC (não é produto) que demonstra uma tese: **o professor opera a plataforma
conversando com o Claude**, via MCP, e o que ele aprova aparece na hora para os
alunos certos.

O norte é [docs/MVP-ESPECIFICACAO.md](docs/MVP-ESPECIFICACAO.md), escrito antes
do código. Leia antes de propor qualquer coisa: ele define escopo, prioridades
e o que está deliberadamente **fora** (§20 e §24). As decisões de implementação
estão em [docs/ARQUITETURA.md](docs/ARQUITETURA.md).

Projeto **independente**: não tem relação com o `mcp-hub` / `natto-agents` nem
com os outros MCPs da casa. Não puxe convenções deles (token de borda ES256,
catálogo `ia_mcp_server`, portas) — aqui a stack e a autenticação são próprias.

## Regra que não se negocia

> A IA propõe. O humano aprova. O backend publica.

Antes de mexer em `services/publicacao.py`, `services/rascunhos.py` ou na tool
`publicar_rascunho`, entenda as quatro camadas descritas em
[docs/ARQUITETURA.md](docs/ARQUITETURA.md#a-regra-que-sustenta-a-poc-6). Nenhuma
mudança pode abrir um caminho em que conteúdo chegue ao aluno sem aprovação
humana gravada em `drafts.aprovado_por_id`. `backend/tests/test_publicacao.py`
existe para travar isso — se um teste de lá começar a falhar, o problema é a
mudança, não o teste.

Duas regras de mesma natureza:

* **Segregação por turma é do backend** (§11). Não resolva no frontend.
* **Autorização é por identidade, não por canal.** O canal só decide o que
  exige um humano de fato (`exigir_humano_no_portal`).

## Comandos

```bash
brew services start postgresql@17

.venv/bin/python -m app.seed --reset      # recria o banco de demonstração
.venv/bin/python -m uvicorn app.main:app --port 8000
.venv/bin/python -m pytest backend/tests -q

cd frontend && npm run dev                # 5173, com proxy para o backend
cd frontend && npm run build              # o backend serve o dist em /
```

Ensaio geral dos quatro fluxos do §21, contra o servidor no ar:

```bash
.venv/bin/python scripts/verificar_fluxos.py <token-do-professor>
```

## Convenções

* **Código e comentários em português.** Nomes de domínio em português
  (`Questao`, `Turma`, `rascunho`); nomes de biblioteca ficam como são.
* **Services não conhecem HTTP nem MCP.** Levantam os erros de
  `app/errors.py`; cada borda traduz (status HTTP no REST, `ToolError` no MCP).
* **Mensagem de erro é interface.** O LLM lê e se corrige — por isso os erros
  listam o que existe ("Turma 'X' não existe. Turmas: …") em vez de só falhar.
* **Toda tool precisa de docstring**; ela é a descrição que o modelo lê. Não
  passe `description=` no decorator, senão a docstring é ignorada.
* **Tools em português, aceitando nomes** ("Extensivo 2027", "João"): quem
  chama é um modelo repetindo o que o professor disse, não um sistema com ids.

## Armadilhas conhecidas

* **Ordem de montagem em `main.py`.** O MCP vai em `/mcp` e o frontend
  estático em `/` — nessa ordem. Um mount em `/` casa com qualquer caminho e
  encerra o roteamento; invertê-los faz o `dist` sequestrar o endpoint do MCP
  (405 em toda chamada). O middleware `BarraFinalDoMcp` existe para `/mcp` e
  `/mcp/` serem a mesma coisa.
* **Elicitation mudou no protocolo 2026-07-28.** Requisição iniciada pelo
  servidor não existe mais; o canal é `InputRequiredResult` (SEP-2322).
  `publicar_rascunho` implementa os dois caminhos — não simplifique para só um.
* **`api.vimeo.com` costuma estar bloqueado em rede corporativa.** Ver
  [docs/VIMEO.md](docs/VIMEO.md). Sem token, o backend cai no acervo de
  demonstração embutido, e a POC roda inteira assim.
* **Sem migrações**: o schema vem de `create_all`. Mudou `models.py`? Rode
  `python -m app.seed --reset`.

## Nunca

* Commitar `.env` — ele carrega o token do Vimeo e o segredo do JWT.
* Guardar token em claro no banco: `api_tokens` guarda só o hash (SHA-256).
* Fazer o MCP falar direto com o banco. Ele passa pelos services, como o REST.
