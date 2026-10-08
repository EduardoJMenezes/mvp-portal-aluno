"use client";

// O momento em que o aluno termina um capítulo: um estouro de hexágonos e bolhas (é química) e um
// cartão ao pé da tela com o próximo passo. Acontece uma vez, na hora em que o último item fica
// feito — quem volta a um capítulo já concluído não vê de novo.

import { BadgeCheck, X } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState, type CSSProperties } from "react";
import { botao } from "./ui";

/** O capítulo que acabou de ser concluído: "Meu curso" lê para dar destaque ao cartão dele, uma vez. */
export const CHAVE_DO_RECEM_CONCLUIDO = "capitulo-recem-concluido";

const CORES = ["var(--color-acento)", "var(--color-ceu)", "var(--color-sucesso-vivo)", "var(--color-atencao-vivo)", "var(--color-violeta)"];

type Particula = { forma: "hexagono" | "bolha" | "atomo"; estilo: CSSProperties };

function sortear(quantas: number): Particula[] {
  return Array.from({ length: quantas }, (_, i) => {
    const forma = (["hexagono", "bolha", "atomo"] as const)[i % 3];
    const lado = 10 + Math.random() * 14;
    // Sobem em leque a partir do cartão, mais alto no meio do que nas pontas.
    const abertura = (Math.random() - 0.5) * 2;
    const cor = CORES[Math.floor(Math.random() * CORES.length)];
    return {
      forma,
      estilo: {
        width: lado,
        height: lado,
        color: cor,
        "--dx": `${Math.round(abertura * 46)}vw`,
        "--dy": `${-Math.round(28 + (1 - Math.abs(abertura)) * 42 + Math.random() * 12)}vh`,
        "--giro": `${Math.round((Math.random() - 0.5) * 720)}deg`,
        animationDuration: `${1500 + Math.round(Math.random() * 900)}ms`,
        animationDelay: `${Math.round(Math.random() * 180)}ms`,
      } as CSSProperties,
    };
  });
}

/** As partículas, por cima de tudo e sem pegar clique. Somem sozinhas. */
function Estouro() {
  const [particulas] = useState(() => sortear(54));
  return (
    <div aria-hidden="true" className="pointer-events-none fixed inset-0 z-40 overflow-hidden">
      {particulas.map((p, i) => (
        <span key={i} className={`particula particula-${p.forma}`} style={p.estilo} />
      ))}
    </div>
  );
}

/**
 * Aparece quando `feitos` chega a `total` durante a visita. Chegar à tela com o capítulo já
 * concluído não dispara nada: só comemora quem acabou de terminar.
 */
export function CapituloConcluido({
  modulo,
  feitos,
  total,
  proximo,
}: {
  modulo: { id: number; nome: string };
  feitos: number;
  total: number;
  /** O capítulo seguinte que ainda tem o que fazer; sem ele, o curso está em dia. */
  proximo?: { nome: string; href: string } | null;
}) {
  const antes = useRef<{ modulo: number; feitos: number } | null>(null);
  const [aberto, setAberto] = useState(false);

  useEffect(() => {
    const anterior = antes.current;
    antes.current = { modulo: modulo.id, feitos };
    const mesmoCapitulo = anterior?.modulo === modulo.id;
    if (mesmoCapitulo && total > 0 && anterior.feitos < total && feitos === total) {
      setAberto(true);
      try {
        sessionStorage.setItem(CHAVE_DO_RECEM_CONCLUIDO, String(modulo.id));
      } catch {
        // Sem sessionStorage (modo privado antigo), "Meu curso" só não dá o destaque.
      }
    } else if (!mesmoCapitulo || feitos < total) {
      setAberto(false);
    }
  }, [modulo.id, feitos, total]);

  if (!aberto) return null;
  return (
    <>
      <Estouro />
      <div className="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex justify-center px-4 pb-5" role="status">
        <div className="comemoracao pointer-events-auto flex w-full max-w-lg items-start gap-3.5 rounded-2xl border border-sucesso-borda bg-papel p-4 shadow-suave">
          <span className="selo-pulsa flex size-11 shrink-0 items-center justify-center rounded-full bg-sucesso-fundo text-sucesso-vivo">
            <BadgeCheck aria-hidden="true" className="size-7" strokeWidth={2} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-titulo text-lg font-bold leading-snug text-tinta">Capítulo concluído!</p>
            <p className="mt-0.5 text-[15px] text-tinta-2">
              Você terminou <span className="font-semibold">{modulo.nome}</span>.{!proximo && " Não ficou nada para trás: o curso está em dia."}
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              {proximo && (
                <Link href={proximo.href} className={`${botao("primario", "pequeno")} max-w-full`}>
                  <span className="truncate">Ir para {proximo.nome}</span>
                </Link>
              )}
              <Link href="/curso/" className={botao(proximo ? "neutro" : "primario", "pequeno")}>Ver meu curso</Link>
            </div>
          </div>
          <button type="button" onClick={() => setAberto(false)} aria-label="Fechar" className="-mr-1 -mt-1 flex size-8 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-canvas hover:text-tinta">
            <X aria-hidden="true" className="size-[18px]" />
          </button>
        </div>
      </div>
    </>
  );
}
