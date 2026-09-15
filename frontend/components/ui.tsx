"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from "react";

// --- botão -------------------------------------------------------------------

type Variante = "primario" | "secundario" | "neutro" | "perigo" | "texto";

const VARIANTES: Record<Variante, string> = {
  // A cor de destaque é só de ação: o botão principal é pílula cheia.
  primario: "rounded-full bg-acento text-white shadow-botao hover:bg-acento-forte disabled:bg-apagado disabled:shadow-none",
  secundario: "rounded-full border border-acento text-acento bg-papel hover:bg-lilas disabled:border-apagado disabled:text-apagado",
  neutro: "rounded-full border border-borda text-tinta bg-papel hover:border-suave disabled:text-apagado",
  perigo: "rounded-full border border-erro-borda text-erro bg-papel hover:bg-erro-fundo disabled:text-apagado",
  texto: "rounded-md text-acento hover:underline disabled:text-apagado px-1",
};

export function botao(variante: Variante = "neutro", tamanho: "normal" | "pequeno" = "normal") {
  const medida = variante === "texto" ? "text-sm" : tamanho === "pequeno" ? "px-3.5 py-1.5 text-sm" : "px-5 py-2.5 text-[15px]";
  return `inline-flex items-center justify-center gap-2 font-semibold whitespace-nowrap transition-colors disabled:cursor-not-allowed ${medida} ${VARIANTES[variante]}`;
}

export function Botao({
  variante = "neutro",
  tamanho = "normal",
  className = "",
  type = "button",
  ...resto
}: ButtonHTMLAttributes<HTMLButtonElement> & { variante?: Variante; tamanho?: "normal" | "pequeno" }) {
  return <button type={type} className={`${botao(variante, tamanho)} ${className}`} {...resto} />;
}

export function BotaoLink({ href, variante = "neutro", tamanho = "normal", children, className = "" }: { href: string; variante?: Variante; tamanho?: "normal" | "pequeno"; children: ReactNode; className?: string }) {
  return (
    <Link href={href} className={`${botao(variante, tamanho)} ${className}`}>
      {children}
    </Link>
  );
}

// --- etiqueta e aviso --------------------------------------------------------

export type Tom = "neutro" | "info" | "sucesso" | "atencao" | "erro";

const TONS: Record<Tom, string> = {
  neutro: "bg-canvas text-suave border-borda",
  info: "bg-lilas text-acento-forte border-lilas",
  sucesso: "bg-sucesso-fundo text-sucesso border-sucesso-borda",
  atencao: "bg-atencao-fundo text-atencao border-atencao-borda",
  erro: "bg-erro-fundo text-erro border-erro-borda",
};

export function Etiqueta({ tom = "neutro", children, className = "" }: { tom?: Tom; children: ReactNode; className?: string }) {
  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap ${TONS[tom]} ${className}`}>
      {children}
    </span>
  );
}

export function Aviso({ tom = "info", titulo, children, className = "" }: { tom?: Tom; titulo?: string; children?: ReactNode; className?: string }) {
  return (
    <div role={tom === "erro" ? "alert" : "status"} className={`rounded-cartao border px-4 py-3 text-[15px] ${TONS[tom]} ${className}`}>
      {titulo && <p className="font-semibold">{titulo}</p>}
      {children && <div className={titulo ? "mt-0.5" : ""}>{children}</div>}
    </div>
  );
}

// --- estrutura ---------------------------------------------------------------

export function Pagina({
  titulo,
  legenda,
  acoes,
  voltar,
  estreita = false,
  children,
}: {
  titulo: ReactNode;
  legenda?: ReactNode;
  acoes?: ReactNode;
  voltar?: { href: string; rotulo: string };
  estreita?: boolean;
  children: ReactNode;
}) {
  return (
    <main className={`mx-auto w-full px-4 pb-16 pt-6 sm:px-6 sm:pt-8 ${estreita ? "max-w-3xl" : "max-w-6xl"}`}>
      {voltar && (
        <Link href={voltar.href} className="mb-3 inline-flex items-center gap-1 text-sm font-medium text-suave hover:text-acento">
          <span aria-hidden="true">←</span> {voltar.rotulo}
        </Link>
      )}
      <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-[28px] font-semibold leading-tight tracking-[-0.01em] text-tinta sm:text-[32px]">{titulo}</h1>
          {legenda && <p className="mt-1.5 max-w-2xl text-[15px] text-suave">{legenda}</p>}
        </div>
        {acoes && <div className="flex flex-wrap items-center gap-2">{acoes}</div>}
      </header>
      <div className="flex flex-col gap-4">{children}</div>
    </main>
  );
}

export function Cartao({ children, className = "", como: Como = "section" }: { children: ReactNode; className?: string; como?: "section" | "div" | "article" | "li" }) {
  return <Como className={`rounded-cartao border border-borda bg-papel ${className}`}>{children}</Como>;
}

export function TituloDeSecao({ children, acao }: { children: ReactNode; acao?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-xl font-semibold text-tinta">{children}</h2>
      {acao}
    </div>
  );
}

export function Vazio({ titulo, children }: { titulo: string; children?: ReactNode }) {
  return (
    <div className="rounded-cartao border border-dashed border-borda bg-papel px-6 py-10 text-center">
      <p className="font-semibold text-tinta">{titulo}</p>
      {children && <div className="mx-auto mt-1 max-w-md text-[15px] text-suave">{children}</div>}
    </div>
  );
}

export function Carregando({ linhas = 3 }: { linhas?: number }) {
  return (
    <div aria-busy="true" aria-label="Carregando" className="flex flex-col gap-3">
      {Array.from({ length: linhas }, (_, i) => (
        <div key={i} className="h-16 animate-pulse rounded-cartao border border-borda bg-papel" />
      ))}
    </div>
  );
}

/** Conteúdo com os três estados de uma busca: carregando, erro e pronto. */
export function Estado<T>({ carregando, erro, dados, children, linhas }: { carregando: boolean; erro: string; dados: T | null; children: (dados: T) => ReactNode; linhas?: number }) {
  if (erro) return <Aviso tom="erro" titulo="Não deu para carregar">{erro}</Aviso>;
  if (carregando && dados === null) return <Carregando linhas={linhas} />;
  if (dados === null) return null;
  return <>{children(dados)}</>;
}

// --- formulário --------------------------------------------------------------

export function Campo({ rotulo, dica, children, className = "" }: { rotulo: string; dica?: ReactNode; children: (id: string) => ReactNode; className?: string }) {
  const id = useId();
  return (
    <div className={`flex flex-col gap-1.5 ${className}`}>
      <label htmlFor={id} className="text-sm font-semibold text-tinta-2">
        {rotulo}
      </label>
      {children(id)}
      {dica && <p className="text-[13px] text-suave">{dica}</p>}
    </div>
  );
}

export function Abas<T extends string>({ abas, atual, aoTrocar }: { abas: { valor: T; rotulo: string }[]; atual: T; aoTrocar: (valor: T) => void }) {
  return (
    <div role="tablist" className="flex gap-1 overflow-x-auto border-b border-borda">
      {abas.map((aba) => (
        <button
          key={aba.valor}
          type="button"
          role="tab"
          aria-selected={aba.valor === atual}
          onClick={() => aoTrocar(aba.valor)}
          className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-[15px] font-semibold transition-colors ${
            aba.valor === atual ? "border-acento text-acento" : "border-transparent text-suave hover:text-tinta"
          }`}
        >
          {aba.rotulo}
        </button>
      ))}
    </div>
  );
}

// --- confirmação -------------------------------------------------------------

type PedidoDeConfirmacao = { titulo: string; texto?: ReactNode; confirmar: string; perigo?: boolean };

/** `confirmar()` abre um <dialog> nativo (foco preso e Esc de graça) e devolve a escolha. */
export function useConfirmar(): [ReactNode, (pedido: PedidoDeConfirmacao) => Promise<boolean>] {
  const [pedido, setPedido] = useState<PedidoDeConfirmacao | null>(null);
  const resolver = useRef<(sim: boolean) => void>(() => {});
  const dialogo = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    if (pedido && dialogo.current && !dialogo.current.open) dialogo.current.showModal();
  }, [pedido]);

  const fechar = useCallback((sim: boolean) => {
    dialogo.current?.close();
    setPedido(null);
    resolver.current(sim);
  }, []);

  const confirmar = useCallback((novo: PedidoDeConfirmacao) => {
    setPedido(novo);
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const elemento = pedido ? (
    <dialog
      ref={dialogo}
      onCancel={(e) => {
        e.preventDefault();
        fechar(false);
      }}
      className="m-auto w-[min(92vw,480px)] rounded-cartao border border-borda bg-papel p-0 text-tinta shadow-suave backdrop:bg-tinta/40"
    >
      <div className="px-6 pb-2 pt-5">
        <h2 className="text-lg font-semibold">{pedido.titulo}</h2>
        {pedido.texto && <div className="mt-2 text-[15px] text-tinta-2">{pedido.texto}</div>}
      </div>
      <div className="flex flex-wrap justify-end gap-2 px-6 pb-5 pt-4">
        <Botao onClick={() => fechar(false)} autoFocus>
          Cancelar
        </Botao>
        <Botao variante={pedido.perigo ? "perigo" : "primario"} onClick={() => fechar(true)}>
          {pedido.confirmar}
        </Botao>
      </div>
    </dialog>
  ) : null;

  return [elemento, confirmar];
}

// --- segredo mostrado uma vez --------------------------------------------------

export function SegredoUmaVez({ titulo, valor, children }: { titulo: string; valor: string; children?: ReactNode }) {
  const [copiado, setCopiado] = useState(false);
  return (
    <Aviso tom="atencao" titulo={titulo}>
      {children}
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <code className="select-all break-all rounded-campo border border-atencao-borda bg-papel px-2 py-1 font-mono text-[15px] text-tinta">{valor}</code>
        <Botao
          tamanho="pequeno"
          onClick={async () => {
            await navigator.clipboard?.writeText(valor);
            setCopiado(true);
          }}
        >
          {copiado ? "Copiado" : "Copiar"}
        </Botao>
      </div>
    </Aviso>
  );
}
