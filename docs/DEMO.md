# Roteiro da demonstração

Destrincha a seção 22 de [MVP-ESPECIFICACAO.md](MVP-ESPECIFICACAO.md). A
mensagem não é "construímos uma plataforma de questões" — é que o Claude virou
uma **interface segura** para o professor operar o próprio ecossistema.

## Antes de começar

```bash
brew services start postgresql@17
.venv/bin/python -m app.seed --reset          # anote os tokens impressos
.venv/bin/python -m uvicorn app.main:app --port 8000
```

Ensaio geral automatizado, que percorre os quatro fluxos sozinho:

```bash
.venv/bin/python scripts/verificar_fluxos.py <token-do-professor>
```

Deixe abertos: o Claude com o MCP conectado, e duas janelas do navegador em
`http://127.0.0.1:8000` — uma logada como professor, outra anônima para os
alunos.

> **Rede:** `api.vimeo.com` precisa estar liberado. Em rede corporativa com
> filtro de DNS ele costuma estar bloqueado — ver [VIMEO.md](VIMEO.md).

## O roteiro

**1 — Mostre o acervo.** No Claude:

> Quais pastas eu tenho no Vimeo?

> Me mostre os vídeos de Cinética.

Ele chama `listar_videos_vimeo`. Ponto a verbalizar: *o agente está olhando o
acervo real, não um banco paralelo.*

**2 — Peça o cadastro.**

> Cadastre essas questões no Extensivo 2027, capítulo Cinética.

Ele chama `importar_questoes_vimeo`. Cada vídeo vira uma questão numerada,
ligada à resolução.

**3 — Mostre que nada foi publicado.** A resposta diz "Nenhuma questão foi
publicada". Abra **Rascunhos** no portal: a proposta está lá, parada, com o
carimbo de quem criou e por qual canal.

Ponto a verbalizar: *isto não é o modelo sendo educado. O backend recusa
publicar sem aprovação — e o teste `test_publicacao.py` prova.*

**4 — Publique.**

> Pode publicar.

O Claude chama `publicar_rascunho` e **você recebe um pedido de confirmação**.
A decisão é sua. (Recuse uma vez, para mostrar que nada acontece; depois
aceite.)

Alternativa, se o cliente não suportar confirmação: o botão **Aprovar e
publicar** no portal faz o mesmo.

**5 — Prove que mudou.** Portal → **Turmas** → Extensivo 2027 → Cinética. As
questões estão lá, cada uma com seu vídeo.

**6 — Entre como João** (`joao@aluno.demo`, Extensivo 2027). Ele vê Cinética e
abre a resolução em vídeo.

**7 — Entre como Pedro** (`pedro@aluno.demo`, Extensivo 2026). Ele **não** vê
Estequiometria. Ponto a verbalizar: *a filtragem é do backend; esconder no
frontend não seria segurança.*

**8 — Monte um simulado.**

> Monte um simulado para o Extensivo 2027 com as questões 1, 3 e 5 de
> Estequiometria.

Rascunho de novo. Confirme para publicar.

**9 — Responda como João**, pelo portal: uma questão por vez, A–E, Finalizar.
Se der tempo, responda também como Maria, com respostas diferentes — as
estatísticas ficam mais interessantes.

**10 — Volte ao Claude.**

> Como o João foi no último simulado?

> Onde a turma teve mais dificuldade?

Os números vêm das respostas que acabaram de ser dadas. Ponto de fechamento:
*o professor perguntou em português e recebeu análise sobre dados reais da
turma dele — sem abrir um dashboard.*

## Opcional: questão a partir de um print (§16)

Funciona sem código adicional. Mande a imagem de uma questão ao Claude:

> Cadastre essa questão no Extensivo 2027, capítulo Estequiometria.

Ele lê enunciado e alternativas e chama `criar_questao_rascunho` — que, como
toda tool de escrita, cria em rascunho e espera sua aprovação.

## Contas

Senha de todas: `demo1234`

| conta | papel | turma |
|---|---|---|
| `professor@escola.demo` | ADMIN | — |
| `gerenciador@escola.demo` | GERENCIADOR | — |
| `joao@aluno.demo` | ALUNO | Extensivo 2027 |
| `maria@aluno.demo` | ALUNO | Extensivo 2027 |
| `pedro@aluno.demo` | ALUNO | Extensivo 2026 |

O seed distribui o conteúdo assim — **Cinética fica vazia no Extensivo 2027 de
propósito**, porque é o buraco que o passo 2 preenche ao vivo:

| turma | capítulos com conteúdo |
|---|---|
| Extensivo 2027 | Estequiometria (Q01–Q05), Atomística (Q01–Q03) |
| Extensivo 2026 | Atomística (as mesmas questões, reaproveitadas), Cinética |

## Se algo der errado

| sintoma | causa provável |
|---|---|
| tool do Vimeo falha citando filtro de rede | `api.vimeo.com` bloqueado — ver [VIMEO.md](VIMEO.md) |
| player mostra "This video does not exist" | vídeo unlisted sem hash, ou embed restrito por domínio |
| `publicar_rascunho` manda aprovar no portal | cliente sem suporte a confirmação — use o botão no portal |
| aluno não vê nada | conteúdo ainda em rascunho, ou aluno de outra turma (é o esperado) |
| 401 nas tools | token do MCP revogado ou trocado — reemita com `scripts/token_mcp.py` |
