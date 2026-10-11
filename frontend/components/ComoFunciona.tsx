"use client";

// O "i" de informação: um botão que abre o passo a passo de uma tela, para quem chegou nela e não
// sabe por onde começar. A tela continua enxuta, e a explicação está a um clique.

import { Info } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Botao } from "@/components/ui";

export function ComoFunciona({ titulo, legenda, rotulo = "Como funciona", children }: { titulo: string; legenda?: string; rotulo?: string; children: ReactNode }) {
  const [aberto, setAberto] = useState(false);
  return (
    <>
      <Botao onClick={() => setAberto(true)} aria-haspopup="dialog">
        <Info aria-hidden="true" className="size-4" />
        {rotulo}
      </Botao>
      {aberto && (
        <Explicacao titulo={titulo} legenda={legenda} aoFechar={() => setAberto(false)}>
          {children}
        </Explicacao>
      )}
    </>
  );
}

function Explicacao({ titulo, legenda, aoFechar, children }: { titulo: string; legenda?: string; aoFechar: () => void; children: ReactNode }) {
  const saida = useSaida(aoFechar);
  return (
    <Modal
      {...saida}
      aoFechar={saida.fechar}
      titulo={titulo}
      legenda={legenda}
      rodape={
        <div className="flex justify-end">
          <Botao variante="primario" onClick={saida.fechar}>Entendi</Botao>
        </div>
      }
    >
      <div className="flex flex-col gap-6 text-[15px]">{children}</div>
    </Modal>
  );
}

/** Um caminho dentro da explicação: o nome dele e os passos, na ordem. */
export function Caminho({ titulo, marca, children }: { titulo: string; marca?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="flex flex-wrap items-center gap-2 font-semibold text-tinta">
        {titulo}
        {marca}
      </h3>
      {children}
    </section>
  );
}

export function Passos({ children }: { children: ReactNode }) {
  return <ol className="list-decimal space-y-1.5 pl-5 marker:font-semibold marker:text-suave">{children}</ol>;
}
