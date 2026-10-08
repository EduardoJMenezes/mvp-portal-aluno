"use client";

// A devolutiva por assunto: onde está indo bem e onde não. É a mesma leitura para o aluno, para o
// professor olhando um aluno e para o professor olhando a turma — muda só quem é o "você".

import { ChevronRight } from "lucide-react";
import { Cartao, Etiqueta, type Tom } from "@/components/ui";
import type { AssuntoNaDevolutiva, Devolutiva, LinhaDaDevolutiva, NivelNoAssunto } from "@/lib/api";
import { plural, porcento } from "@/lib/formato";

const NIVEL: Record<NivelNoAssunto, { rotulo: string; tom: Tom; barra: string }> = {
  ATENCAO: { rotulo: "Precisa de atenção", tom: "erro", barra: "bg-erro-vivo" },
  DESENVOLVENDO: { rotulo: "Em desenvolvimento", tom: "atencao", barra: "bg-atencao-vivo" },
  BEM: { rotulo: "Indo bem", tom: "sucesso", barra: "bg-sucesso-vivo" },
};

/** "2 da aula e 1 de simulado": de onde vieram as respostas. */
function origem(l: LinhaDaDevolutiva): string {
  const partes = [l.da_aula && `${l.da_aula} da aula`, l.de_simulado && `${l.de_simulado} de simulado`].filter((p): p is string => !!p);
  return partes.join(" e ");
}

function Barra({ linha }: { linha: LinhaDaDevolutiva }) {
  return (
    <span aria-hidden="true" className="block h-2 overflow-hidden rounded-full bg-gelo">
      <span className={`block h-full rounded-full ${NIVEL[linha.nivel].barra}`} style={{ width: `${Math.max(linha.percentual, 2)}%` }} />
    </span>
  );
}

/** O resumo: quantas questões entram na conta e o acerto geral. */
export function ResumoDaDevolutiva({ dados, quem }: { dados: Devolutiva; quem: string }) {
  return (
    <p className="text-[15px] text-suave">
      {dados.respostas === 0
        ? `${quem} ainda não respondeu nenhuma questão.`
        : `${plural(dados.respostas, "questão respondida", "questões respondidas")}, entre aulas e simulados, com ${porcento(dados.percentual ?? 0)} de acerto.`}
    </p>
  );
}

export function PorAssunto({ dados }: { dados: Devolutiva }) {
  if (dados.assuntos.length === 0) return null;
  return (
    <Cartao className="overflow-hidden">
      <ul className="divide-y divide-borda">
        {dados.assuntos.map((a) => (
          <Assunto key={a.id} assunto={a} />
        ))}
      </ul>
      <p className="border-t border-borda bg-canvas/50 px-5 py-3 text-[13px] text-suave">
        Do que mais pede atenção para o que vai melhor. A ordem considera quantas questões há em cada assunto: com poucas, ele fica mais perto da média geral, para um erro isolado não virar alarme.
      </p>
    </Cartao>
  );
}

function Assunto({ assunto }: { assunto: AssuntoNaDevolutiva }) {
  const temSubs = assunto.subassuntos.length > 0;
  const cabeca = (
    <span className="grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 sm:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_auto]">
      <span className="flex min-w-0 items-center gap-2">
        {temSubs && <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-suave transition-transform group-open:rotate-90" />}
        <span className="truncate font-semibold text-tinta">{assunto.nome}</span>
      </span>
      <span className="col-span-2 sm:col-span-1 sm:col-start-2 sm:row-start-1">
        <Barra linha={assunto} />
      </span>
      <span className="col-start-2 row-start-1 sm:col-start-3">
        <Etiqueta tom={NIVEL[assunto.nivel].tom}>{NIVEL[assunto.nivel].rotulo}</Etiqueta>
      </span>
      <span className="col-span-2 text-[13px] tabular-nums text-suave sm:col-span-3">
        {assunto.acertos} de {plural(assunto.respostas, "questão", "questões")} ({porcento(assunto.percentual)}): {origem(assunto)}
      </span>
    </span>
  );
  if (!temSubs) return <li className="px-5 py-3.5">{cabeca}</li>;
  return (
    <li>
      <details className="group">
        <summary className="cursor-pointer list-none px-5 py-3.5 hover:bg-canvas/60 [&::-webkit-details-marker]:hidden">{cabeca}</summary>
        <ul className="flex flex-col gap-2.5 bg-canvas/40 px-5 pb-4 pl-11 pt-1">
          {assunto.subassuntos.map((s) => (
            <li key={s.id} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_auto]">
              <span className="truncate text-[15px] font-medium text-tinta">{s.nome}</span>
              <span className="col-span-2 sm:col-span-1 sm:col-start-2 sm:row-start-1">
                <Barra linha={s} />
              </span>
              <span className="col-start-2 row-start-1 text-[13px] tabular-nums text-suave sm:col-start-3">
                {s.acertos} de {s.respostas} ({porcento(s.percentual)})
              </span>
            </li>
          ))}
        </ul>
      </details>
    </li>
  );
}
