"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PainelDoResultado, abaDoResultado, type AbaDoResultado } from "@/components/ResultadoDoSimulado";
import { Estado, Pagina } from "@/components/ui";
import { api, useDados } from "@/lib/api";

export default function PaginaDoResultado() {
  return (
    <Suspense>
      <ResultadoDoAluno />
    </Suspense>
  );
}

function ResultadoDoAluno() {
  const router = useRouter();
  const parametros = useSearchParams();
  const id = Number(parametros.get("id"));
  const aba = abaDoResultado(parametros.get("aba"));
  const resultado = useDados(() => api.resultado(id), [id]);
  // A aba mora no endereço: voltar no navegador funciona, e dá para mandar o link de uma aba.
  const trocar = (nova: AbaDoResultado) => router.replace(`/simulados/resultado/?id=${id}${nova === "geral" ? "" : `&aba=${nova}`}`, { scroll: false });
  return (
    <Pagina titulo={resultado.dados?.titulo ?? "Resultado"} voltar={{ href: "/simulados/", rotulo: "Simulados" }}>
      <Estado {...resultado} linhas={4}>
        {(r) => <PainelDoResultado r={r} aba={aba} aoTrocar={trocar} />}
      </Estado>
    </Pagina>
  );
}
