# Padrão de botões

Vale para o portal inteiro (`frontend/`), professor e aluno. As peças estão em
`frontend/components/ui.tsx`, e a vitrine, em `/admin/componentes/`.

## A regra

> Tudo o que faz alguma coisa é botão, com forma de botão. Palavra colorida
> solta na tela não é botão.

Antes desta regra havia 56 lugares em que só a cor azul dizia "clique aqui":
"Renomear", "Tirar", "Nova senha", "Abrir no editor, em outra aba". Quem olha
não tem como saber o que é texto e o que é ação.

| O que é | Peça | Exemplo |
|---|---|---|
| Faz alguma coisa: grava, abre, troca, tira | `<Botao>` | Salvar, Trocar, Tentar de novo |
| Só um ícone | `<BotaoIcone rotulo icone>` | fechar, remover, tocar |
| Leva a outra tela | `<BotaoLink>` | Abrir, Editar questão #12 |
| "Ver todos" no título de uma seção | `<LinkDeSecao>` | Ver todos, Curso completo |
| Link que faz parte de uma frase | `<LinkNoTexto>` | "Cadastre em Assuntos" |
| O título que leva ao item | `<LinkDeTitulo>` | o nome do simulado, do PDF |
| Mostrar e esconder | `<Revelar>` | Ver a questão / Recolher |

Os três últimos são os únicos casos em que texto continua sendo texto, e cada
um tem a sua marca: o link da frase é **sempre sublinhado** (dentro de um
aviso, `herdaCor` mantém a cor do aviso), o título é o texto principal da
linha, e o `Revelar` nunca aparece sem a setinha. A trilha de pastas e o
"mostrar senha" entram no mesmo espírito.

Não existe mais a variante `texto` do `Botao`: era a porta por onde a palavra
colorida entrava.

## Variantes e tamanhos

* `primario`: a ação principal da tela ou do painel. **Uma só.**
* `secundario`: a segunda ação mais importante (contorno azul).
* `neutro` (o padrão): as demais, e o "Cancelar".
* `perigo`: remove ou tira. Sempre com confirmação antes.
* `discreto`: sem borda, para barra de ferramentas ("Atualizar").

Tamanhos: `normal`, `pequeno` e `mini`. O `mini` é para a ação que mora dentro
de uma linha: ao lado de um valor ("Sem categoria [Alterar]") ou numa tabela.

Em linha de tabela, a ação principal é um botão `mini`; havendo três ou mais,
as outras vão para o menu "⋯" (`<Menu>`), como em Alunos.

`BotaoLink` para endereço de fora da plataforma abre em outra aba sozinho e
mostra o ícone; para abrir ao lado uma tela nossa, `outraAba`.

## O clique

**O botão afunda ao ser pressionado.** É o aviso de que o clique chegou. Com
"reduzir movimento" ligado no sistema, não afunda.

**Uma ação de cada vez.** Enquanto a ação roda, o botão gira e não aceita
outro clique. Quem garante é o `useAcao`, com uma trava que é referência e não
estado: o segundo clique é recusado mesmo que chegue no mesmo instante, antes
de a tela redesenhar. Há três jeitos de ligar, do mais automático ao mais
manual:

```tsx
// 1. O onClick devolve a promessa: o botão se trava sozinho.
<Botao onClick={() => remover(aluno)}>Remover</Botao>

// 2. O formulário: o envio roda uma vez de cada vez, e o botão de enviar de
//    dentro gira sozinho. Enter repetido não manda de novo.
<Formulario onSubmit={salvar}>
  <input ... />
  <Botao type="submit" variante="primario">Salvar</Botao>
</Formulario>

// 3. O botão não vê a ação começar (está fora do <form>, no rodapé do modal):
<Botao type="submit" form={id} ocupado={salvando}>{salvando ? "Salvando…" : "Salvar"}</Botao>
```

Armadilha do primeiro jeito: `onClick={() => void remover(a)}` esconde a
promessa do botão, e a trava não liga. **Sem `void` no `onClick` de um
`Botao`.**

Ocupado não é `disabled`: o botão continua com a cor dele e com o foco do
teclado (num modal, perder o foco joga a pessoa para fora do formulário).
`disabled` fica para "ainda não dá para clicar" (falta preencher um campo).

## A segunda tranca, no servidor

A trava do botão é do navegador. Duas abas, um clique que escapou ou um pedido
repetido no caminho ainda chegam ao servidor em dobro. Lá, quem segura é o
`FiltroDaRepeticao` (`portal/`, com as regras em `Repeticoes`): a escrita
idêntica — mesma pessoa, mesmo método, mesmo endereço, mesmo corpo — não roda
de novo em dois casos.

* **A primeira ainda está rodando.** A segunda espera e recebe a mesma
  resposta. Passados 30 s sem resposta, recebe 409.
* **A primeira acabou de dar certo** (há menos de 2 s) **e a pessoa não
  escreveu mais nada depois.** A segunda recebe a resposta guardada.

A resposta repetida sai com o cabeçalho `X-Repetida: 1`, e é a da primeira,
byte a byte: para o portal, é como se o segundo pedido tivesse dado certo.

"Sem nada no meio" é o que separa o clique repetido da intenção de fazer de
novo. Adicionar, remover e adicionar outra vez são três pedidos, e o terceiro
roda. Marcar, desmarcar e marcar também. Só o pedido igual, logo em seguida, é
respondido de memória. E só o que deu certo fica guardado: o pedido recusado
pode ser tentado de novo à vontade.

O que fica de fora:

* **Leitura** (`GET`): nunca é repetição.
* **Envio de arquivo** (PDF, figura, foto do módulo): o navegador sorteia o
  separador das partes, e dois envios do mesmo arquivo nunca têm o mesmo
  corpo. Ali a proteção é só o botão.
* **Escrita sem sessão**, com uma exceção: a compra (`/api/vendas/planos/…/comprar`),
  em que quem repete é reconhecido pelo IP, para o clique duplo não abrir dois
  checkouts no Asaas. Login não entra: a resposta dele leva o cookie, e
  repeti-la sem o cookie deixaria o segundo navegador de fora.
* **`/comandos/**`** (o Claude, pelo MCP): fora do filtro.

**Mora na memória do processo.** A janela é de segundos e a API roda numa
instância só (conferido no Railway em 10/10/2026: `numReplicas` 1). Com mais de
uma réplica, cada uma teria a sua memória e a garantia valeria só para pedidos
que caíssem na mesma: aí `Repeticoes` precisa ir para o banco.

**Nos testes a resposta de memória fica desligada** (`BaseDeComando` zera a
janela): eles repetem o mesmo pedido de propósito, para ver a regra de negócio
recusar o segundo. Quem testa a repetição liga a janela, como o
`RepeticaoTest`.
