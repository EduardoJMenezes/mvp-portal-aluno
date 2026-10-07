"use client";

import { CircleCheck, CircleX, X } from "lucide-react";
import { useCallback, useEffect, useState, type ReactNode } from "react";

type Recado = { tom: "sucesso" | "erro"; texto: ReactNode; chave: number };

/**
 * O recado do que acabou de acontecer, preso ao pé da tela: numa página comprida, o aviso no
 * topo fica fora de vista. O de sucesso some sozinho; o de erro espera ser lido e fechado.
 */
export function useRecado(): [ReactNode, { sucesso: (texto: ReactNode) => void; erro: (texto: ReactNode) => void; limpar: () => void }] {
  const [recado, setRecado] = useState<Recado | null>(null);

  useEffect(() => {
    if (recado?.tom !== "sucesso") return;
    const id = setTimeout(() => setRecado(null), 6000);
    return () => clearTimeout(id);
  }, [recado]);

  const sucesso = useCallback((texto: ReactNode) => setRecado({ tom: "sucesso", texto, chave: Date.now() }), []);
  const erro = useCallback((texto: ReactNode) => setRecado({ tom: "erro", texto, chave: Date.now() }), []);
  const limpar = useCallback(() => setRecado(null), []);

  const Icone = recado?.tom === "erro" ? CircleX : CircleCheck;
  const elemento = (
    // A região existe sempre: o leitor de tela só anuncia o que entra numa região que já estava lá.
    <div className="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex justify-center px-4 pb-5" aria-live={recado?.tom === "erro" ? "assertive" : "polite"} role={recado?.tom === "erro" ? "alert" : "status"}>
      {recado && (
        <div
          key={recado.chave}
          className={`recado pointer-events-auto flex max-w-xl items-start gap-3 rounded-2xl border px-4 py-3 text-[15px] shadow-suave ${
            recado.tom === "erro" ? "border-erro-borda bg-erro-fundo text-erro" : "border-tinta bg-tinta text-white"
          }`}
        >
          <Icone aria-hidden="true" className={`mt-0.5 size-5 shrink-0 ${recado.tom === "erro" ? "text-erro-vivo" : "text-sucesso-vivo"}`} strokeWidth={2.2} />
          <div className="min-w-0 flex-1">{recado.texto}</div>
          <button type="button" onClick={limpar} aria-label="Fechar aviso" className={`-mr-1 flex size-6 shrink-0 items-center justify-center rounded-md ${recado.tom === "erro" ? "hover:bg-erro-borda/40" : "hover:bg-white/15"}`}>
            <X aria-hidden="true" className="size-4" />
          </button>
        </div>
      )}
    </div>
  );

  return [elemento, { sucesso, erro, limpar }];
}
