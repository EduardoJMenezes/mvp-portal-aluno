"use client";

// As duas camadas que abrem por cima da página, iguais para a plataforma inteira:
//
//   <Modal tamanho="pequeno | medio | grande | tela">          no meio da tela
//   <OffCanvas lado="direita | esquerda | baixo | cima"         preso a uma borda
//              tamanho="pequeno | medio | grande | tela">
//
// As duas são o mesmo <dialog> nativo: o foco fica preso lá dentro, o Esc fecha, o resto da página
// fica inerte e, ao fechar, o foco volta para onde estava. O desenho e o movimento estão em
// globals.css (.camada): animação de entrada ao abrir e, ao fechar, a de saída antes do close().
//
// Quem usa escolhe um de dois jeitos:
//   - deixa montado e liga/desliga com `aberto`; o conteúdo é desmontado quando a saída termina;
//   - monta só quando precisa, com `aberto` num estado próprio, e desmonta em `aoSumir`, que é
//     chamado depois da animação de saída.

import { X } from "lucide-react";
import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from "react";

export type TamanhoDaCamada = "pequeno" | "medio" | "grande" | "tela";
export type LadoDoOffCanvas = "direita" | "esquerda" | "baixo" | "cima";

type Conteudo = {
  aberto: boolean;
  /** Pediram para fechar: Esc, clique fora ou o X. Quem usa decide se fecha (e pode perguntar antes). */
  aoFechar: () => void;
  /** Terminou de sair da tela. */
  aoSumir?: () => void;
  titulo: ReactNode;
  legenda?: ReactNode;
  /** Fica preso ao pé, fora da rolagem: é o lugar dos botões. */
  rodape?: ReactNode;
  /** O corpo sem margem interna, para lista ou tabela que vai de borda a borda. */
  semMargem?: boolean;
  /** `false` para o clique fora não fechar (formulário longo, por exemplo). */
  fechaClicandoFora?: boolean;
  children?: ReactNode;
};

/** O mesmo tempo da animação de saída em globals.css. */
const SAIDA_MS = 200;

function Camada({
  aberto,
  aoFechar,
  aoSumir,
  titulo,
  legenda,
  rodape,
  semMargem = false,
  fechaClicandoFora = true,
  children,
  alinhamento,
  caixa,
  de,
  some = false,
}: Conteudo & { alinhamento: string; caixa: string; de: string; some?: boolean }) {
  const dialogo = useRef<HTMLDialogElement>(null);
  const moldura = useRef<HTMLDivElement>(null);
  const idDoTitulo = useId();
  // O conteúdo existe enquanto a camada está na tela, saída incluída.
  const [presente, setPresente] = useState(aberto);
  const apertouFora = useRef(false);
  const sumir = useRef(aoSumir);
  useEffect(() => {
    sumir.current = aoSumir;
  });

  useEffect(() => {
    const d = dialogo.current;
    if (!d) return;
    if (aberto) {
      setPresente(true);
      delete d.dataset.saindo;
      if (!d.open) d.showModal();
      return;
    }
    if (!d.open) return;
    // Continua aberto enquanto a caixa sai; fechar antes tiraria o <dialog> da frente de tudo no
    // meio do movimento.
    d.dataset.saindo = "";
    const id = setTimeout(() => {
      delete d.dataset.saindo;
      d.close();
      setPresente(false);
      sumir.current?.();
    }, SAIDA_MS);
    return () => clearTimeout(id);
  }, [aberto]);

  // O foco começa em quem pediu (`data-foco-inicial`) ou na própria caixa: sem isso o <dialog>
  // escolhe o primeiro botão, que é o X.
  useEffect(() => {
    if (!aberto || !presente) return;
    (moldura.current?.querySelector<HTMLElement>("[data-foco-inicial]") ?? moldura.current)?.focus({ preventScroll: true });
  }, [aberto, presente]);

  return (
    <dialog
      ref={dialogo}
      aria-labelledby={idDoTitulo}
      onCancel={(e) => {
        e.preventDefault();
        aoFechar();
      }}
      // Só vale como clique fora o que começou e terminou fora: arrastar uma seleção de texto para
      // além da caixa não fecha nada.
      onMouseDown={(e) => {
        apertouFora.current = e.target === e.currentTarget;
      }}
      onClick={(e) => {
        if (fechaClicandoFora && apertouFora.current && e.target === e.currentTarget) aoFechar();
        apertouFora.current = false;
      }}
      className={`camada ${alinhamento}`}
      style={{ "--camada-de": de, "--camada-opacidade": some ? 0 : 1 } as CSSProperties}
    >
      {presente && (
        <div ref={moldura} tabIndex={-1} className={`camada-caixa flex min-h-0 min-w-0 flex-col bg-papel text-tinta outline-none ${caixa}`}>
          <header className="flex shrink-0 items-start gap-3 border-b border-borda px-5 py-4">
            <div className="min-w-0 flex-1">
              <h2 id={idDoTitulo} className="text-lg font-semibold leading-snug text-tinta">{titulo}</h2>
              {legenda && <p className="mt-0.5 text-sm text-suave">{legenda}</p>}
            </div>
            <button type="button" onClick={aoFechar} aria-label="Fechar" className="-mr-1.5 -mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-canvas hover:text-tinta">
              <X aria-hidden="true" className="size-5" />
            </button>
          </header>
          <div className={`min-h-0 flex-1 overflow-y-auto overscroll-contain ${semMargem ? "" : "px-5 py-4"}`}>{children}</div>
          {rodape && <footer className="shrink-0 border-t border-borda bg-canvas/60 px-5 py-3.5">{rodape}</footer>}
        </div>
      )}
    </dialog>
  );
}

// --- modal ---------------------------------------------------------------------

const LARGURA_DO_MODAL: Record<TamanhoDaCamada, string> = {
  pequeno: "max-w-md",
  medio: "max-w-xl",
  grande: "max-w-4xl",
  tela: "h-full max-w-none",
};

/** A caixa no meio da tela, para uma pergunta ou uma tarefa curta. `tela` ocupa tudo. */
export function Modal({ tamanho = "medio", ...conteudo }: Conteudo & { tamanho?: TamanhoDaCamada }) {
  const cheio = tamanho === "tela";
  return (
    <Camada
      {...conteudo}
      alinhamento={`items-center justify-center ${cheio ? "" : "p-4"}`}
      caixa={`max-h-full w-full shadow-suave ${LARGURA_DO_MODAL[tamanho]} ${cheio ? "" : "rounded-cartao"}`}
      de="translateY(10px) scale(0.98)"
      some
    />
  );
}

// --- off-canvas ----------------------------------------------------------------

const LADOS: Record<LadoDoOffCanvas, { alinhamento: string; de: string; deitado: boolean; canto: string }> = {
  direita: { alinhamento: "justify-end", de: "translateX(100%)", deitado: false, canto: "sm:rounded-l-[20px]" },
  esquerda: { alinhamento: "justify-start", de: "translateX(-100%)", deitado: false, canto: "sm:rounded-r-[20px]" },
  baixo: { alinhamento: "items-end", de: "translateY(100%)", deitado: true, canto: "rounded-t-[20px]" },
  cima: { alinhamento: "items-start", de: "translateY(-100%)", deitado: true, canto: "rounded-b-[20px]" },
};

/** Quanto da tela o painel toma: largura, quando nasce de um lado; altura, de baixo ou de cima. */
const DE_PE: Record<TamanhoDaCamada, string> = { pequeno: "max-w-sm", medio: "max-w-xl", grande: "max-w-3xl", tela: "max-w-none" };
const DEITADO: Record<TamanhoDaCamada, string> = { pequeno: "h-[40dvh]", medio: "h-[60dvh]", grande: "h-[88dvh]", tela: "h-full" };

/**
 * O painel que desliza de uma borda e deixa a página atrás à vista: bom para escolher ou editar
 * algo sem sair de onde se está. No celular, o de lado toma a largura inteira.
 */
export function OffCanvas({ lado = "direita", tamanho = "medio", ...conteudo }: Conteudo & { lado?: LadoDoOffCanvas; tamanho?: TamanhoDaCamada }) {
  const { alinhamento, de, deitado, canto } = LADOS[lado];
  const medida = deitado ? `w-full ${DEITADO[tamanho]}` : `h-full w-full ${DE_PE[tamanho]}`;
  return <Camada {...conteudo} alinhamento={alinhamento} caixa={`shadow-[0_0_48px_rgb(10_37_80/0.22)] ${medida} ${tamanho === "tela" ? "" : canto}`} de={de} />;
}
