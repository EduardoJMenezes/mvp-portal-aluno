"use client";

import { Suspense } from "react";
import { LinhaDoEvento, porMes } from "@/components/Agenda";
import { useCategoria } from "@/components/Categoria";
import { Cartao, Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, casaCategoria, useDados } from "@/lib/api";

export default function PaginaDaAgenda() {
  return (
    <Suspense>
      <Agenda />
    </Suspense>
  );
}

/** A agenda da turma do aluno até hoje (decisão 0012). O que vem depois não aparece. */
function Agenda() {
  const categoria = useCategoria();
  const lista = useDados(() => api.agenda(), [], 60);

  return (
    <Pagina titulo={categoria ?? "Agenda"} legenda="O que aconteceu na sua turma até hoje. Clique numa aula para estudar ela.">
      <Estado {...lista} linhas={4}>
        {(eventos) => {
          const daqui = eventos.filter((e) => casaCategoria(categoria, e.categoria));
          if (daqui.length === 0) {
            return <Vazio titulo="Nada na agenda ainda">Quando o professor marcar as aulas da sua turma, elas aparecem aqui no dia.</Vazio>;
          }
          return porMes(daqui).map(([mes, doMes]) => (
            <section key={mes} className="flex flex-col gap-2" aria-label={mes}>
              <TituloDeSecao>{mes}</TituloDeSecao>
              <Cartao>
                <ul className="divide-y divide-borda">
                  {doMes.map((e) => (
                    <LinhaDoEvento key={e.evento_id} evento={e} />
                  ))}
                </ul>
              </Cartao>
            </section>
          ));
        }}
      </Estado>
    </Pagina>
  );
}
