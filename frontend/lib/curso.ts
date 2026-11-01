// Os endereços do curso do aluno: o capítulo (um cartão por seção) e a seção aberta.

export const enderecoDoCapitulo = (modulo: number) => `/curso/capitulo/?modulo=${modulo}`;

export const enderecoDaSecao = (modulo: number, secao: number, item?: number) =>
  `/curso/aula/?modulo=${modulo}&secao=${secao}${item ? `&item=${item}` : ""}`;
