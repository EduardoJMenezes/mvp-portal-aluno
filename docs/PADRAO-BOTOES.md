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

## O que isto não garante

A trava é do navegador. Duas abas, ou uma requisição repetida pela rede, ainda
chegam duas vezes ao servidor. O servidor já recusa algumas repetições pela
regra de negócio (a mesma questão duas vezes no mesmo sub-módulo); garantia
geral pede chave de idempotência por requisição, que não foi feita.
