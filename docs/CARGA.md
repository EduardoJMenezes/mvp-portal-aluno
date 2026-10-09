# Quantos alunos a plataforma aguenta

Medido em produção em 08/10/2026, contra `rodrigomeloplataforma.up.railway.app`,
com o curso real da turma EXTENSIVO Q1 (7 módulos, 150 linhas, árvore de 59 KB)
e a apostila de 37,5 MB no banco. A pergunta era direta: **aguenta 400 alunos ao
mesmo tempo?**

**Aguenta, e sobra.** Quatrocentos alunos virtuais, cada um com conta e sessão
próprias, fizeram 46,7 mil pedidos em sete minutos e nenhum voltou com erro. O
servidor não chegou perto do limite em nenhum cenário de uso real.

> **Resumo (tempo de servidor, sem a rede)**
>
> | cenário | carga | árvore do curso (p50 / p95 / p99) | demais rotas (p50 / p95) |
> |---|---|---|---|
> | uso normal, 400 abas abertas | 50 req/s | 27 / 37 / 43 ms | 6–10 / 15–16 ms |
> | 400 alunos entrando em 60 s | 54 req/s | 26 / 36 / 44 ms | 6–8 / 14–15 ms |
> | 400 alunos entrando em 15 s | 160 req/s | 29 / 39 / 51 ms | 6–8 / 13–16 ms |
> | apostila, 240 faixas de 256 KB em 6 s | 39 req/s, 10 MB/s | — | 14 / 22 ms |
>
> O teto da árvore do curso, a rota mais pedida, fica um pouco acima de
> **680 pedidos por segundo**. Quatrocentos alunos pedem 7 por segundo em uso
> normal e 53 no pior pico medido.

Os números de setembro (60 pedidos por segundo, quatro processos) eram da versão
em Python e não valem mais; estão no [histórico](#histórico), no fim.

## A infraestrutura de hoje

| | |
|---|---|
| Contêiner | Railway, plano Hobby, uma réplica em US East, limite de 8 vCPU e 8 GB |
| Servidor | Spring Boot 4 em Java 25, threads virtuais, heap limitado a 768 MB (`JAVA_TOOL_OPTIONS` no Dockerfile) |
| Conexões | HikariCP, até 30, 5 ociosas |
| Banco | Postgres no mesmo projeto, rede privada, volume de 279 MB (limite de 5 GB no Hobby) |
| Vídeo | **não passa por aqui**: o player é iframe do Vimeo |
| Sessão | 12 h — o aluno faz login uma vez por dia |

Assistir aula não custa nada ao nosso servidor: os bytes vêm do Vimeo. O que
custa é a aba aberta.

## O que uma aba aberta pede

Vem do frontend, não de suposição:

| o quê | quando | de onde |
|---|---|---|
| `GET /api/aluno/menu` | a cada 60 s, em toda tela do aluno | `AppShell.tsx` |
| `GET /api/aluno/conteudo` (a árvore do curso) | a cada 60 s, nas telas do curso e da aula | `useDados(..., 60)` |
| `POST /api/aluno/itens/{id}/progresso` | a cada 15 s, com o vídeo tocando | `Player.tsx` |
| navegação (simulados, agenda, desempenho…) | quando o aluno clica | — |

Quatrocentas abas numa aula são, portanto, uns **50 pedidos por segundo**: 7 da
árvore, 7 do menu, 27 de progresso e o resto de navegação. Foi exatamente isso
que a fase de uso normal reproduziu.

## A medição

Sete minutos, das 23:24 às 23:31 UTC, com um gerador de carga em Python num Mac
no Brasil. Dois relógios mediram cada pedido, e eles contam histórias
diferentes:

* **o do cliente** inclui a viagem até US East, que sozinha custa uns 180 ms por
  pedido;
* **o do servidor** é o `upstreamRqDuration` dos logs HTTP do Railway: só o
  tempo que a aplicação levou.

As tabelas abaixo trazem os dois. Para saber se o servidor aguenta, vale o
segundo; para saber o que o aluno sente, o primeiro.

### Uso normal — 400 abas por 3 minutos

| rota | pedidos | cliente p50 / p95 | servidor p50 / p95 / p99 |
|---|---|---|---|
| árvore do curso | 1 200 | 213 / 523 ms | 27 / 37 / 43 ms |
| progresso do vídeo | 4 800 | 190 / 372 ms | 10 / 16 / 21 ms |
| menu, simulados, agenda, desempenho, aulas, perfil | 2 991 | 184–197 / 208–351 ms | 6 / 15 / 22 ms |
| **total** | **8 991** (50 req/s) | 191 / 367 ms | zero erro |

### A turma entrando

Cada aluno abre o portal (6 chamadas), vai ao curso e abre a aula: 9 pedidos,
3 deles da árvore do curso, a primeira sem cache (59 KB).

| janela | carga | árvore, servidor p50 / p95 / p99 | portal aberto, no cliente |
|---|---|---|---|
| 400 em 60 s | 54 req/s | 26 / 36 / 44 ms | 1,5 s |
| 400 em 15 s | 160 req/s (53 da árvore) | 29 / 39 / 51 ms | 1,5 s |

Sem fila em nenhuma das duas. O segundo e meio que o aluno espera para ver o
portal são seis chamadas em sequência atravessando o continente, com uns 60 ms
de servidor no total: é distância, não carga.

### O teto da árvore do curso

Sem pausa entre um pedido e outro, com N pedidos em voo, 20 s por nível:

| em voo | vazão | servidor p50 / p95 / p99 |
|---|---|---|
| 25 | 120 req/s | 24 / 33 / 40 ms |
| 50 | 233 req/s | 28 / 41 / 65 ms |
| 100 | 450 req/s | 32 / 50 / 64 ms |
| 200 | 680 req/s | 94 / 161 / 211 ms |

Até 100 em voo quem limitava era a rede do gerador, não o servidor. Com 200 o
tempo de servidor triplicou: é o pool de 30 conexões enchendo e o Postgres a
1,9 vCPU. **O teto fica um pouco acima de 680 req/s**, ainda sem erro.

### Ler a apostila

Sessenta alunos, quatro faixas de 256 KB cada, em posições sorteadas do arquivo
de 37,5 MB: 240 faixas em 6 segundos (10 MB/s), servidor em p50 de 14 ms e p95
de 22 ms. Aqui o limite é a banda de quem gera a carga.

### O que o contêiner mostrou

| | parado | no teste |
|---|---|---|
| CPU do app | ~0 | 0,2 a 0,4 vCPU em uso normal; pico de 1,05 no teto |
| CPU do Postgres | ~0 | ~0,1 vCPU em uso normal; pico de 1,90 no teto |
| memória do app | 798 MB | 900 MB |
| memória do Postgres | 140 MB | 304 MB |

As métricas por minuto do Railway são amostradas — a contagem de pedidos de lá
veio menor que a do gerador —, então servem para CPU e memória, não para vazão.

## O que mudou antes do teste

Na véspera, com um usuário só em produção, a árvore do curso levava **324 ms de
mediana** (de 160 a 700). Quatro ajustes entraram no commit `37d0d0b`:

* **A árvore em consultas fixas.** Ela custava uma consulta por módulo, por
  sub-módulo e por linha: 56 com dois módulos, 176 com oito. Agora os
  sub-módulos e as linhas vêm em duas consultas (`EstruturaServico.ramos`) e o
  que é preguiçoso carrega em lote (`default_batch_fetch_size: 100`). O
  `CustoDoConteudoTest` falha se o número voltar a crescer com o curso.
* **Pool de 10 para 30 conexões.** Com threads virtuais o Tomcat não limita
  pedido em voo; o pool era o único teto.
* **PDF só em faixas.** O leitor pedia faixas, mas sem `disableStream` o pdf.js
  ignora o `disableAutoFetch` e baixa o arquivo inteiro por trás: 37,5 MB de
  saída a cada abertura da apostila.
* **Teto de heap.** A JVM subia sem `-Xmx` e tomaria até 2 GB dos 8 do
  contêiner. Com 768 MB de heap, o processo para perto de 1 GB.

O efeito, ainda com um usuário só:

| rota | antes | depois |
|---|---|---|
| árvore do curso | 324 ms | 61 ms |
| agenda | 126 ms | 23 ms |
| simulados | 68 ms | 27 ms |
| menu | 24 ms | 15 ms |

Sob carga a árvore ficou ainda mais rápida (27 ms), com a JVM aquecida.

## O que não foi medido

* **Login com senha.** As sessões do teste foram assinadas direto, então o
  bcrypt ficou de fora. O que se sabe: um login de conta antiga (hash do Python)
  levou ~400 ms em produção; conta criada pela API em Java usa o custo padrão
  do `BCryptPasswordEncoder`, mais barato. Pela conta, 400 logins em um minuto
  ocupam menos de três dos oito núcleos — mas é conta, não medição.
* **Simulado.** Abrir a prova, responder, entregar e ler o resultado. É o
  único fluxo em que a turma inteira chega no mesmo minuto por construção, e o
  único em que falhar custa caro. **É o próximo teste a fazer**, com um
  simulado descartável, antes do primeiro simulado de verdade.
* **Anotação na apostila.** Uma linha de ~2 KB por página, como o progresso do
  vídeo — que passou a 27 gravações por segundo com p50 de 10 ms.
* **Telas do professor.** É um usuário só; o que pesa ali é volume de dado
  (devolutiva de 400 alunos), não concorrência.
* **Horas seguidas.** O teste durou sete minutos. Memória ao longo de um dia de
  aula se acompanha nas métricas do Railway, sem teste.

## Onde o próximo teto vai aparecer

1. **Conexões e Postgres**, perto de 700 pedidos da árvore por segundo — cem
   vezes o uso normal de 400 alunos. Não é problema deste ano.
2. **A árvore crescendo.** Ela é montada inteira a cada pedido; o número de
   consultas não cresce mais com o curso, mas o trabalho de montar e o JSON
   crescem. Se um dia pesar, o degrau seguinte é cache por turma.
3. **Memória**, que não é teto de desempenho e sim a conta: ver abaixo.
4. **A distância.** O servidor responde em 30 ms e o aluno espera 200: a região
   é US East. É o que mais aparece na tela hoje.

## Vale continuar na Railway?

Sim. A conta é quase toda memória, e memória não cresce com aluno:

| | |
|---|---|
| Ciclo de 10/09 a 10/10/2026 | **US$ 6,73 gastos até 08/10, US$ 7,05 estimados** — memória US$ 6,52, CPU US$ 0,10, saída US$ 0,08, volume US$ 0,04 |
| Preços | US$ 10 por GB de RAM ao mês, US$ 20 por vCPU ao mês, US$ 0,05 por GB de saída, US$ 0,15 por GB de volume |
| Com 400 alunos | **US$ 17 a 21 por mês**: ~1 GB do app, 0,3 a 0,4 GB do Postgres e 0,1 GB do MCP em memória (US$ 13 a 16), CPU abaixo de US$ 2, saída de US$ 1 a 2 |

O Hobby inclui US$ 5 de uso; o Pro custa US$ 20 e inclui US$ 20, ou seja, no
Pro a conta ficaria praticamente no mínimo do plano. Um servidor de preço fixo
economizaria uns US$ 15 por mês em troca de cuidar de backup, deploy e TLS — não
compensa nesta faixa.

Quando reabrir a pergunta: se a conta passar de dezenas de dólares por causa de
**saída** (gravação de aula servida daqui, apostilas em volume) ou se o volume
do Postgres encostar nos 5 GB do Hobby, que é onde os PDFs moram.

## Como repetir o teste

O andaime não está no repositório (é medição, não produto); fica em
`~/Projects/carga-teste/`. O método:

1. `carga.py` cria N contas `carga-<n>@alunos-teste.invalid`, sem senha
   utilizável, matriculadas na turma que recebe mais módulos, e assina a sessão
   de cada uma com o `JWT_SECRET` — emprestado por `railway run -s app`, nunca
   impresso.
2. Cada aluno virtual tem conexão própria, como um navegador, e manda o
   `If-None-Match` da árvore do curso. As fases: aquecimento, uso normal,
   entrada em 60 s e em 15 s, teto com 25, 50, 100 e 200 em voo, apostila.
3. O teste só lê o curso e grava progresso de vídeo das contas de teste. Não
   responde questão, não abre simulado, não anota apostila.
4. No fim — e também se der erro no meio — apaga as contas, as matrículas e o
   progresso. Conferido no banco depois: zero contas, zero progresso órfão.
5. O banco de produção é privado: o caminho é `railway ssh -s Postgres`, que
   pede uma chave SSH registrada na conta.

Criar conta e assinar sessão em produção é decisão do dono do projeto: quem
dispara o `rodar-producao.sh` é ele. Contra `localhost` (`carga.py --local`) o
mesmo roteiro valida o andaime em um minuto.

## Histórico

As medições de 17/09 e 19/09/2026 foram feitas na versão em Python (uvicorn),
e estão no histórico do git deste arquivo. Vinham de lá o teto de 60 pedidos
por segundo com um processo, os ~150 a 185 com quatro, o pool do SQLAlchemy e a
recomendação de separar o MCP do portal — que foi feita: o adaptador MCP hoje é
outro serviço, em outro repositório.
