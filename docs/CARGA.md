# Quantos alunos a plataforma aguenta

Medido em produção na madrugada de 17/09/2026, contra
`app-production-e5b7.up.railway.app`, com a apostila real de 37,5 MB no banco.
A pergunta era direta: **aguenta 500 a 600 alunos ao mesmo tempo?**

**Não, ainda não.** Hoje ela aguenta bem cerca de **100 alunos**, degrada até
uns 150 e desmonta em 300. O motivo não é o banco nem a máquina — é um processo
só usando um núcleo de oito. A correção é conhecida, está medida abaixo, e o
que falta para aplicá-la é uma decisão sua sobre o MCP.

## A infraestrutura de hoje

| | |
|---|---|
| Contêiner | 8 vCPU, 8 GB (Railway, plano Hobby) — usando 258 MB |
| Servidor | uvicorn, **1 processo**, rotas síncronas num pool de 40 threads |
| Banco | Postgres 18.6, `max_connections` 500, banco de 50 MB |
| Vídeo | **não passa por aqui**: o player é iframe do Vimeo |
| Sessão | 12 h — o aluno faz login uma vez por dia |

Vale registrar o que o vídeo significa: assistir aula não custa nada ao nosso
servidor. Os bytes vêm do Vimeo. O que custa é apostila, simulado e navegação.

## O que cada rota custa

Medido de dentro do contêiner (sem rede, sem TLS), um pedido de cada vez:

| rota | resposta | tempo |
|---|---|---|
| `/api/eu` | 0,1 KB | 8,6 ms |
| `/api/aluno/conteudo` | 48,7 KB | 29,5 ms |
| `/api/aluno/materiais` | 0,3 KB | 10,8 ms |
| `/api/aluno/simulados` | 0,6 KB | 14,6 ms |
| `/api/aluno/simulados/3` | 0,3 KB | 11,8 ms |
| `/api/aluno/simulados/3/resultado` | 34,8 KB | 55,7 ms |
| `/api/aluno/desempenho` | 0,4 KB | 38,3 ms |
| `/api/aluno/figuras/1` | 30,3 KB | 8,0 ms |
| `/api/aluno/materiais/1/arquivo` (faixa de 256 KB) | 256 KB | 17,3 ms |
| `/api/aluno/materiais/1/arquivo` (faixa de 1 MB) | 1 MB | 24,4 ms |

Nenhuma rota é lenta. A apostila de 323 páginas entrega uma faixa de 256 KB em
17 ms — a decisão de guardar o PDF no Postgres com `substring` se sustentou.

## Onde quebra

Carga de dentro do contêiner, mistura realista de navegação, 15 s por nível:

| pedidos em voo | req/s | p50 | p95 | erros |
|---|---|---|---|---|
| 5 | 69 | 39 ms | 228 ms | 0 |
| 15 | 55 | 151 ms | 856 ms | 0 |
| 30 | 56 | 452 ms | 1,1 s | 0 |
| 60 | 9 | 1,0 s | **30,7 s** | sim |
| 120 | 3 | **40 s** | 40 s | tudo |

O teto é **~60 pedidos por segundo**, e acima de ~30 pedidos em voo a fila
explode. De fora, com alunos virtuais de verdade (sessão própria, pausa entre
uma ação e outra), o mesmo limite aparece assim:

| alunos simultâneos | como ficou |
|---|---|
| 25 | p50 de 300 a 500 ms — bom |
| 100 | p50 900 ms, p99 14 s — passa, mas já raspa |
| 300 | 78% de erro, tudo estourando os 30 s |

E o log do servidor dizia o que era, em letras garrafais:
`sqlalchemy.exc.TimeoutError` em `pool._do_get()`.

### Os dois gargalos, em ordem

1. **Um processo só.** O uvicorn subia com um worker. Python roda um bytecode
   de cada vez por processo (o GIL), então sete dos oito núcleos ficavam vendo
   o oitavo trabalhar. É o teto de 60 req/s.
2. **A fila do banco era de escritório.** O pool do SQLAlchemy vinha no padrão
   de script: 5 conexões, mais 10 de transbordo, e 30 segundos de espera antes
   de desistir. Quando o pico chegava, cada aluno ficava meio minuto pendurado
   para no fim receber erro — o pior dos dois mundos.

Não são gargalos do banco (50 MB, 500 conexões, quase ocioso), nem de memória
(258 MB de 8 GB), nem de rede.

## O que já foi corrigido

Commit `484c632`, no ar:

* **Pool de 20 + 20, espera de 5 s.** Não aumenta o teto — aumenta a dignidade
  de quem passa dele: 503 na hora em vez de 30 s pendurado. Medido: a 60
  pedidos em voo, o p95 caiu de 30,7 s para 2,6 s e a vazão subiu de 9 para 47
  req/s.
* **Cache imutável no portal.** Os arquivos de `/_next/static` têm o hash do
  conteúdo no nome e vinham sem `Cache-Control`: cada aba aberta revalidava 25
  arquivos, 1,1 MB. Agora valem um ano. Seiscentos alunos entrando na aula
  faziam **15 mil pedidos e 660 MB** só para ouvir "não mudou"; na segunda
  visita, agora, fazem zero.
* **`WEB_CONCURRENCY` no Dockerfile**, ainda em 1. É o dial do item 1 do plano.

## O plano, para você revisar

### 1. Separar o MCP do portal — e então ligar os processos

**Por que os dois juntos:** o ganho está em rodar 4 a 8 processos. Medi, subindo
uma cópia com 4 processos dentro do próprio contêiner:

| pedidos em voo | 1 processo | 4 processos |
|---|---|---|
| 15 | 55 req/s, p50 159 ms | **239 req/s, p50 22 ms** |
| 30 | 51 req/s, p50 375 ms | **194 req/s, p50 22 ms** |
| 60 | 47 req/s, p50 1,0 s | **179 req/s, p50 167 ms** |
| 120 | 21 req/s, p50 6,2 s | **138 req/s, p50 232 ms** |

**4,4× de vazão e p50 sete vezes menor** — e os 239 req/s são o limite do meu
medidor, não do servidor. Com isso, 600 alunos cabem com folga de quatro vezes.

**O que impede de girar o dial hoje:** o MCP guarda a sessão do conector na
memória do processo. Conferido no FastMCP 4.0.4 instalado:
`self._server_instances: dict[str, StreamableHTTPServerTransport] = {}`. Com
dois ou mais processos, um pedido do conector cai no processo errado e a sessão
some. O portal não tem esse problema (a sessão dele é o JWT no cookie).

Dois caminhos, e a escolha é sua:

* **(a) Dois serviços na Railway, a partir da mesma imagem.** `app` serve o
  portal e a API com `WEB_CONCURRENCY=4`; `mcp` serve o `/mcp` com um processo.
  O endereço do conector muda, e você o adiciona de novo no claude.ai — coisa
  que já faz quando uma tool muda de descrição. É a solução limpa, e ainda
  isola o LibreOffice do importador do caminho dos alunos.
* **(b) MCP sem sessão (`stateless_http=True`), um serviço só.** Mudança de uma
  linha, mas muda o transporte do conector — e quem testa isso é você, com o
  claude.ai aberto. Se funcionar, é o caminho barato.

Eu recomendo o **(a)**: mais previsível, e separa dois tipos de carga que não
têm nada a ver um com o outro.

### 2. Um CDN na frente do portal

Mesmo com o cache imutável, o **primeiro** acesso de cada aluno baixa 1,1 MB do
nosso contêiner: 660 MB no primeiro dia de aula, servidos por Python. Cloudflare
na frente (plano grátis) tira isso do servidor e absorve o tranco da entrada.
É configuração, não código.

### 3. Deixar o `conteudo` mais barato

É a rota mais chamada e a segunda mais cara (48,7 KB, 29,5 ms): toda aba de
portal pede a árvore inteira do curso. Um `ETag` (304 quando nada mudou) ou um
cache de 60 s por turma corta boa parte do pico de entrada. O preço é conteúdo
novo aparecer com até um minuto de atraso — por isso não fiz sozinho.

### 4. Login: 40 por segundo, e está bom

O bcrypt custa 192 ms e usa os oito núcleos: o teto é **40 logins/s**, ou 15
segundos para 600 alunos entrarem. Como a sessão dura 12 h, isso acontece uma
vez por dia. Não mexeria — a não ser que você queira baixar o custo do bcrypt
de 12 para 10 (quatro vezes mais rápido, ainda forte para portal). Fica de
registro, não de recomendação.

### 5. Um detalhe de segurança que os processos trazem junto

A trava de tentativas de login (5 por conta, 20 por IP) vive na memória do
processo. Com 4 processos ela vira, na prática, 20 por conta e 80 por IP. Não é
impeditivo — é uma linha para mover o contador para o banco quando o portal
sair do piloto.

### 6. Enxergar sem precisar de teste de carga

Hoje o servidor não registra tempo de resposta. Um log de acesso com duração,
ou um middleware de dez linhas, responde a próxima pergunta dessas com dados do
dia a dia em vez de uma madrugada de medição.

## Se nada for feito

600 alunos em uso normal pedem cerca de **60 requisições por segundo** —
exatamente o teto de hoje. Ou seja: sem margem nenhuma, e qualquer pico (a aula
começando, o simulado abrindo) derruba o portal por minutos. Com o item 1, o
mesmo cenário usa um quarto da capacidade.

## Como repetir o teste

O andaime não foi para o repositório (é medição, não produto), mas o método é:

1. Sessões das 32 contas fictícias `@alunos-teste.invalid` emitidas **por
   dentro** do contêiner, com `cria_jwt` — nenhuma senha foi digitada, criada
   ou lida.
2. Alunos virtuais em `httpx` assíncrono, cada um com sessão própria, mistura
   de navegação, leitura de apostila em faixas de 256 KB, anotação salva,
   simulado aberto e resultado lido, com pausa entre as ações.
3. A mesma carga rodada de dentro do contêiner, contra `127.0.0.1`, para
   separar o que é servidor do que é rede.
4. As 116 anotações que o teste gravou nas contas fictícias foram apagadas ao
   final; as 4 anotações reais (professor e Pedro) ficaram intactas.
