"use client";

import { Ellipsis, type LucideIcon } from "lucide-react";
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

/** `dica` é a segunda linha, para quando o nome sozinho não diz o que acontece. */
export type ItemDoMenu =
  | { rotulo: string; dica?: string; icone?: LucideIcon; perigo?: boolean; desabilitado?: boolean; aoEscolher: () => void }
  | "divisor";

/**
 * Botão que abre uma lista de ações. A lista é `fixed`, na posição do botão: assim não é cortada
 * pelo cartão de cantos arredondados em que o botão mora. Rolou a página ou clicou fora, fecha.
 */
export function Menu({
  rotulo,
  itens,
  children,
  className = "",
  largura = "w-56",
}: {
  /** O nome do botão para quem não vê o ícone. */
  rotulo: string;
  itens: (ItemDoMenu | false | null | undefined)[];
  /** O conteúdo do botão; sem ele, os três pontinhos. */
  children?: ReactNode;
  className?: string;
  largura?: string;
}) {
  const id = useId();
  const botao = useRef<HTMLButtonElement>(null);
  const lista = useRef<HTMLDivElement>(null);
  const [aberto, setAberto] = useState(false);
  const [lugar, setLugar] = useState<{ top?: number; bottom?: number; left?: number; right?: number } | null>(null);
  const validos = itens.filter((i): i is ItemDoMenu => !!i);

  const fechar = useCallback((devolverFoco = false) => {
    setAberto(false);
    setLugar(null);
    if (devolverFoco) botao.current?.focus();
  }, []);

  // Abre para baixo e para a esquerda do botão; sem espaço, vira para cima.
  useLayoutEffect(() => {
    if (!aberto || !botao.current || !lista.current) return;
    const b = botao.current.getBoundingClientRect();
    const altura = lista.current.offsetHeight;
    const cabeEmbaixo = b.bottom + 6 + altura <= window.innerHeight - 8;
    const vertical = cabeEmbaixo || b.top - 6 - altura < 8 ? { top: b.bottom + 6 } : { bottom: window.innerHeight - b.top + 6 };
    const cabeAEsquerda = b.right - lista.current.offsetWidth >= 8;
    setLugar({ ...vertical, ...(cabeAEsquerda ? { right: window.innerWidth - b.right } : { left: Math.max(8, b.left) }) });
  }, [aberto]);

  // O foco entra na lista só depois de ela ter lugar: escondida, ela não aceita foco.
  const posicionado = lugar !== null;
  useEffect(() => {
    if (posicionado) lista.current?.querySelector<HTMLElement>('[role="menuitem"]:not([disabled])')?.focus();
  }, [posicionado]);

  useEffect(() => {
    if (!aberto) return;
    const fora = (e: PointerEvent) => {
      const alvo = e.target as Node;
      if (!lista.current?.contains(alvo) && !botao.current?.contains(alvo)) fechar();
    };
    const mexeu = () => fechar();
    document.addEventListener("pointerdown", fora);
    window.addEventListener("scroll", mexeu, true);
    window.addEventListener("resize", mexeu);
    return () => {
      document.removeEventListener("pointerdown", fora);
      window.removeEventListener("scroll", mexeu, true);
      window.removeEventListener("resize", mexeu);
    };
  }, [aberto, fechar]);

  function teclas(e: KeyboardEvent<HTMLDivElement>) {
    const opcoes = [...(lista.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') ?? [])];
    const atual = opcoes.indexOf(document.activeElement as HTMLElement);
    const ir = (i: number) => {
      e.preventDefault();
      opcoes[(i + opcoes.length) % opcoes.length]?.focus();
    };
    if (e.key === "ArrowDown") ir(atual + 1);
    else if (e.key === "ArrowUp") ir(atual - 1);
    else if (e.key === "Home") ir(0);
    else if (e.key === "End") ir(opcoes.length - 1);
    else if (e.key === "Escape") {
      e.preventDefault();
      fechar(true);
    } else if (e.key === "Tab") fechar();
  }

  return (
    <>
      <button
        ref={botao}
        type="button"
        aria-haspopup="menu"
        aria-expanded={aberto}
        aria-controls={aberto ? id : undefined}
        aria-label={rotulo}
        title={children ? undefined : rotulo}
        onClick={() => (aberto ? fechar() : setAberto(true))}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" && !aberto) {
            e.preventDefault();
            setAberto(true);
          }
        }}
        className={
          className ||
          "flex size-9 shrink-0 items-center justify-center rounded-lg text-suave transition-colors hover:bg-canvas hover:text-tinta aria-expanded:bg-lilas aria-expanded:text-acento-forte"
        }
      >
        {children ?? <Ellipsis aria-hidden="true" className="size-5" />}
      </button>
      {aberto && (
        <div
          ref={lista}
          id={id}
          role="menu"
          aria-label={rotulo}
          onKeyDown={teclas}
          style={lugar ?? { top: 0, left: 0, visibility: "hidden" }}
          className={`fixed z-40 ${largura} max-w-[calc(100vw-16px)] rounded-xl border border-borda bg-papel p-1.5 shadow-suave`}
        >
          {validos.map((item, i) =>
            item === "divisor" ? (
              <div key={i} role="separator" className="my-1.5 border-t border-borda" />
            ) : (
              <button
                key={i}
                type="button"
                role="menuitem"
                disabled={item.desabilitado}
                onClick={() => {
                  fechar(true);
                  item.aoEscolher();
                }}
                className={`flex w-full items-start gap-2.5 rounded-lg px-2.5 py-2 text-left text-[15px] outline-none transition-colors disabled:cursor-not-allowed disabled:text-apagado ${
                  item.perigo ? "text-erro hover:bg-erro-fundo focus-visible:bg-erro-fundo" : "text-tinta hover:bg-canvas focus-visible:bg-lilas"
                }`}
              >
                {item.icone && <item.icone aria-hidden="true" className={`mt-0.5 size-[18px] shrink-0 ${item.perigo || item.desabilitado ? "" : "text-suave"}`} strokeWidth={1.9} />}
                <span className="min-w-0">
                  <span className="block font-medium">{item.rotulo}</span>
                  {item.dica && <span className="block text-[13px] leading-snug text-suave">{item.dica}</span>}
                </span>
              </button>
            ),
          )}
        </div>
      )}
    </>
  );
}
