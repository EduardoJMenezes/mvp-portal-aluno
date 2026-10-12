"use client";

// A leitura crua da prova, sem IA: como as respostas se dividem e o acerto em cada assunto, ao
// lado da média de quem fez. O mapa questão a questão já mora no resultado (GradeQuestoes).

import { Cartao, TituloDeSecao } from "@/components/ui";
import type { Resultado } from "@/lib/api";
import { plural, porcento } from "@/lib/formato";

const PARTES = [
  { chave: "acertos", rotulo: "acertou", cor: "bg-sucesso" },
  { chave: "erros", rotulo: "errou", cor: "bg-erro" },
  { chave: "brancos", rotulo: "em branco", cor: "bg-atencao" },
] as const;

export function GraficosDoSimulado({ r }: { r: Resultado }) {
  const conta = { acertos: r.acertos, erros: r.total - r.acertos - r.em_branco, brancos: r.em_branco };
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-graficos">
      <TituloDeSecao>
        <span id="titulo-graficos">Como você foi</span>
      </TituloDeSecao>
      <Cartao como="div" className="flex flex-col gap-6 p-5">
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold text-tinta">As {r.total} questões</h3>
          <div className="flex h-3 overflow-hidden rounded-full bg-gelo" role="img" aria-label={`${conta.acertos} acertos, ${conta.erros} erros e ${conta.brancos} em branco`}>
            {PARTES.map((p) => conta[p.chave] > 0 && <span key={p.chave} className={`h-full ${p.cor}`} style={{ width: `${(100 * conta[p.chave]) / r.total}%` }} />)}
          </div>
          <ul className="flex flex-wrap gap-x-4 gap-y-1 text-[13px] text-suave">
            {PARTES.map((p) => (
              <li key={p.chave} className="flex items-center gap-1.5">
                <span className={`size-2.5 rounded-sm ${p.cor}`} aria-hidden="true" />
                <span className="font-semibold tabular-nums text-tinta">{conta[p.chave]}</span> {p.rotulo}
              </li>
            ))}
          </ul>
        </div>

        {r.por_assunto.length > 0 && (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-sm font-semibold text-tinta">Por assunto</h3>
              <p className="flex items-center gap-3 text-[13px] text-suave">
                <span className="flex items-center gap-1.5"><span className="h-2 w-4 rounded-full bg-acento" aria-hidden="true" />você</span>
                <span className="flex items-center gap-1.5"><span className="h-3.5 w-0.5 bg-tinta" aria-hidden="true" />média de quem fez</span>
              </p>
            </div>
            <ul className="flex flex-col gap-3">
              {r.por_assunto.map((a) => (
                <li key={a.assunto} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto]">
                  <span className="truncate text-[15px] font-medium text-tinta">{a.assunto}</span>
                  <span className="relative col-span-2 block h-2.5 rounded-full bg-gelo sm:col-span-1 sm:col-start-2 sm:row-start-1" aria-hidden="true">
                    <span className="block h-full rounded-full bg-acento" style={{ width: `${Math.max(a.percentual, 2)}%` }} />
                    <span className="absolute -top-1 h-[18px] w-0.5 bg-tinta" style={{ left: `calc(${Math.min(a.media_da_turma, 99.5)}% - 1px)` }} />
                  </span>
                  <span className="col-start-2 row-start-1 text-right text-[13px] tabular-nums text-suave sm:col-start-3">
                    <span className="font-semibold text-tinta">{a.acertos} de {a.questoes}</span> · turma {porcento(Math.round(a.media_da_turma))}
                  </span>
                </li>
              ))}
            </ul>
            <p className="text-[13px] text-suave">
              {plural(r.participantes, "aluno fez", "alunos fizeram")} esta prova. Questão em branco conta como erro, para você e para a média.
            </p>
          </div>
        )}
      </Cartao>
    </section>
  );
}
