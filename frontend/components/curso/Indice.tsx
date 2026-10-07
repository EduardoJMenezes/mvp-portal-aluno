"use client";

// O índice do curso: todos os módulos, agrupados pela categoria, na ordem em que o aluno os vê.

import { Plus } from "lucide-react";
import Link from "next/link";
import { CapaDoModulo } from "@/components/CapaDoModulo";
import type { Modulo } from "@/lib/api";
import { plural } from "@/lib/formato";
import { Alca, Marca, useArrastar } from "./comum";

export function Indice({
  grupos,
  escolhido,
  soNaTelaLarga,
  novo,
  href,
  aoReordenar,
}: {
  grupos: [categoria: string, modulos: Modulo[]][];
  /** O módulo aberto ao lado. */
  escolhido: number | null;
  /** O aberto é só o primeiro da lista, que a tela estreita nem mostra: lá ele não se destaca. */
  soNaTelaLarga: boolean;
  /** O formulário de módulo novo está aberto ao lado. */
  novo: boolean;
  href: (modulo: number | "novo") => string;
  aoReordenar: (ids: number[]) => void;
}) {
  return (
    <nav aria-label="Módulos do curso" className="flex flex-col gap-4">
      {grupos.map(([categoria, modulos]) => (
        <Grupo key={categoria || "sem-categoria"} categoria={categoria} modulos={modulos} escolhido={escolhido} soNaTelaLarga={soNaTelaLarga} href={href} aoReordenar={aoReordenar} />
      ))}
      <Link
        href={href("novo")}
        scroll={false}
        aria-current={novo ? "page" : undefined}
        className={`flex items-center gap-2.5 rounded-xl border border-dashed px-3 py-2.5 text-[15px] font-semibold transition-colors ${
          novo ? "border-acento bg-lilas text-acento-forte" : "border-borda-campo/70 text-tinta-2 hover:border-acento hover:bg-papel hover:text-acento-forte"
        }`}
      >
        <Plus aria-hidden="true" className="size-4" strokeWidth={2.4} />
        Novo módulo
      </Link>
    </nav>
  );
}

function Grupo({
  categoria,
  modulos,
  escolhido,
  soNaTelaLarga,
  href,
  aoReordenar,
}: {
  categoria: string;
  modulos: Modulo[];
  escolhido: number | null;
  soNaTelaLarga: boolean;
  href: (modulo: number) => string;
  aoReordenar: (ids: number[]) => void;
}) {
  const arrastar = useArrastar(modulos.map((m) => m.id), Object.fromEntries(modulos.map((m) => [m.id, m.nome])), aoReordenar);

  return (
    <section aria-label={categoria || "Sem categoria"}>
      <h2 className="mb-1.5 px-2 font-sans text-[13px] font-semibold text-suave">{categoria || "Sem categoria"}</h2>
      <ol className="flex flex-col gap-0.5">
        {modulos.map((modulo) => {
          const itens = modulo.submodulos.flatMap((s) => s.itens);
          const rascunhos = itens.filter((i) => i.status !== "PUBLICADO").length;
          const aberto = modulo.id === escolhido;
          return (
            <li key={modulo.id} {...arrastar.linha(modulo.id)} className={`group relative transition-opacity ${arrastar.arrastando === modulo.id ? "opacity-40" : ""}`}>
              <Marca onde={arrastar.marca(modulo.id)} />
              <div className={`flex items-center rounded-xl transition-colors ${!aberto ? "hover:bg-papel/70" : soNaTelaLarga ? "lg:bg-papel lg:shadow-suave lg:ring-1 lg:ring-acento/25" : "bg-papel shadow-suave ring-1 ring-acento/25"}`}>
                <Alca arrastar={arrastar} id={modulo.id} nome={modulo.nome} className="ml-0.5 lg:opacity-0 lg:group-focus-within:opacity-100 lg:group-hover:opacity-100" />
                <Link href={href(modulo.id)} scroll={false} aria-current={aberto && !soNaTelaLarga ? "page" : undefined} className="flex min-w-0 flex-1 items-center gap-2.5 rounded-xl py-2 pl-0.5 pr-3 pointer-coarse:pl-2.5">
                  <CapaDoModulo modulo={modulo} tamanho="pequeno" />
                  <span className="min-w-0 flex-1">
                    <span className={`line-clamp-2 text-[15px] leading-snug ${!aberto ? "font-medium text-tinta" : soNaTelaLarga ? "font-medium text-tinta lg:font-semibold lg:text-acento-forte" : "font-semibold text-acento-forte"}`}>{modulo.nome}</span>
                    <span className="block text-[13px] text-suave">
                      {itens.length ? plural(itens.length, "item", "itens") : "Vazio"}
                      {rascunhos > 0 && <span className="font-semibold text-atencao">, {rascunhos} em rascunho</span>}
                    </span>
                  </span>
                </Link>
              </div>
            </li>
          );
        })}
      </ol>
      <p aria-live="polite" className="sr-only">{arrastar.anuncio}</p>
    </section>
  );
}
