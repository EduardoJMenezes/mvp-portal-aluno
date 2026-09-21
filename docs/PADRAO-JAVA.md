# Padrão do projeto Java

**Estado:** esqueleto construído e testado em `api/` — 12 testes contra Postgres
18 real, todos verdes. Aguardando aprovação para replicar nos outros comandos.

A tese: **o que hoje é convenção vira coisa que o compilador cobra.** O
[CLAUDE.md](../CLAUDE.md) tem regras que dependem de disciplina — e diz que uma
delas "morde em silêncio". Em Java elas deixam de depender de quem escreve.

O código é a fonte da verdade do padrão. Este documento explica o porquê e
aponta os arquivos; não repete código, porque código repetido em documento
diverge — foi exatamente assim que a primeira versão dele ficou errada (ver o
fim).

---

## Onde mora

```
mvp-portal-aluno/
├── api/        Spring Boot: dono das regras e do schema
├── mcp/        hoje backend/ — vira só o adaptador FastMCP
├── frontend/   inalterado
└── docs/
```

O `git mv backend mcp` é o primeiro commit da migração. Custa 8 referências
(2 no CI, 3 no `Dockerfile`, 3 no `pyproject.toml`), e o git rastreia o rename.
O nome casa com o serviço `mcp` que já está na Railway.

## Stack — conferida nos jars, não de memória

| peça | versão | nota |
|---|---|---|
| Java | 25 LTS (Temurin) | |
| Spring Boot | 4.1.1 | starters modulares: `-webmvc`, `-flyway`, testes por módulo |
| Hibernate | **7**.4 | não 6 |
| Jackson | **3**.1 | pacote `tools.jackson`, não `com.fasterxml` |
| Flyway | 11 | Postgres em módulo à parte: `flyway-database-postgresql` |
| Testcontainers | 2 | `org.testcontainers.postgresql.PostgreSQLContainer` |
| Build | Maven wrapper 3.3.4 | ninguém instala Maven |

## Rodar

```bash
cd api && ./mvnw verify
```

Precisa do Docker aberto: o Testcontainers sobe um Postgres 18 por teste.

---

## O que o compilador passa a cobrar

| regra do CLAUDE.md | hoje, no Python | no Java | onde |
|---|---|---|---|
| o MCP não fala com o banco | regra escrita | repositório **package-private**: fora do pacote nem se cita o tipo | `estrutura/ModuloRepositorio.java` |
| não esquecer o filtro do removido | `selecionar()` em vez de `select()` | `@SQLRestriction("removido_em IS NULL")` na entidade | `estrutura/Modulo.java` |
| todo erro de domínio vira status | convenção de cada borda | `sealed` + `switch` sem `default`: tipo novo não compila | `comum/ErroDominio.java`, `comandos/TratadorDoComando.java` |
| módulo só nasce pelas regras | convenção | construtor package-private | `estrutura/Modulo.java` |
| senha nunca vaza da entidade | cuidado | `senha_hash` nem é mapeada na entidade da segurança | `contas/Usuario.java` |

**Pacote por assunto, não por camada** (`estrutura/`, `catalogo/`, `contas/`),
porque é isso que permite o repositório ser package-private. Com
`entidades/ servicos/ controllers/`, ele teria que ser público.

O `@SQLRestriction` já se mostrou mais consistente que o `selecionar()`: o
próprio docstring do `selecionar()` avisa que ele não alcança agregações, e o
`_proxima_ordem` do Python usa `func.max` — então lá a ordem conta módulos
removidos. No Java, `maiorOrdem` os ignora. **Diferença de comportamento
conhecida:** remover o último módulo e criar outro dá ordem 5 no Java e 6 no
Python. Só afeta a ordenação, que continua correta.

O preço do `@SQLRestriction`: ver o removido (restaurar) passa a exigir query
nativa.

---

## Autorização dos comandos

[`seguranca/FiltroDoComando.java`](../api/src/main/java/br/com/plataforma/seguranca/FiltroDoComando.java),
nesta ordem:

| # | confere | se falhar |
|---|---|---|
| 1 | `X-Servico` bate com `SERVICO_TOKEN`, em tempo constante | 401 |
| 2 | `X-Operador` é o id de um usuário que existe | 401 |
| 3 | o papel desse usuário, **relido do banco a cada comando**, é de operador | 403 |
| 4 | o canal é fixo: `MCP` — não há cabeçalho para o chamador escolher | — |

Recusa devolve só o status; o motivo vai para o log. Quem erra aqui configurou
mal o deploy ou está sondando, e a resposta não deve dizer se o id existe.

`SERVICO_TOKEN` é validado na partida: vazio ou com menos de 32 caracteres, a
aplicação não sobe. Melhor um deploy que falha que uma porta aberta com token
vazio.

**O que isto garante, honestamente.** O Java confia no MCP para dizer *quem* é
o operador — quem autentica a pessoa é o MCP, pelo GitHub. O Java garante que
(a) o pedido veio mesmo do MCP e (b) essa pessoa é operadora **agora**. Um MCP
comprometido pode agir como qualquer operador que exista, mas não promove
aluno, não sobrevive a um rebaixamento e não se passa pelo portal. É o mesmo
raio de estrago de hoje.

---

## Erros: duas bordas, dois comportamentos

O tratador dos comandos entrega a mensagem de domínio **inteira** — do outro
lado está um modelo, que lê para se corrigir. Erro de validação diz qual campo
falta, em português. O tratador do portal, quando existir, é outro e esconde.
Escopo por `basePackageClasses`, sem classe marcadora.

Status: `NaoEncontrado` 404, `NaoAutorizado` 403, `RegraDeNegocio` 400 (a casa
responde 400, não 422).

---

## O schema

[`V1__schema_atual.sql`](../api/src/main/resources/db/migration/V1__schema_atual.sql)
**não foi escrita à mão.** É o `migracoes.py` do Python aplicado num Postgres
18 vazio e extraído com `pg_dump --schema-only`. Aplicada de volta num banco
vazio, foi comparada ao original pelo catálogo: **799 itens — 232 colunas, 57
índices, 278 constraints, 232 storages — idênticos.** Os 6 índices parciais e o
`EXTERNAL` da coluna do PDF estão lá.

Do dump saíram só duas coisas que o Flyway não digere: os meta-comandos
`\restrict`/`\unrestrict` que o pg_dump 18 passou a emitir, e o
`set_config('search_path', '', false)`, que zeraria o search_path da sessão do
próprio Flyway.

`baseline-version: 1`: em produção a V1 **não roda** (o banco existe e é só
marcado); em banco vazio, roda. Antes de o Java apontar para produção, compare
a V1 com um `pg_dump` de lá — se divergir, é produção que manda.

---

## Minas que quebram em silêncio

1. **`ddl-auto` fica em `validate`.** O Hibernate não conhece índice parcial
   nem `EXTERNAL`; com `update`, "consertaria" o schema por cima.
2. **O PDF não é `@Lob byte[]`.** Carregaria os 37 MB a cada faixa. A coluna é
   `materials.conteudo`, e só uma query nativa com `substring` a toca; a
   entidade não a mapeia.
3. **Testcontainers, nunca H2.** Índice parcial e `substring` sobre bytea não
   existem lá do mesmo jeito.
4. **O agente do Mockito é declarado no `pom`.** O aviso de agente dinâmico
   aparece mesmo sem teste usar mock — a infraestrutura do Boot toca o
   Mockito. Precisa do goal `dependency:properties`, senão
   `${org.mockito:mockito-core:jar}` fica sem valor.
5. **Sem usuário em memória.** O Spring Security cria um e imprime a senha
   gerada no log. Excluído pela classe (`UserDetailsServiceAutoConfiguration`),
   não por string: se mudar de pacote, a compilação acusa.
6. **`open-in-view: false`.** Carregamento preguiçoso fora da transação tem que
   falhar alto.
7. **Build com `clean` depois de mexer em arquivo por fora.** Restaurar um
   fonte com `mv` devolve a data antiga, e o Maven não recompila — o teste roda
   contra a classe velha. Aconteceu durante a construção deste esqueleto.

---

## Os testes provam cada afirmação

[`CriarModuloTest.java`](../api/src/test/java/br/com/plataforma/comandos/CriarModuloTest.java)
— HTTP → filtro → comando → serviço → Postgres real:

| afirmação | teste |
|---|---|
| comando cria módulo e os dois sub-módulos, em snake_case | `criaOModuloComOsDoisSubmodulosDeSempre` |
| mensagem de domínio chega inteira | `nomeRepetidoNaMesmaTurmaVoltaComAMensagemInteira` |
| `@SQLRestriction` + índice parcial liberam o nome | `moduloRemovidoLiberaONome` |
| erro lista o que existe | `turmaQueNaoExisteListaAsQueExistem` |
| **um comando é uma transação** | `subModuloRecusadoDesfazOModuloJunto` |
| validação diz o campo | `pedidoSemTurmaDizQualCampoFalta` |
| sem token / token errado | `semTokenDeServicoNemEntra`, `tokenDeServicoErradoNemEntra` |
| aluno não opera | `alunoNaoOperaMesmoComOTokenCerto` |
| **papel relido do banco** | `operadorRebaixadoPerdeOAcessoNoComandoSeguinte` |
| o resto está fechado | `foraDosComandosSoOHealthResponde` |

O teste de transação **foi provado capaz de falhar**: sem o `@Transactional` no
endpoint, ele quebra com o módulo órfão no banco (`expected: 0, but was: 1`).

---

## O que construir corrigiu na primeira versão deste documento

| a primeira versão dizia | o real | consequência se não fosse corrigido |
|---|---|---|
| `@Table(name = "modulos")` | tabelas em inglês: `modules`, `classes`, `submodules` | `validate` não deixaria subir |
| ids `Long` | colunas `integer` → `Integer` | `validate` recusaria o tipo |
| `baseline-version: 0` | **`1`** | a V1 rodaria em produção e o deploy cairia no `CREATE TABLE` |
| coluna do PDF `arquivo` | `conteudo` | query de faixa quebraria |
| `X-Operador` como JWT | id simples + papel do banco | o MCP teria o segredo e cunharia o JWT: segurança de enfeite |
| `X-Canal` em cabeçalho | canal fixo em `MCP` | um cabeçalho a menos para mentir |
| `./mvnw -f api/pom.xml` no CI | `working-directory: api` | o wrapper mora em `api/`, não na raiz |
| agente do Mockito só com `argLine` | + `dependency:properties` | variável sem valor, JVM não sobe |
| bean `Auditoria` | `tocar()` na classe base | uma injeção a menos em todo serviço |

---

## CI — terceiro job, quando aprovado

```yaml
  api:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: api
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25', cache: maven }
      - run: ./mvnw -B verify
```

O runner do GitHub tem Docker, então o Testcontainers funciona lá.

## Para aprovar

1. O padrão como está no esqueleto — pacote por assunto, repositório
   package-private, `@SQLRestriction`, `@Transactional` no comando, dois
   tratadores.
2. A autorização corrigida: id simples + papel do banco + canal fixo.
3. `br.com.plataforma` como pacote raiz.
4. A diferença de ordem após remoção (Java preenche o buraco; Python não).
