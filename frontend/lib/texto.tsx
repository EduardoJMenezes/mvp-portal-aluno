"use client";

import katex from "katex";
import "katex/contrib/mhchem";
import { Marked } from "marked";
import { useEffect, useMemo, useRef } from "react";

// Enunciado, alternativas e resolução chegam como Markdown, com tabela, fórmula
// em LaTeX entre $...$ (química em \ce{}) e figuras referenciadas no ponto onde
// aparecem: ![](figura:123). Ver docs/IMPORTADOR-SIMULADO.md.

const escapar = (texto: string) => texto.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

// Questão não carrega HTML nem link: seriam só um jeito de injetar coisa na
// tela do aluno. Imagem, só a figura da própria questão.
const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    html({ text }) {
      return escapar(text);
    },
    link({ tokens }) {
      return this.parser.parseInline(tokens);
    },
    image({ href, text }) {
      if (href === "figura:pendente") return '<span class="figura-pendente">figura pendente</span>';
      const figura = /^figura:(\d+)$/.exec(href);
      return figura
        ? `<img class="figura" src="/api/aluno/figuras/${figura[1]}" alt="Figura da questão" loading="lazy">`
        : escapar(text);
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
    formulas.push(katex.renderToString(bloco ?? linha ?? "", { displayMode: bloco !== undefined, throwOnError: false }));
    return `%%F${formulas.length - 1}%%`;
  });
  return (
    (markdown.parse(semFormulas) as string)
      .replace(/%%F(\d+)%%/g, (_, i: string) => formulas[Number(i)])
      // Figura sozinha no parágrafo ocupa a linha; no meio do texto (a seta de
      // uma equação), fica na altura dele.
      .replace(/<p>\s*<img class="figura"/g, '<p><img class="figura sozinha"')
      .replace(/(<img class="figura sozinha"[^>]*>)\s*(?=\S)(?!<\/p>)/g, (img) => img.replace(" sozinha", ""))
  );
}

// A figura vem da API com o cookie da sessão: antes de a prova abrir, o backend
// recusa (ela adiantaria a questão). Se falhar, vira o aviso no lugar dela.
export function TextoFormatado({ texto, compacto = false, className = "" }: { texto: string; compacto?: boolean; className?: string }) {
  const html = useMemo(() => paraHtml(texto ?? ""), [texto]);
  const caixa = useRef<HTMLDivElement>(null);

  useEffect(() => {
    caixa.current?.querySelectorAll<HTMLImageElement>("img.figura").forEach((img) => {
      const trocar = () =>
        img.replaceWith(Object.assign(document.createElement("span"), { className: "figura-pendente", textContent: "figura indisponível" }));
      if (img.complete && img.naturalWidth === 0) trocar();
      else img.addEventListener("error", trocar, { once: true });
    });
  }, [html]);

  return <div ref={caixa} className={`texto ${compacto ? "compacto" : ""} ${className}`} dangerouslySetInnerHTML={{ __html: html }} />;
}
