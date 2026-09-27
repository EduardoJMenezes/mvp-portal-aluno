"use client";

// A agenda (decisão 0012): eventos por turma que levam ao conteúdo ligado.

import Link from "next/link";
import { Etiqueta } from "@/components/ui";
import type { DestinoDoEvento, EventoDaAgenda } from "@/lib/api";

const DIA = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo", weekday: "short", day: "2-digit", month: "2-digit" });
const HORA = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo", hour: "2-digit", minute: "2-digit" });
const MES = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo", month: "long", year: "numeric" });
const CHAVE_DO_MES = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit" });

/** Para onde o clique leva: a aula dentro do módulo, o módulo, a tela de lives ou o simulado. */
export function hrefDoDestino(d: DestinoDoEvento): string {
  switch (d.tipo) {
    case "AULA":
      return `/curso/aula/?modulo=${d.modulo_id}&item=${d.id}`;
    case "MODULO":
      return `/curso/aula/?modulo=${d.id}`;
    case "AULA_AO_VIVO":
      return "/aulas/";
    case "SIMULADO":
      return `/simulados/inicio/?id=${d.id}`;
  }
}

/** Os eventos por mês, o mais recente primeiro; dentro do mês, em ordem de data. */
export function porMes(eventos: EventoDaAgenda[]): [string, EventoDaAgenda[]][] {
  const meses = new Map<string, EventoDaAgenda[]>();
  for (const e of [...eventos].sort((a, b) => a.inicio_em.localeCompare(b.inicio_em))) {
    const chave = CHAVE_DO_MES.format(new Date(e.inicio_em));
    meses.set(chave, [...(meses.get(chave) ?? []), e]);
  }
  return [...meses.entries()]
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([, lista]) => {
      const mes = MES.format(new Date(lista[0].inicio_em)); // "setembro de 2026"
      return [mes.charAt(0).toUpperCase() + mes.slice(1), lista];
    });
}

/** Uma linha da agenda: quando, o quê, e o link para o conteúdo quando já saiu. */
export function LinhaDoEvento({ evento, acoes }: { evento: EventoDaAgenda; acoes?: React.ReactNode }) {
  const inicio = new Date(evento.inicio_em);
  const periodo = evento.fim_em && DIA.format(new Date(evento.fim_em)) !== DIA.format(inicio);
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3">
      <span className="w-28 shrink-0 text-[13px] tabular-nums text-suave">
        <span className="block font-semibold capitalize text-tinta-2">{DIA.format(inicio)}</span>
        {periodo ? `até ${DIA.format(new Date(evento.fim_em!))}` : HORA.format(inicio)}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-tinta">{evento.titulo}</span>
          {evento.categoria && <Etiqueta>{evento.categoria}</Etiqueta>}
        </span>
        {evento.descricao && <span className="block text-[13px] text-suave">{evento.descricao}</span>}
      </span>
      {evento.destino &&
        (evento.liberado ? (
          <Link href={hrefDoDestino(evento.destino)} className="text-sm font-semibold text-acento hover:underline">
            {evento.destino.tipo === "SIMULADO" ? "Ver simulado" : evento.destino.tipo === "AULA_AO_VIVO" ? "Ver aula ao vivo" : "Estudar"} →
          </Link>
        ) : (
          <span className="text-[13px] text-apagado">Libera às {HORA.format(inicio)}</span>
        ))}
      {acoes}
    </li>
  );
}
