import katex from "katex";
import "katex/dist/katex.min.css";
import { Marked } from "marked";
import { useEffect, useMemo, useState } from "react";
import { api } from "./api";

// Enunciado e alternativas chegam como o Claude transcreve: Markdown, com
// tabela, e fórmula em LaTeX entre $...$ (docs/MODELO-SIMULADO.md).

const escapar = (texto: string) =>
  texto.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

// Questão não carrega HTML, link nem imagem: a figura vem anexada à parte, e o
// resto seria só um jeito de injetar coisa na tela do aluno.
const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    html({ text }) { return escapar(text); },
    link({ tokens }) { return this.parser.parseInline(tokens); },
    image({ text }) { return escapar(text); },
  },
});

// $$bloco$$ ou $linha$. O `$` colado no texto é o que separa fórmula de preço:
// "R$ 50 e R$ 60" não vira fórmula.
const FORMULA = /\$\$([\s\S]+?)\$\$|\$(?!\s)([^$\n]+?)(?<!\s)\$/g;

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

export function TextoFormatado({ texto }: { texto: string }) {
  const html = useMemo(() => paraHtml(texto), [texto]);
  return <div className="texto" dangerouslySetInnerHTML={{ __html: html }} />;
}

// A figura só sai com o token: antes de a prova abrir, ela adiantaria a
// questão. Por isso não é um <img src> direto para a API.
export function FiguraDaQuestao({ questaoId }: { questaoId: number }) {
  const [url, setUrl] = useState<string | null>(null);
  const [erro, setErro] = useState("");

  useEffect(() => {
    let criada: string | null = null;
    let desmontou = false;
    api.imagem(questaoId)
      .then((u) => {
        criada = u;
        if (desmontou) URL.revokeObjectURL(u);
        else setUrl(u);
      })
      .catch((e) => setErro(e.message));
    return () => {
      desmontou = true;
      if (criada) URL.revokeObjectURL(criada);
    };
  }, [questaoId]);

  if (erro) return <div className="legenda">{erro}</div>;
  return url ? <img className="figura" src={url} alt="Figura da questão" /> : null;
}
