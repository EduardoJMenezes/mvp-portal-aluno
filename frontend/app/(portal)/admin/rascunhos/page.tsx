"use client";

import Link from "next/link";
import { useState } from "react";
import { Abas, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";
import { ORIGEM, TIPO_DE_RASCUNHO } from "@/lib/rotulos";

type Filtro = "RASCUNHO" | "PUBLICADO";

export default function Rascunhos() {
  const [filtro, setFiltro] = useState<Filtro>("RASCUNHO");
  const lista = useDados(() => api.rascunhos(filtro), [filtro]);

  return (
    <Pagina titulo="Rascunhos" legenda="Nada aqui está visível para os alunos até alguém aprovar. Publicar é uma decisão sua — o backend recusa publicar sem essa aprovação.">
      <Abas
        abas={[
          { valor: "RASCUNHO", rotulo: "Esperando aprovação" },
          { valor: "PUBLICADO", rotulo: "Publicados" },
        ]}
        atual={filtro}
        aoTrocar={setFiltro}
      />
      <Estado {...lista} linhas={4}>
        {(rascunhos) =>
          rascunhos.length === 0 ? (
            <Vazio titulo={filtro === "RASCUNHO" ? "Nada esperando aprovação" : "Nenhum rascunho publicado ainda"}>
              {filtro === "RASCUNHO" && "Quando o Claude propuser vídeos, questões ou simulados, eles esperam aqui."}
            </Vazio>
          ) : (
            <Cartao>
              <ul className="divide-y divide-borda">
                {rascunhos.map((r) => (
                  <li key={r.rascunho_id}>
                    <Link href={`/admin/rascunhos/revisar/?id=${r.rascunho_id}`} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4 hover:bg-canvas">
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-2">
                          <Etiqueta tom="info">{TIPO_DE_RASCUNHO[r.tipo] ?? r.tipo}</Etiqueta>
                          <span className="font-mono text-[13px] text-suave">#{r.rascunho_id}</span>
                        </span>
                        <span className="mt-1 block font-medium text-tinta">{r.resumo || "Sem resumo"}</span>
                        <span className="mt-0.5 block text-[13px] text-suave">
                          {[r.turma, r.modulo, r.submodulo].filter(Boolean).join(" › ")}
                          {r.turma ? " · " : ""}
                          {r.criado_por} via {ORIGEM[r.origem] ?? r.origem} · {emBrasilia(r.criado_em)}
                          {r.publicado_em && ` · publicado ${emBrasilia(r.publicado_em)} por ${r.aprovado_por ?? "—"}`}
                        </span>
                      </span>
                      <span className="text-sm font-semibold text-acento">{filtro === "RASCUNHO" ? "Revisar" : "Ver"}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Cartao>
          )
        }
      </Estado>
    </Pagina>
  );
}
