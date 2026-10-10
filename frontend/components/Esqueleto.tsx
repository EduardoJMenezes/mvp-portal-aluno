// O carregamento da plataforma: blocos cinza-azulados, com um brilho que passa, no formato do que
// vai chegar. Quem espera vê onde o conteúdo vai aparecer, em vez de uma tela vazia ou um "…".
//
//   <Carregando forma="lista" linhas={5} />     o formato pronto mais parecido com a tela
//   <Esqueleto className="h-4 w-40" />          um bloco, para desenhar um formato sob medida
//
// O `Estado` (ui.tsx) já mostra um `Carregando` enquanto os dados não chegam: basta dizer a forma.
// O desenho e o brilho estão em globals.css (.esqueleto); com "reduzir movimento", o bloco fica parado.

import type { ReactNode } from "react";

/** Um bloco do esqueleto. O tamanho e o canto vêm das classes: `h-4 w-40`, `size-9 rounded-full`. */
export function Esqueleto({ className = "" }: { className?: string }) {
  return <span aria-hidden="true" className={`esqueleto ${className}`} />;
}

/**
 * - `blocos`: cartões empilhados, quando não há formato melhor;
 * - `lista`: linhas com ícone, título e uma segunda linha;
 * - `cartoes`: a grade de cartões, como em "Meu curso";
 * - `tabela`: cabeçalho e linhas em colunas;
 * - `texto`: um parágrafo.
 */
export type FormaDoCarregamento = "blocos" | "lista" | "cartoes" | "tabela" | "texto";

/** As larguras se revezam para as linhas não ficarem todas iguais, que é o que denuncia o molde. */
const LARGURAS = ["w-2/5", "w-3/5", "w-1/3", "w-1/2", "w-2/3"];
const largura = (i: number) => LARGURAS[i % LARGURAS.length];

/** A moldura que avisa o leitor de tela: os blocos são só desenho. */
export function Carregamento({ children, className = "", rotulo = "Carregando" }: { children: ReactNode; className?: string; rotulo?: string }) {
  return (
    <div role="status" aria-busy="true" className={className}>
      <span className="sr-only">{rotulo}</span>
      {children}
    </div>
  );
}

export function Carregando({ linhas = 3, forma = "blocos" }: { linhas?: number; forma?: FormaDoCarregamento }) {
  const vezes = Array.from({ length: linhas }, (_, i) => i);

  if (forma === "lista") {
    return (
      <Carregamento className="divide-y divide-borda/70 overflow-hidden rounded-cartao border border-borda/70 bg-papel">
        {vezes.map((i) => (
          <div key={i} className="flex items-center gap-3 px-5 py-4">
            <Esqueleto className="size-9 shrink-0 rounded-xl" />
            <div className="flex min-w-0 flex-1 flex-col gap-2">
              <Esqueleto className={`h-4 ${largura(i)}`} />
              <Esqueleto className={`h-3 ${largura(i + 2)}`} />
            </div>
            <Esqueleto className="h-4 w-14 shrink-0" />
          </div>
        ))}
      </Carregamento>
    );
  }

  if (forma === "cartoes") {
    return (
      <Carregamento className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {vezes.map((i) => (
          <div key={i} className="flex gap-4 rounded-cartao border border-borda/70 bg-papel p-5">
            <Esqueleto className="size-16 shrink-0 rounded-2xl" />
            <div className="flex min-w-0 flex-1 flex-col gap-2.5">
              <Esqueleto className="h-4 w-12" />
              <Esqueleto className={`h-5 ${largura(i + 1)}`} />
              <Esqueleto className="h-3 w-4/5" />
              <Esqueleto className="mt-2 h-2 w-full rounded-full" />
            </div>
          </div>
        ))}
      </Carregamento>
    );
  }

  if (forma === "tabela") {
    return (
      <Carregamento className="overflow-hidden rounded-cartao border border-borda/70 bg-papel">
        <div className="flex gap-6 border-b border-borda bg-canvas/70 px-5 py-3">
          <Esqueleto className="h-3 w-1/4" />
          <Esqueleto className="h-3 w-1/5" />
          <Esqueleto className="ml-auto h-3 w-16" />
        </div>
        <div className="divide-y divide-borda/70">
          {vezes.map((i) => (
            <div key={i} className="flex items-center gap-6 px-5 py-3.5">
              <Esqueleto className={`h-4 ${largura(i)}`} />
              <Esqueleto className="h-4 w-1/5" />
              <Esqueleto className="ml-auto h-4 w-16" />
            </div>
          ))}
        </div>
      </Carregamento>
    );
  }

  if (forma === "texto") {
    return (
      <Carregamento className="flex flex-col gap-2.5">
        {vezes.map((i) => (
          <Esqueleto key={i} className={`h-4 ${i === linhas - 1 ? "w-3/5" : "w-full"}`} />
        ))}
      </Carregamento>
    );
  }

  return (
    <Carregamento className="flex flex-col gap-3">
      {vezes.map((i) => (
        <div key={i} className="flex flex-col gap-2.5 rounded-cartao border border-borda/70 bg-papel p-5">
          <Esqueleto className={`h-4 ${largura(i)}`} />
          <Esqueleto className="h-3 w-4/5" />
        </div>
      ))}
    </Carregamento>
  );
}
