> **Documento de origem — o norte deste repositório.**
>
> Escrito antes de qualquer linha de código, é ele que define o objetivo, o
> escopo e os critérios de sucesso da POC. Está reproduzido aqui na íntegra e
> **sem alterações**: quando houver divergência entre o que está aqui e o que
> foi construído, este documento é a referência, e a divergência precisa de
> justificativa registrada em [ARQUITETURA.md](ARQUITETURA.md).
>
> O que foi efetivamente implementado está mapeado em
> [ARQUITETURA.md](ARQUITETURA.md#o-que-o-mvp-pediu-e-onde-está); o roteiro da
> seção 22 está destrinchado em [DEMO.md](DEMO.md).

---

# MVP --- Plataforma Educacional com Vimeo + MCP

## 1. Objetivo deste primeiro momento

O objetivo **não é construir a plataforma final**.

O objetivo desta primeira versão é construir uma **demonstração
funcional (POC/MVP)** capaz de provar, de ponta a ponta, a principal
tese do produto:

> Um professor ou gerenciador autorizado consegue usar Claude/ChatGPT
> como interface operacional da plataforma, por meio de MCP, para
> consultar conteúdo do Vimeo, cadastrar e organizar questões, montar
> simulados e consultar estatísticas; as alterações aprovadas ficam
> imediatamente refletidas na plataforma para os alunos corretos.

O **MCP é o carro-chefe da demonstração**. O frontend existe
principalmente para tornar visível que as ações executadas via MCP
realmente modificaram o sistema e que o conteúdo está corretamente
segregado por turma.

### Prioridades da POC

1.  Integração com Vimeo.
2.  MCP próprio da plataforma.
3.  Segurança/autorização do MCP.
4.  Fluxo rascunho → revisão → aprovação → publicação.
5.  Conteúdo segregado por turma.
6.  Simulado respondido pelo aluno.
7.  Estatísticas consultáveis via MCP.

Não devemos tentar construir neste momento todas as funcionalidades
imaginadas para o produto final.

------------------------------------------------------------------------

## 2. Problema

Hoje existem milhares de resoluções de questões no Vimeo, organizadas
historicamente por ano e capítulo. Todos os anos existe trabalho manual
para decidir o que reaproveitar, reorganizar capítulos e questões,
reutilizar vídeos antigos, cadastrar conteúdo novamente, relacionar
vídeos às questões, disponibilizar tudo para a turma correta, criar
simulados e posteriormente analisar o desempenho.

A POC deve provar que esse processo pode ser centralizado e operado com
auxílio de IA através de MCP.

------------------------------------------------------------------------

## 3. Arquitetura

``` text
                         PROFESSOR / GERENCIADOR
                                  |
                    +-------------+-------------+
                    |                           |
                    v                           v
             Frontend Admin             Claude / ChatGPT
                                                |
                                                | MCP
                                                v
                                      +-------------------+
                                      | MCP da Plataforma |
                                      +---------+---------+
                                                |
                                                v
+----------------+                     +-------------------+
| Frontend Aluno | ------------------> |      Backend      |
+----------------+                     +---------+---------+
                                                |
                              +-----------------+----------------+
                              |                                  |
                              v                                  v
                         PostgreSQL                          Vimeo API
```

O backend é o **mesmo backend** utilizado pelo frontend e pelo MCP.

``` text
REST Controller ---\
                    > Application/Domain Services -> PostgreSQL / Vimeo
MCP Tools ---------/
```

O MCP nunca acessa diretamente o PostgreSQL.

### Responsabilidades

**PostgreSQL:** fonte de verdade dos dados: usuários, turmas,
matrículas, capítulos, questões, alternativas, classificações,
referências Vimeo, simulados, tentativas, respostas e status.

**Backend:** fonte de verdade das regras: autenticação, autorização,
segregação por turma, workflow editorial, Vimeo, questões, simulados,
respostas e estatísticas.

**Frontend:** duas experiências mínimas na POC: Admin/Gerenciador e
Aluno.

**MCP:** interface administrativa alternativa para o mesmo domínio do
backend.

**Vimeo:** biblioteca/repositório dos vídeos. A plataforma guarda os
IDs/metadados necessários para relacionar vídeos a questões.

------------------------------------------------------------------------

## 4. Roles

``` text
ADMIN
GERENCIADOR
ALUNO
```

### ADMIN

Administra o sistema e pode utilizar o MCP.

### GERENCIADOR

Pessoa designada pelo administrador/professor. Pode operar a plataforma
e utilizar o MCP conforme suas permissões.

No futuro, considerar scopes como `questions:write`,
`simulations:write`, `vimeo:read`, `analytics:read` etc.

### ALUNO

Não acessa o MCP administrativo. Usa apenas a plataforma, visualiza
conteúdo das turmas em que está matriculado, responde simulados e acessa
somente seus dados permitidos.

**Toda autorização deve ser validada no backend.** Ocultar elementos no
frontend não é segurança.

------------------------------------------------------------------------

## 5. Autenticação do MCP

O MCP não permite execução anônima.

``` text
Claude / ChatGPT
       |
       | autenticação/autorização
       v
Servidor de autorização
       |
       | identidade + permissões
       v
MCP
       |
       v
Backend
```

Na arquitetura real, seguir o padrão de autorização vigente do MCP para
servidores HTTP/OAuth.

Uma simplificação de autenticação pode ser aceita exclusivamente na POC
se necessária para velocidade, mas deve ficar documentada como
simplificação e não como arquitetura final.

------------------------------------------------------------------------

## 6. Regra fundamental: IA não publica diretamente

``` text
IA propõe
    |
    v
DRAFT
    |
    v
Professor revisa
    |
    v
Professor aprova
    |
    v
Backend publica
```

Estados conceituais futuros:

``` text
DRAFT -> REVIEW -> APPROVED -> PUBLISHED -> ARCHIVED
```

Na POC, no mínimo:

``` text
DRAFT -> confirmação humana -> PUBLISHED
```

Não confiar apenas em prompts dizendo ao modelo para pedir confirmação.
A garantia deve existir no backend.

As instruções do MCP devem reforçar:

> Nunca publique ou aplique definitivamente alterações em conteúdo
> pedagógico sem aprovação explícita do usuário. Sempre apresente
> primeiro um rascunho/resumo para revisão.

Quando suportado pelo cliente, usar elicitation/confirmação para
operações críticas. Ainda assim, a proteção definitiva continua no
backend.

------------------------------------------------------------------------

## 7. Dados da demonstração

Não importar o acervo inteiro.

Usar aproximadamente:

``` text
Turmas:
- Extensivo 2026
- Extensivo 2027

Capítulos:
- Atomística
- Estequiometria
- Cinética

Questões:
- 10 a 20

Alunos:
- João -> Extensivo 2027
- Maria -> Extensivo 2027
- Pedro -> Extensivo 2026
```

Dados de demonstração são suficientes.

------------------------------------------------------------------------

## 8. Demonstração: Vimeo

Queremos demonstrar que o agente consegue consultar o Vimeo.

Idealmente utilizar o MCP oficial do Vimeo para as operações que ele
efetivamente expuser. Não assumir que toda manipulação necessária de
folders está disponível no MCP oficial. Quando necessário, nossa
integração backend poderá utilizar a API REST oficial do Vimeo.

Exemplo:

> Veja no meu Vimeo os vídeos disponíveis para o Extensivo 2027.

------------------------------------------------------------------------

## 9. Demonstração principal: Vimeo + nosso MCP

``` text
                 Claude
                  /   \
                 /     \
                v       v
          Vimeo MCP   Nosso MCP
              |           |
              v           v
            Vimeo       Backend
                           |
                           v
                       PostgreSQL
```

Exemplo:

> Veja os vídeos da pasta/capítulo X no Vimeo e cadastre essas questões
> como rascunho no Extensivo 2027.

Fluxo:

1.  agente consulta Vimeo;
2.  identifica os vídeos;
3.  chama nosso MCP;
4.  backend cria registros/relações;
5.  retorna preview;
6.  nada é publicado;
7.  professor revisa;
8.  professor confirma;
9.  backend publica.

Retorno conceitual:

``` text
Rascunho criado.

Turma: Extensivo 2027
Capítulo: Estequiometria

5 questões encontradas
5 vídeos associados
0 erros

Nenhuma questão foi publicada.
Revise antes de publicar.
```

------------------------------------------------------------------------

## 10. Frontend Admin

O frontend administrativo é propositalmente simples. Sua função
principal é mostrar que as ações via MCP realmente aconteceram.

``` text
Dashboard
   |
   +-- Turmas
          |
          +-- Capítulos
                  |
                  +-- Questões
                          |
                          +-- Vídeo Vimeo
```

Exemplo:

``` text
Extensivo 2027
Estequiometria

Q01  vídeo Vimeo
Q02  vídeo Vimeo
Q03  vídeo Vimeo
Q04  vídeo Vimeo
Q05  vídeo Vimeo
```

Não construir um CMS administrativo completo nesta POC.

------------------------------------------------------------------------

## 11. Frontend Aluno e segregação por turma

Login:

``` text
João
Turma: Extensivo 2027
```

João vê:

``` text
Extensivo 2027

Estequiometria
├── Questão 01 -> Resolução
├── Questão 02 -> Resolução
├── Questão 03 -> Resolução
├── Questão 04 -> Resolução
└── Questão 05 -> Resolução
```

Vídeos podem usar Vimeo embed.

Depois logar como:

``` text
Pedro
Turma: Extensivo 2026
```

Pedro **não pode visualizar** conteúdo exclusivo do Extensivo 2027.

A filtragem ocorre no backend.

------------------------------------------------------------------------

## 12. Estrutura mínima de uma questão

``` text
Questão
├── enunciado
├── alternativas A-E
├── gabarito
├── tópico
├── subtópico
├── dificuldade
├── vídeo Vimeo (opcional)
└── status
```

Não é necessário definir toda a taxonomia pedagógica final na POC.

------------------------------------------------------------------------

## 13. Criar simulado via MCP

Exemplo:

> Monte um simulado para o Extensivo 2027 utilizando as questões 1, 3 e
> 5 de Estequiometria.

Tool conceitual:

``` text
criar_simulado_rascunho(...)
```

Resultado:

``` text
Simulado criado como RASCUNHO

Revisão de Estequiometria

Q01
Q03
Q05

Turma: Extensivo 2027

Nenhuma alteração foi publicada.
```

Professor revisa e confirma a publicação. Após publicação, o simulado
aparece para os alunos daquela turma.

------------------------------------------------------------------------

## 14. Aluno respondendo simulado

``` text
Simulados

Revisão de Estequiometria
3 questões
```

Tela mínima:

``` text
Questão 1/3

[enunciado]

( ) A
( ) B
( ) C
( ) D
( ) E

Próxima
```

Persistir pelo menos:

``` text
aluno
simulado
questao
alternativa_marcada
correta
timestamp
```

Telemetria avançada fica para depois.

------------------------------------------------------------------------

## 15. Estatísticas via MCP

Depois das respostas:

> Como João foi no último simulado?

Tool:

``` text
buscar_desempenho_aluno(...)
```

Depois:

> Como a turma foi?

Tool:

``` text
buscar_estatisticas_simulado(...)
```

Exemplo:

``` text
Simulado: Revisão de Estequiometria

Alunos: 2
Média: 50%

Q01: 50% de acerto
Q03: 100% de acerto
Q05: 0% de acerto

Maior dificuldade:
Reagente limitante
```

O objetivo é provar que o LLM consulta dados reais produzidos pelos
alunos e os transforma em análise em linguagem natural.

Não precisamos de dashboard avançado de analytics na POC.

------------------------------------------------------------------------

## 16. Funcionalidade opcional: questão por imagem

Se houver tempo:

Professor envia print de uma questão ao Claude/ChatGPT:

> Cadastre essa questão para o Extensivo 2027.

O modelo interpreta enunciado/alternativas e chama:

``` text
criar_questao_rascunho(...)
```

A questão nunca é publicada automaticamente.

Questões autorais geradas por IA e geração de imagens fazem parte da
visão futura e **não são requisito desta POC**.

------------------------------------------------------------------------

## 17. Tools MCP sugeridas

Manter poucas tools orientadas ao domínio.

### Consulta

``` text
listar_turmas()
buscar_questoes()
buscar_desempenho_aluno()
buscar_estatisticas_simulado()
```

### Escrita em rascunho

``` text
criar_questao_rascunho()
importar_questoes_vimeo()
criar_simulado_rascunho()
```

### Publicação

``` text
publicar_rascunho()
```

As tools devem ter nomes claros, descriptions detalhadas, schemas claros
e annotations adequadas. O backend continua validando autorização e
regras.

------------------------------------------------------------------------

## 18. Modelo de dados mínimo

``` text
users
roles

classes
enrollments

chapters

questions
question_options
question_classifications

videos

class_questions

exams
exam_questions

exam_attempts
exam_answers
```

O mesmo vídeo/questão deve poder ser reutilizado em anos diferentes.

``` text
                  Questão X
                     |
                  Vídeo Vimeo
                  /          \
                 /            \
          Extensivo 2026   Extensivo 2027
           Cap. 2 Q17       Cap. 4 Q31
```

A organização anual pertence à plataforma, e não exclusivamente às
folders do Vimeo.

------------------------------------------------------------------------

## 19. Princípios de implementação

### Backend único

``` text
REST Controller ---\
                    -> Application Service
MCP Tool ----------/
```

### MCP não acessa banco diretamente

``` text
MCP -> Application/Domain Service -> Repository
```

### Autorização no servidor

Validar usuário, role/permissão, acesso ao recurso e estado.

### Segregação por turma no backend

Nunca confiar somente no frontend.

### IA como interface, não autoridade

O LLM interpreta intenção e solicita operações. O backend decide se são
válidas.

------------------------------------------------------------------------

## 20. Fora do escopo da POC

Não gastar tempo inicialmente com:

-   importar \~3.000 vídeos;
-   migrar todo o acervo 2022--2027;
-   CMS administrativo completo;
-   dashboard sofisticado;
-   analytics avançado;
-   pagamentos;
-   notificações;
-   recuperação de senha sofisticada;
-   multi-tenancy completo;
-   geração autoral de questões por IA;
-   geração de imagens por IA;
-   taxonomia pedagógica definitiva;
-   auditoria completa;
-   rollback sofisticado;
-   versionamento completo;
-   filas/jobs complexos;
-   permissões extremamente granulares;
-   múltiplas modalidades de prova;
-   design visual final.

A arquitetura não deve impedir essas evoluções, mas elas não devem
atrasar a demonstração.

------------------------------------------------------------------------

## 21. Critérios de sucesso

### Fluxo A --- Vimeo -\> plataforma

``` text
LLM
-> consulta Vimeo
-> identifica vídeos
-> chama nosso MCP
-> cria conteúdo
-> professor aprova
-> conteúdo aparece na plataforma
```

### Fluxo B --- segregação

``` text
Aluno A -> Turma 2027 -> vê conteúdo 2027
Aluno B -> Turma 2026 -> não vê conteúdo exclusivo 2027
```

### Fluxo C --- simulado

``` text
Professor pede via MCP
-> rascunho
-> aprovação
-> publicação
-> aluno recebe
-> aluno responde
-> respostas persistidas
```

### Fluxo D --- analytics

``` text
respostas
-> backend
-> estatísticas
-> MCP
-> Claude/ChatGPT
-> análise em linguagem natural
```

**Se esses quatro fluxos funcionarem de ponta a ponta, a POC cumpriu seu
objetivo.**

------------------------------------------------------------------------

## 22. Roteiro da apresentação

1.  Mostrar os vídeos no Vimeo.
2.  Abrir Claude/ChatGPT.
3.  Perguntar pelos vídeos e demonstrar a integração Vimeo.
4.  Pedir: "Cadastre essas questões no Extensivo 2027."
5.  Mostrar nosso MCP sendo utilizado.
6.  Mostrar que foi criado um rascunho, não uma publicação automática.
7.  Revisar e aprovar.
8.  Abrir visão Admin e mostrar as questões cadastradas.
9.  Entrar como aluno 2027 e mostrar questões/vídeos.
10. Entrar como aluno 2026 e provar segregação.
11. Voltar ao LLM e pedir a criação de um simulado.
12. Revisar e publicar.
13. Entrar como aluno e responder.
14. Voltar ao LLM.
15. Perguntar "Como esse aluno foi?"
16. Perguntar "Onde a turma teve mais dificuldade?"
17. Mostrar a análise baseada nos dados reais da plataforma.

------------------------------------------------------------------------

## 23. Mensagem principal

A apresentação não deve transmitir apenas:

> "Construímos uma plataforma de questões."

A mensagem é:

> **Transformamos Claude/ChatGPT em uma interface segura para operar o
> ecossistema pedagógico do professor.**

O professor pode dizer:

``` text
"Pegue essas questões do Vimeo."
"Use no Extensivo 2027."
"Monte um simulado."
"Publique depois que eu aprovar."
"Como a turma foi?"
"Em que assunto meus alunos estão tendo dificuldade?"
```

O agente coordena ferramentas. A plataforma continua responsável pelos
dados, regras, segurança, autorização, publicação e experiência do
aluno.

------------------------------------------------------------------------

## 24. Visão futura --- não implementar agora

Depois da POC:

-   reaproveitamento inteligente entre anos;
-   planejamento anual assistido por IA;
-   identificação de questões que precisam de novos vídeos;
-   histórico completo de utilização;
-   questões autorais geradas com IA;
-   geração de imagens;
-   classificação automática;
-   analytics pedagógico avançado;
-   lacunas de aprendizagem;
-   banco histórico completo;
-   versionamento;
-   auditoria;
-   rollback;
-   permissões granulares;
-   múltiplos professores/cursinhos.

------------------------------------------------------------------------

## 25. Ordem sugerida de desenvolvimento

``` text
1. Estrutura do projeto
2. PostgreSQL + schema mínimo
3. Backend + autenticação simples
4. Turmas / alunos / matrículas
5. Capítulos / questões / vídeos
6. Frontend Admin mínimo
7. Frontend Aluno mínimo
8. Integração Vimeo
9. MCP server
10. Tools de consulta
11. Tools de criação de rascunho
12. Workflow aprovação/publicação
13. Simulados
14. Respostas
15. Estatísticas
16. Tools MCP de analytics
17. Polimento do roteiro da demo
```

------------------------------------------------------------------------

## 26. Regra para o Claude Code durante o desenvolvimento

Quando houver dúvida entre:

-   adicionar uma funcionalidade nova; ou
-   tornar o fluxo `MCP -> Backend -> Plataforma -> Aluno` mais
    confiável;

**priorizar sempre o segundo.**

A primeira versão existe para **provar a arquitetura e a experiência
MCP**, não para ser a versão comercial completa do produto.

Antes de expandir escopo, garantir que os quatro fluxos dos critérios de
sucesso estejam funcionando de ponta a ponta.
