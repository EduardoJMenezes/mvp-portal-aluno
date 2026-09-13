import katex from "katex";
import "katex/contrib/mhchem";
import "katex/dist/katex.min.css";
import { Marked } from "marked";
import { useEffect, useMemo, useRef } from "react";
import { api } from "./api";

// Enunciado, alternativas e resolução chegam como Markdown, com tabela, fórmula
// em LaTeX entre $...$ (química em \ce{}) e figuras referenciadas no ponto onde
// aparecem: ![](figura:123). Ver docs/IMPORTADOR-SIMULADO.md.

const escapar = (texto: string) =>
  texto.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

// Questão não carrega HTML nem link: seriam só um jeito de injetar coisa na
// tela do aluno. Imagem, só a figura da própria questão.
const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    html({ text }) { return escapar(text); },
    link({ tokens }) { return this.parser.parseInline(tokens); },
    image({ href, text }) {
      if (href === "figura:pendente") return '<span class="figura-pendente">figura pendente</span>';
      const figura = /^figura:(\d+)$/.exec(href);
      return figura ? `<img class="figura" data-figura="${figura[1]}" alt="Figura da questão">` : escapar(text);
    },
  },
});

// $$bloco$$ ou $linha$. O `$` colado no texto é o que separa fórmula de preço
// ("R$ 50 e R$ 60" não vira fórmula), e `\$` é um cifrão de verdade.
const FORMULA = /(?<!\\)\$\$([\s\S]+?)(?<!\\)\$\$|(?<!\\)\$(?!\s)([^$\n]+?)(?<!\s|\\)\$/g;

export function paraHtml(texto: string): string {
  // A fórmula sai antes do Markdown, senão o `_` e o `*` dela viram itálico.
  const formulas: string[] = [];
  const semFormulas = texto.replace(FORMULA, (_, bloco?: string, linha?: string) => {
    formulas.push(
      katex.renderToString(bloco ?? linha ?? "", {
        displayMode: bloco !== undefined,
        throwOnError: false,
      }),
    );
    return `%%F${formulas.length - 1}%%`;
  });
  return (markdown.parse(semFormulas) as string).replace(
    /%%F(\d+)%%/g, (_, i: string) => formulas[Number(i)],
  );
}

// A figura só sai com o token — antes de a prova abrir ela adiantaria a
// questão —, então o <img> nasce sem src e ganha o endereço local do arquivo.
export function TextoFormatado({ texto }: { texto: string }) {
  const html = useMemo(() => paraHtml(texto), [texto]);
  const caixa = useRef<HTMLDivElement>(null);

  useEffect(() => {
    caixa.current?.querySelectorAll<HTMLImageElement>("img[data-figura]").forEach((img) => {
      api.figura(Number(img.dataset.figura))
        .then((url) => { img.src = url; })
        .catch(() => img.replaceWith(Object.assign(document.createElement("span"), {
          className: "figura-pendente", textContent: "figura indisponível",
        })));
    });
  }, [html]);

  return <div ref={caixa} className="texto" dangerouslySetInnerHTML={{ __html: html }} />;
}
