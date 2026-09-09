# Arquitetura

Referência: [MVP-ESPECIFICACAO.md](MVP-ESPECIFICACAO.md). As seções citadas
(§3, §6, §19…) são dela.

## Backend único

```
REST (portal)  ─┐
                ├─► Application Services ─► PostgreSQL / Vimeo
MCP (agente)   ─┘
```

FastAPI e FastMCP sobem **no mesmo processo** ([main.py](../backend/app/main.py)),
e as duas bordas chamam os mesmos services. O MCP não tem caminho próprio até o
banco — é o §19 levado a sério, não uma figura de retórica.

Quem torna isso possível é [identidade.py](../backend/app/identidade.py): os
services recebem sempre uma `Identidade` (usuário, papel, canal) e não sabem se
ela nasceu de um login no navegador ou de um token de MCP. Autorização escrita
uma vez, valendo para os dois.

```
backend/app/
  main.py            FastAPI + MCP no mesmo processo
  models.py          15 tabelas (§18)
  identidade.py      quem está pedindo, sem dizer por qual porta entrou
  errors.py          erros de domínio; cada borda traduz para o seu formato
  security.py        senha (bcrypt), sessão do portal (JWT), token do MCP (SHA-256)
  services/
    catalogo.py        consulta e segregação por turma
    rascunhos.py       criação — sempre em rascunho
    publicacao.py      aprovação e publicação
    simulados.py       aluno respondendo
    analytics.py       estatísticas
  api/               controllers REST (portal do professor e do aluno)
  mcp_server/        instância, autenticação e as 13 tools
  vimeo/client.py    API REST do Vimeo + acervo de demonstração
```

## A regra que sustenta a POC (§6)

> A IA propõe. O humano aprova. O backend publica.

E isso **não depende do modelo se comportar bem**. Quatro camadas, da mais
fraca para a mais forte:

1. **Instruções do servidor MCP** ([server.py](../backend/app/mcp_server/server.py)):
   dizem ao modelo para nunca publicar sem aprovação. Útil, e insuficiente
   sozinha — é exatamente o que o §6 alerta.
2. **Nenhuma tool de escrita aceita status.** Todas gravam `RASCUNHO`
   ([rascunhos.py](../backend/app/services/rascunhos.py)). Não existe caminho,
   a partir do MCP, que crie conteúdo já publicado.
3. **`publicar_rascunho` recusa rascunho sem aprovação humana gravada** em
   `drafts.aprovado_por_id` ([publicacao.py](../backend/app/services/publicacao.py)).
   É uma checagem de estado no banco. Um agente que "esqueça" de pedir
   confirmação recebe `AprovacaoNecessaria` e não publica nada.
4. **Só duas coisas gravam essa aprovação, e ambas exigem uma pessoa:**
   - o botão no portal — `aprovar_rascunho` chama `exigir_humano_no_portal`,
     que **barra o canal MCP**;
   - a confirmação que o servidor pede ao cliente antes de publicar —
     `registrar_confirmacao_do_cliente_mcp`, que **não tem tool exposta**. O
     modelo controla o pedido de publicação; o aceite vem do usuário.

### A confirmação, e por que ela não é `ctx.elicit`

A versão **2026-07-28** do protocolo MCP removeu a elicitation empurrada pelo
servidor (SEP-2322, SEP-2575): o núcleo virou request/response sem back-channel.
O substituto é o canal *guard/return* — a tool devolve um `InputRequiredResult`
com o pedido, o cliente mostra o formulário, e a tool é reinvocada com a
resposta em `ctx.input_responses`.

`publicar_rascunho` ([tools.py](../backend/app/mcp_server/tools.py)) implementa
os dois: tenta `ctx.elicit()` (clientes de era anterior) e cai no guard/return
quando o protocolo é o novo. Se o cliente não suportar nenhum dos dois, a tool
**não publica** — devolve a instrução de aprovar no portal.

## Segregação por turma (§11)

Aplicada nas consultas, no backend. `exigir_acesso_a_turma`
([catalogo.py](../backend/app/services/catalogo.py)) é a única porta: operador
vê qualquer turma, aluno só as suas. O frontend não filtra nada por conta
própria, e o aluno nem recebe o gabarito no JSON.

`test_segregacao.py` prova isso sem interface no meio — inclusive que Pedro,
do Extensivo 2026, leva 403 ao pedir explicitamente o conteúdo do 2027.

## Reaproveitamento entre anos (§18)

Quem amarra uma questão a uma turma é `class_questions`, não a própria questão.
A mesma questão (e o mesmo vídeo) pode ser Q17 do capítulo 2 em 2026 e Q31 do
capítulo 4 em 2027. A organização anual pertence à plataforma, não às pastas do
Vimeo.

## Rascunho como entidade

`drafts` é de primeira classe: tudo que a IA cria nasce apontando para um
rascunho, e publicar é uma transição desse rascunho — não de cada linha solta.
É o que faz `publicar_rascunho(id)` ser atômico e auditável, e o que permite
mostrar ao professor um resumo coerente do que ele está aprovando.

## O que o MVP pediu, e onde está

| §  | pedido | onde |
|----|--------|------|
| 3  | backend único para REST e MCP | `main.py`, `services/` |
| 4  | ADMIN / GERENCIADOR / ALUNO; aluno fora do MCP | `models.Papel`, `mcp_server/auth.py` |
| 5  | autenticação do MCP, sem execução anônima | `mcp_server/auth.py` (simplificação documentada) |
| 6  | IA não publica direto | `services/publicacao.py` + `tools.publicar_rascunho` |
| 7  | dados de demonstração | `seed.py` |
| 8  | consulta ao Vimeo | `vimeo/client.py`, tool `listar_videos_vimeo` |
| 10 | frontend admin mínimo | `frontend/src/paginas/Admin*.tsx` |
| 11 | frontend aluno e segregação | `frontend/src/paginas/Aluno*.tsx`, `catalogo.py` |
| 12 | estrutura da questão | `models.Questao`, `Alternativa`, `Classificacao` |
| 13 | simulado via MCP | `criar_simulado_rascunho` |
| 14 | aluno respondendo | `services/simulados.py`, `AlunoProva.tsx` |
| 15 | estatísticas via MCP | `services/analytics.py` |
| 16 | questão por imagem (opcional) | sem código próprio — ver [DEMO.md](DEMO.md) |
| 17 | tools do MCP | `mcp_server/tools.py` |
| 18 | modelo de dados | `models.py` |
| 19 | princípios de implementação | esta página |
| 21 | critérios de sucesso | `scripts/verificar_fluxos.py` |

## Simplificações assumidas

Documentadas como simplificação, não como arquitetura final — a distinção que
o §5 pede.

* **Token Bearer opaco no lugar de OAuth.** O token só diz *quem* é a pessoa;
  o que ela pode fazer continua sendo decidido pelo papel dela, no backend, a
  cada chamada. Trocar por OAuth muda a origem da identidade e nada mais.
* **Sem scopes granulares** (`questions:write`, `analytics:read`…). Hoje o
  papel decide tudo. O §4 já prevê isso como evolução.
* **Sem migrações.** O schema vem de `create_all`. Produção precisa de Alembic.
* **Questão importada do Vimeo entra sem alternativas A–E**, com o título do
  vídeo como enunciado. Aparece para o aluno como questão com resolução em
  vídeo, e é recusada em simulados. Redigir a questão seria geração autoral por
  IA, fora do escopo pelo §24; para a questão completa existe
  `criar_questao_rascunho`.
* **Frontend deliberadamente simples** (§10): serve para provar que as ações do
  agente mudaram o sistema, não para ser um CMS.

## Fora do escopo (§20)

Importação do acervo inteiro, migração 2022–2027, CMS completo, dashboards,
pagamentos, notificações, multi-tenancy, geração autoral de questões ou imagens
por IA, taxonomia definitiva, auditoria, versionamento, filas. A arquitetura
não impede nada disso — só não foi construído.
