"use client";

// Os assuntos de um lado, do que mais pede atenção para o que vai melhor; do outro, o assunto
// escolhido, com cada sub-assunto e, onde houve erro, o que há no curso sobre ele. É a mesma tela
// para o aluno ("para estudar") e para o professor olhando um aluno ("para indicar").

import { ChevronRight } from "lucide-react";
import { Barra, NIVEL, origem } from "@/components/Devolutiva";
import { MaterialParaRevisar } from "@/components/Resultado";
import { Cartao, Etiqueta } from "@/components/ui";
import type { AssuntoNaDevolutiva, Devolutiva, Recomendacao } from "@/lib/api";
import { plural, porcento } from "@/lib/formato";

type Quem = "aluno" | "professor";

export function AssuntosEmDetalhe({ d, escolhido, aoEscolher, quem = "aluno" }: { d: Devolutiva; escolhido: number | null; aoEscolher: (id: number) => void; quem?: Quem }) {
  const atual = d.assuntos.find((a) => a.id === escolhido) ?? d.assuntos[0];
  return (
    <div className="grid items-start gap-4 lg:grid-cols-[19rem_minmax(0,1fr)]">
      <Cartao como="div" className="overflow-hidden">
        <ul className="divide-y divide-borda" aria-label="Assuntos">
          {d.assuntos.map((a) => {
            const ativo = a.id === atual.id;
            return (
              <li key={a.id}>
                <button
                  type="button"
                  aria-pressed={ativo}
                  onClick={() => {
                    aoEscolher(a.id);
                    // No celular a lista fica em cima: leva o olho para o assunto que abriu.
                    if (window.matchMedia("(max-width: 1023px)").matches) requestAnimationFrame(() => document.getElementById("assunto-escolhido")?.scrollIntoView({ behavior: "smooth", block: "start" }));
                  }}
                  className={`flex w-full flex-col gap-1.5 border-l-4 px-4 py-3 text-left transition-colors ${ativo ? "border-acento bg-lilas/50" : "border-transparent hover:bg-canvas/60"}`}
                >
                  <span className="flex items-center justify-between gap-2">
                    <span className="truncate font-semibold text-tinta">{a.nome}</span>
                    <ChevronRight aria-hidden="true" className={`size-4 shrink-0 ${ativo ? "text-acento" : "text-suave"}`} />
                  </span>
                  <Barra linha={a} />
                  <span className="flex items-center justify-between gap-2 text-[13px] text-suave">
                    <span className="tabular-nums">{a.acertos} de {a.respostas} ({porcento(a.percentual)})</span>
                    <span className={ativo ? "font-semibold text-tinta-2" : ""}>{NIVEL[a.nivel].rotulo}</span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        {d.sem_assunto > 0 && (
          <p className="border-t border-borda bg-canvas/50 px-4 py-2.5 text-[13px] text-suave">
            {d.sem_assunto === 1 ? "1 resposta ficou" : `${d.sem_assunto} respostas ficaram`} de fora: {d.questoes_sem_assunto === 1 ? "a questão ainda não tem" : "as questões ainda não têm"} assunto.
          </p>
        )}
      </Cartao>
      <Assunto key={atual.id} a={atual} recomendacoes={d.onde_revisar.filter((r) => r.assunto_id === atual.id)} quem={quem} />
    </div>
  );
}

/** O assunto escolhido: cada sub-assunto com o acerto dele e, onde houve erro, o que estudar. */
function Assunto({ a, recomendacoes, quem }: { a: AssuntoNaDevolutiva; recomendacoes: Recomendacao[]; quem: Quem }) {
  const doAssunto = recomendacoes.find((r) => r.subassunto_id == null);
  // Abre sozinho o primeiro ponto em que há o que estudar: é o mais fraco, porque a lista vem do pior para o melhor.
  const primeiroComErro = a.subassuntos.find((s) => s.acertos < s.respostas)?.id;
  return (
    <Cartao como="div" className="overflow-hidden">
      <div id="assunto-escolhido" className="flex scroll-mt-20 flex-col gap-2 border-b border-borda px-5 py-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold text-tinta">{a.nome}</h2>
          <Etiqueta tom={NIVEL[a.nivel].tom}>{NIVEL[a.nivel].rotulo}</Etiqueta>
        </div>
        <Barra linha={a} />
        <p className="text-[13px] tabular-nums text-suave">
          {a.acertos} de {plural(a.respostas, "questão", "questões")} ({porcento(a.percentual)}): {origem(a)}
        </p>
      </div>

      {a.subassuntos.length === 0 ? (
        <div className="flex flex-col gap-3 px-5 py-4">
          {doAssunto ? <Estudar r={doAssunto} quem={quem} /> : <p className="text-[15px] text-suave">Sem erros neste assunto até agora.</p>}
        </div>
      ) : (
        <ul className="divide-y divide-borda">
          {a.subassuntos.map((s) => {
            const r = recomendacoes.find((x) => x.subassunto_id === s.id);
            const cabeca = (
              <span className="grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 sm:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_auto]">
                <span className="flex min-w-0 items-center gap-2">
                  {r && <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-suave transition-transform group-open:rotate-90" />}
                  <span className="truncate font-medium text-tinta">{s.nome}</span>
                </span>
                <span className="col-span-2 sm:col-span-1 sm:col-start-2 sm:row-start-1">
                  <Barra linha={s} />
                </span>
                <span className="col-start-2 row-start-1 text-[13px] tabular-nums text-suave sm:col-start-3">
                  {s.acertos} de {s.respostas} ({porcento(s.percentual)})
                </span>
              </span>
            );
            return (
              <li key={s.id}>
                {r ? (
                  <details className="group" open={s.id === primeiroComErro}>
                    <summary className="cursor-pointer list-none px-5 py-3.5 hover:bg-canvas/60 [&::-webkit-details-marker]:hidden">{cabeca}</summary>
                    <div className="flex flex-col gap-3 bg-canvas/40 px-5 pb-4 pt-2 sm:pl-11">
                      <Estudar r={r} quem={quem} />
                    </div>
                  </details>
                ) : (
                  <div className="px-5 py-3.5">{cabeca}</div>
                )}
              </li>
            );
          })}
          {doAssunto && (
            <li>
              <details className="group">
                <summary className="flex cursor-pointer list-none items-center gap-2 px-5 py-3.5 hover:bg-canvas/60 [&::-webkit-details-marker]:hidden">
                  <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-suave transition-transform group-open:rotate-90" />
                  <span className="font-medium text-tinta">Outras questões de {a.nome}</span>
                </summary>
                <div className="flex flex-col gap-3 bg-canvas/40 px-5 pb-4 pt-2 sm:pl-11">
                  <Estudar r={doAssunto} quem={quem} />
                </div>
              </details>
            </li>
          )}
        </ul>
      )}
      <p className="border-t border-borda bg-canvas/50 px-5 py-3 text-[13px] text-suave">
        Os temas vêm do que mais pede atenção para o que vai melhor. Com poucas questões, um tema fica mais perto da {quem === "aluno" ? "sua média geral" : "média geral do aluno"}, para um erro isolado não virar alarme.
      </p>
    </Cartao>
  );
}

function Estudar({ r, quem }: { r: Recomendacao; quem: Quem }) {
  return (
    <>
      <p className="text-[13px] text-suave">
        {plural(r.erros, "erro", "erros")} aqui. {quem === "aluno" ? "Para estudar:" : "O que há no curso dele para indicar:"}
      </p>
      <MaterialParaRevisar t={r} />
    </>
  );
}
