# Quantos alunos a plataforma aguenta

Medido em produção contra `app-production-e5b7.up.railway.app`, com a apostila
real de 37,5 MB no banco. A pergunta era direta: **aguenta 500 a 600 alunos ao
mesmo tempo?**

**Em 17/09, não: aguentava uns 100.** Um processo só, usando um núcleo de oito.
**Em 19/09, com o MCP separado e quatro processos no portal, sim — e sobra.**
Abaixo estão as duas medições: primeiro o diagnóstico que levou à correção,
depois os números do estado atual.

> **Resumo do estado de hoje (19/09/2026), com quatro processos**
>
> | caminho | teto medido | folga para 600 alunos |
> |---|---|---|
> | navegação (mix pesado) | ~150–185 req/s | é o mais apertado |
> | abrir o portal (aba nova) | ~267 req/s | 600 entram em ~11 s |
> | portal frio (13 arquivos, 667 KB) | ~80 abas/s, 55 MB/s | 600 em ~7,5 s |
> | escrever anotação | **~560 gravações/s** | folga de 2,8× |
> | ler apostila (faixas de 256 KB) | ~130 faixas/s, **40 MB/s** | limite vira banda, não CPU |
> | login (bcrypt) | 40/s | 600 logins em 15 s, uma vez por dia |
>
> Com 600 alunos virtuais batendo de fora, **o servidor respondeu em p50 de
> 12 ms e p95 de 82 ms**. Quem engasgou foi o meu notebook, não a plataforma:
> não consegui achar o teto real de fora.

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

#### Não, isso não é trocar de linguagem

O teto de 60 req/s é de **um processo**, não do Python. Um processo Python
executa um bytecode por vez (o GIL), e a resposta para isso não é reescrever
nada: é rodar oito processos, um por núcleo, que é exatamente o que PHP, Ruby e
Node fazem há vinte anos. Node, aliás, tem o mesmo laço único por processo e
precisaria do mesmo `cluster`.

E o número diz que a linguagem não é o gargalo: as rotas custam de **8 a 55 ms**
de servidor, o banco está ocioso, a memória em 258 MB de 8 GB. Não há trabalho
pesado de CPU aqui para uma linguagem compilada economizar — há fila. Reescrever
em Go trocaria meses de trabalho e todos os testes por um ganho que uma linha no
Dockerfile já dá.

Quando eu levantaria a mão para trocar alguma coisa: se um dia o gargalo virar
transcodificar vídeo, processar imagem em escala ou algo assim — e mesmo aí o
certo seria tirar **aquele pedaço** do caminho do aluno, não reescrever o
portal.

### 1b. A escrita da anotação: medida, e não é problema

Medo legítimo: a turma inteira riscando a apostila **durante a aula**, cem mãos
ao mesmo tempo. Fui medir, de dentro do contêiner, no processo único de hoje:

| escritas simultâneas | gravações/s | p50 | p95 | erros |
|---|---|---|---|---|
| 10 | 100 | 93 ms | 136 ms | 0 |
| 25 | 109 | 209 ms | 382 ms | 0 |
| 50 | 92 | 493 ms | 787 ms | 0 |
| 100 | 52 | 939 ms | 6,6 s | 42 |

Antes de ler a tabela, desfazer um mal-entendido que muda tudo: **o PDF nunca é
reescrito**. O arquivo de 37 MB é só leitura; o que a anotação grava é uma linha
de **2,2 KB** — a página que mudou, em JSON. E o leitor espera 1,5 s depois do
último traço para mandar.

Então a tradução da tabela é esta: um aluno riscando manda, no pior caso, uma
gravação a cada três segundos. Cem alunos riscando ao mesmo tempo são **33
gravações por segundo** — um terço do que o processo único já faz hoje, com p50
abaixo de 200 ms. O momento que assusta é justamente o que já está folgado.

**Fila (RabbitMQ) não entra agora, e o motivo não é preguiça:** ela transformaria
o "Salvo 20:02" numa mentira — o aluno lê que salvou enquanto a linha ainda está
na fila. Para o caderno de alguém, durabilidade agora vale mais que vazão
depois. E, se um dia a escrita apertar, há dois degraus mais baratos antes de um
broker: mandar as páginas sujas numa requisição só (o leitor já sabe quais são)
e os processos do item 1.

**Onde uma fila é a ferramenta certa nesta plataforma:** o cano da gravação
(Zoom → Vimeo), que dura minutos, falha no meio e precisa de nova tentativa. E
mesmo lá, uma tabela de trabalhos no Postgres com um cron resolve sem subir
broker nenhum.

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

## Depois dos quatro processos — a medição completa (19/09/2026)

Com o MCP num serviço próprio e o portal em `python -m app.servir portal
--workers 4`. Carga gerada **de dentro do contêiner** (sem rede, sem TLS) por
vários processos geradores, porque um gerador sozinho virava o gargalo e a
medida seria do medidor.

Um aviso de método: o gerador divide os mesmos 8 núcleos com o servidor, então
estes tetos são **piso**, não teto. Rodando de fora, o servidor mal transpirou.

### Navegação — o caminho mais apertado

Mix realista: `conteudo` (duas vezes, é a rota mais pedida), `materiais`,
`simulados`, abrir simulado, resultado, desempenho, aulas, figura, `eu`.

| em voo | req/s | p50 | p95 | erros |
|---|---|---|---|---|
| 20 | 184 | 46 ms | 417 ms | 0 |
| 60 | 153 | 211 ms | 1,3 s | 0 |
| 120 | 143 | 479 ms | 2,9 s | 0 |

Subir o número de geradores de 1 para 6 mudou pouco (129 → 148 req/s): o teto
é do servidor, não do medidor. **Este é o número que limita a plataforma hoje.**

### Entrar no portal e carregar a página

| cenário | resultado |
|---|---|
| burst de aba nova (5 chamadas de API) | 267 req/s a 198 em voo, p50 597 ms, zero erro |
| portal frio: 13 arquivos, 667 KB | **83 abas/s** a 10 em voo (p50 116 ms); 70/s a 60 em voo |

Seiscentos alunos entrando na aula: ~7,5 s para servir o portal de todos e
~11 s para as chamadas de API. Somado ao login, a turma inteira está dentro em
menos de um minuto — e na segunda visita o portal nem é baixado, por causa do
cache imutável.

### Escrever anotação — o medo da aula, medido

| em voo | gravações/s | p50 | p95 | erros |
|---|---|---|---|---|
| 24 | **562** | 40 ms | 69 ms | 0 |
| 99 | 478 | 121 ms | 526 ms | 0 |
| 198 | 369 | 343 ms | 778 ms | 0,9% |

Cem alunos riscando ao mesmo tempo pedem ~33 gravações/s; seiscentos, ~200.
Contra 560/s, é folga de quase três vezes. Com um processo eram 100/s.

### Ler a apostila

| em voo | faixas/s | MB/s | p50 | erros |
|---|---|---|---|---|
| 18 | 133 | 45 | 78 ms | 0 |
| 60 | 124 | 39 | 283 ms | 0 |
| 120 | 129 | 40 | 711 ms | 0 |

Quarenta MB por segundo saindo do Postgres, sem erro. Aqui o limite deixa de
ser nosso: vira banda de saída e a internet do aluno.

### O que o contêiner mostrou

| | antes (1 processo) | agora (4 processos) |
|---|---|---|
| pico de CPU | **1,0 de 8 vCPU** | **4,1 de 8 vCPU** |
| memória em repouso | 179 MB | 561 MB |
| memória depois de horas de teste | — | 3,0 GB, **estável** |

A memória merece nota: depois de servir muita faixa de 256 KB, cada processo
estaciona em ~750 MB e **não sobe mais** — conferido com duas rodadas extras,
que a deixaram em 3058 MB, 3061 MB, 3067 MB. Não é vazamento; é o alocador do
Python segurando o que já usou. Mas é o que decide o próximo passo: **oito
processos não caberiam** nos 8 GB deste contêiner. Para ir além de quatro,
primeiro um plano maior.

### O que não foi medido, e por quê

* **Responder simulado.** Os dois simulados publicados estão com a janela
  fechada, e abrir um só para teste mexeria em conteúdo pedagógico. A escrita
  da anotação (560/s) é a melhor aproximação que existe: mesma forma, uma linha
  por chamada. Se quiser o número exato, dá para medir com um simulado
  descartável, com sua autorização.
* **Assistir vídeo.** Não passa por aqui: o player é iframe do Vimeo.
* **MCP.** Fora do escopo por decisão sua — é um usuário só.

### Onde o próximo teto vai aparecer

1. **Navegação**, em ~150 req/s. É o caminho mais caro e o mais pedido. O ETag
   do `conteudo` já alivia quem volta; se apertar, o passo seguinte é cache de
   60 s por turma.
2. **Memória**, se alguém dobrar os processos sem dobrar o plano.
3. **Egresso**, quando a apostila (e depois a gravação das aulas) começar a
   sair em gigabytes por aula. É o CDN.

## Vale continuar na Railway?

Com os números na mão, hoje a resposta é sim, e com folga:

| | |
|---|---|
| Conta do ciclo (10/09 a 10/10) | **US$ 0,75 gastos, US$ 1,43 estimados** — dentro dos US$ 5 que o plano Hobby já inclui |
| CPU | 1% em média; **o pico foi exatamente 1,0 de 8 vCPU** — a métrica da própria Railway confirmando que o teto é de um processo, não da máquina |
| Memória | 179 MB de 8 GB |
| Egresso público | praticamente zero até agora |

Não existe problema de preço nem de escala para resolver mudando de casa: sair
agora custaria dias de trabalho para economizar centavos. O que de fato vai
mexer na conta é **egresso** — apostila hoje, gravação de aula amanhã — e a
resposta para isso não é trocar de provedor, é o CDN do item 2, que é grátis no
plano de entrada da Cloudflare e ainda absorve o tranco da entrada da aula.

Quando eu reabriria esta pergunta: se um dia forem vários processos sempre
ligados **mais** dezenas de GB de egresso por mês, um servidor de preço fixo
(Hetzner e parecidos) fica mais barato que cobrança por uso. É conversa para
quando a conta estiver em dezenas de dólares, não em US$ 1,43.

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
