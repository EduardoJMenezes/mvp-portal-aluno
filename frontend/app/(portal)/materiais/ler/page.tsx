"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { LeitorPdf } from "@/components/LeitorPdf";
import { Aviso } from "@/components/ui";
import { api, useDados } from "@/lib/api";

export default function PaginaDeLeitura() {
  return (
    <Suspense>
      <Leitura />
    </Suspense>
  );
}

function Leitura() {
  const id = Number(useSearchParams().get("id")) || 0;
  const material = useDados(() => (id ? api.materiais() : Promise.resolve([])), [id]);
  const atual = material.dados?.find((m) => m.material_id === id);

  return (
    <main data-sem-impressao>
      <p className="recado-de-impressao">
        Este material não sai do portal em papel nem em PDF. Abra em
        app-production-e5b7.up.railway.app para ler e marcar.
      </p>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-borda bg-papel px-4 py-2">
        <Link href="/materiais/" className="text-sm font-medium text-suave hover:text-acento">
          <span aria-hidden="true">←</span> Materiais
        </Link>
        <h1 className="truncate font-semibold text-tinta">{atual?.titulo ?? "Material"}</h1>
      </div>
      {id ? (
        <LeitorPdf materialId={id} />
      ) : (
        <div className="p-4">
          <Aviso tom="erro">Endereço sem material. Volte e escolha um da lista.</Aviso>
        </div>
      )}
    </main>
  );
}
