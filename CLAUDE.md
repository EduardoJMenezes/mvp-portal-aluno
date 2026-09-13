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

* **Ordem de montagem em `main.py`.** Quem hospeda é o app do MCP; o FastAPI
  (REST + portal) entra como `Mount("/")` e fica **sempre por último**. Um mount
  em `/` casa com qualquer caminho e encerra o roteamento: com o portal na
  frente, ele sequestra `/mcp` (405 em toda chamada) e também as rotas de OAuth
  que moram na raiz — `/authorize`, `/token`, `/.well-known/...` —, e aí o
  conector do claude.ai recebe HTML onde espera JSON e desiste do servidor. Ver
  [docs/MCP-OAUTH.md](docs/MCP-OAUTH.md). O middleware `BarraFinalDoMcp` existe
  para `/mcp` e `/mcp/` serem a mesma coisa.
* **Duas credenciais, uma identidade.** O MCP aceita token Bearer opaco (Claude
  Code, scripts) e login OAuth no GitHub (claude.ai), via `MultiAuth`. As duas
  terminam nos mesmos claims, e `identidade_da_sessao()` não sabe por qual
  porta a pessoa entrou — mantenha assim. Quem entra pelo GitHub só abre sessão
  se `MCP_OAUTH_OPERADORES` (ou o e-mail público) casar com um ADMIN ou
  GERENCIADOR.
* **Nada é apagado; tudo é filtrado.** Remoção é `removido_em` preenchido
  (`models.Rastreavel`). A consequência morde em silêncio: consulta sem o
  filtro faz conteúdo removido reaparecer para o aluno. Use `selecionar()` e
  `vivos()` de [services/consultas.py](backend/app/services/consultas.py) —
  `select()` cru num service de conteúdo é bug, não estilo. E como `unique`
  comum queimaria o nome de um módulo removido para sempre, a unicidade é
  índice parcial (`_vivo()` em `models.py`).
* **Conteúdo do curso é vídeo; `Questao` é só do simulado.** A questão da
  apostila mora na apostila — o que a plataforma guarda dela é o vídeo da
  resolução, como item de sub-módulo. Ver
  [docs/MODELO-CONTEUDO.md](docs/MODELO-CONTEUDO.md).
* **Módulo é endereço, assunto é etiqueta.** `Modulo` pertence à turma e
  carrega o "K01"; `Assunto` é global e **nunca** leva numeração de capítulo —
  K03 é Estequiometria em 2026 e Tabela Periódica em 2025.
* **Editar e remover são diretos; publicar não.** As tools de
  [tools_estrutura.py](backend/app/mcp_server/tools_estrutura.py) alteram na hora
  e gravam `alterado_por_id`; a confirmação é o preview no chat, escrito na
  descrição de cada uma. Não volte para formulário de confirmação (elicitation):
  o app do Claude responde a ele sozinho, sem mostrar a ninguém. Publicar continua exigindo aprovação humana em
  `drafts.aprovado_por_id`, verificada no banco — inclusive quando se publica
  item a item, que acontece de dentro de um rascunho já aprovado.
* **Elicitation mudou no protocolo 2026-07-28.** Requisição iniciada pelo
  servidor não existe mais; o canal é `InputRequiredResult` (SEP-2322).
  `publicar_rascunho` implementa os dois caminhos — não simplifique para só um.
* **`api.vimeo.com` costuma estar bloqueado em rede corporativa.** Ver
  [docs/VIMEO.md](docs/VIMEO.md). Sem token, o backend cai no acervo de
  demonstração embutido, e a POC roda inteira assim.
* **Produção tem curso real: nunca `seed --reset` lá.** Mudança de schema é
  migração leve em [app/migracoes.py](backend/app/migracoes.py) — `create_all`
  para tabela nova, `ALTER ... IF NOT EXISTS` para o resto, idempotente —, e o
  Dockerfile roda `python -m app.migracoes` antes de subir o servidor a cada
  deploy. Mudou `models.py`? Acrescente a alteração ao fim de `ALTERACOES`.
* **Simulado: o relógio entra como parâmetro.** Os services de
  [simulados.py](backend/app/services/simulados.py) recebem `agora`; não há job
  de entrega automática — a tentativa vencida é consolidada na próxima consulta.
  O resultado só sai depois do fechamento, e quem recusa é o backend. Ver
  [docs/MODELO-SIMULADO.md](docs/MODELO-SIMULADO.md).

## Nunca

* Commitar `.env` — ele carrega o token do Vimeo e o segredo do JWT.
* Guardar token em claro no banco: `api_tokens` guarda só o hash (SHA-256).
* Fazer o MCP falar direto com o banco. Ele passa pelos services, como o REST.
