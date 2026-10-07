"use client";

import { Suspense } from "react";
import { CartaoDoModulo } from "@/components/CartaoDoModulo";
import { useCategoria } from "@/components/Categoria";
import { Estado, Pagina, Vazio } from "@/components/ui";
import { api, casaCategoria, useDados } from "@/lib/api";
import { plural } from "@/lib/formato";

export default function PaginaDoCurso() {
  return (
    <Suspense>
      <MeuCurso />
    </Suspense>
  );
}

// Cada turma leva uma cor na barrinha do título: quem está em duas distingue de relance.
const COR_DA_TURMA = ["bg-acento", "bg-violeta", "bg-ceu"];

function MeuCurso() {
  const categoria = useCategoria();
  const conteudo = useDados(() => api.conteudo(), [], 60);

  return (
    <Pagina titulo={categoria ?? "Meu curso"} legenda="Aulas e resoluções das turmas em que você está matriculado.">
      <Estado {...conteudo} linhas={5}>
        {(todas) => {
          // O botão do menu mostra só os capítulos daquela categoria; turma sem nenhum some.
          const turmas = todas
            .map((t) => ({ ...t, modulos: t.modulos.filter((m) => casaCategoria(categoria, m.categoria)) }))
            .filter((t) => t.modulos.length > 0);
          return turmas.length === 0 ? (
            <Vazio titulo="Nada publicado para você ainda">Quando o professor publicar as aulas da sua turma, elas aparecem aqui.</Vazio>
          ) : (
            turmas.map((turma, i) => {
              const aulas = turma.modulos.reduce((n, m) => n + m.submodulos.reduce((k, s) => k + s.itens.length, 0), 0);
              return (
                <section key={turma.turma_id} className="mt-2 flex flex-col gap-4" aria-label={turma.turma}>
                  <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                    <h2 className="flex items-center gap-3 text-xl font-bold uppercase tracking-[0.02em] text-tinta">
                      <span aria-hidden="true" className={`h-6 w-1.5 rounded-full ${COR_DA_TURMA[i % COR_DA_TURMA.length]}`} />
                      {turma.turma}
                    </h2>
                    <p className="rounded-full bg-lilas/80 px-3.5 py-1.5 text-sm font-medium text-tinta-2">
                      {plural(turma.modulos.length, "módulo")} <span aria-hidden="true">•</span> {plural(aulas, "aula")}
                    </p>
                  </div>
                  <ol className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                    {turma.modulos.map((modulo) => (
                      <li key={modulo.id}>
                        <CartaoDoModulo modulo={modulo} />
                      </li>
                    ))}
                  </ol>
                </section>
              );
            })
          );
        }}
      </Estado>
    </Pagina>
  );
}
