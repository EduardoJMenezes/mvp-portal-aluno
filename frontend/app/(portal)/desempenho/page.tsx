"use client";

import { ChevronRight } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { Barra, NIVEL, origem } from "@/components/Devolutiva";
import { MaterialParaRevisar } from "@/components/Resultado";
import { Abas, Cartao, Estado, Etiqueta, LinkDeTitulo, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type AssuntoNaDevolutiva, type Devolutiva, type Historico, type LinhaDaDevolutiva, type Recomendacao } from "@/lib/api";
import { emBrasilia, plural, porcento } from "@/lib/formato";

export default function PaginaDoDesempenho() {
  return (
    <Suspense>
      <MeuDesempenho />
    </Suspense>
  );
}

// A página era uma coluna só: todos os assuntos, depois tudo o que revisar, depois os simulados.
// Agora o aluno escolhe um assunto e vê, ao lado, os sub-assuntos dele e o que estudar em cada um.
type Aba = "assuntos" | "simulados";

function MeuDesempenho() {
  const router = useRouter();
  const parametros = useSearchParams();
  const aba: Aba = parametros.get("aba") === "simulados" ? "simulados" : "assuntos";
  const escolhido = Number(parametros.get("assunto")) || null;
  const dados = useDados(async () => {
    const [historico, devolutiva] = await Promise.all([api.historico(), api.minhaDevolutiva()]);
    return { historico, devolutiva };
  });
  // A aba e o assunto moram no endereço: voltar no navegador funciona.
  const ir = (nova: Aba, assunto?: number | null) =>
    router.replace(`/desempenho/${nova === "simulados" ? "?aba=simulados" : assunto ? `?assunto=${assunto}` : ""}`, { scroll: false });

  return (
    <Pagina titulo="Meu desempenho" legenda="Como você está indo em cada assunto, juntando as questões das aulas e os simulados com resultado liberado, e o que estudar em cada um.">
      <Estado {...dados} linhas={3}>
        {({ historico: h, devolutiva: d }) =>
          d.respostas === 0 && h.simulados.length === 0 ? (
            <Vazio titulo="Nada para mostrar ainda">Responda as questões das aulas ou faça um simulado: o seu desempenho por assunto aparece aqui.</Vazio>
          ) : (
            <>
              <Resumo d={d} h={h} />
              <Abas
                abas={[
                  { valor: "assuntos", rotulo: "Por assunto" },
                  { valor: "simulados", rotulo: h.simulados.length ? `Simulados (${h.simulados.length})` : "Simulados" },
                ]}
                atual={aba}
                aoTrocar={(nova) => ir(nova)}
              />
              {aba === "assuntos" &&
                (d.assuntos.length > 0 ? (
                  <PorAssunto d={d} escolhido={escolhido} aoEscolher={(id) => ir("assuntos", id)} />
                ) : (
                  <Vazio titulo="Suas questões ainda não têm assunto">Assim que o professor classificar as questões, o desempenho aparece separado por assunto.</Vazio>
                ))}
              {aba === "simulados" && <Simulados h={h} />}
            </>
          )
        }
      </Estado>
    </Pagina>
  );
}

/** Os pontos que o aluno estuda: o sub-assunto, ou o assunto que não tem nenhum. */
const pontos = (d: Devolutiva): LinhaDaDevolutiva[] => d.assuntos.flatMap((a) => (a.subassuntos.length ? a.subassuntos : [a]));

function Resumo({ d, h }: { d: Devolutiva; h: Historico }) {
  const temas = pontos(d);
  const atencao = temas.filter((t) => t.nivel === "ATENCAO").length;
  const media = h.simulados.length ? h.simulados.reduce((soma, s) => soma + s.percentual, 0) / h.simulados.length : null;
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <Numero rotulo="Questões respondidas" valor={String(d.respostas)} detalhe={d.respostas ? `${porcento(d.percentual ?? 0)} de acerto, entre aulas e simulados` : "nenhuma ainda"} />
      <Numero rotulo="Simulados" valor={String(h.simulados.length)} detalhe={media === null ? "nenhum com resultado" : `média de ${porcento(Math.round(media))}`} />
      <Numero rotulo="Pedem atenção" valor={String(atencao)} detalhe={temas.length ? `de ${plural(temas.length, "tema respondido", "temas respondidos")}` : "sem temas ainda"} />
    </div>
  );
}

function Numero({ rotulo, valor, detalhe }: { rotulo: string; valor: string; detalhe: string }) {
  return (
    <Cartao como="div" className="p-4">
      <p className="text-[13px] font-semibold uppercase tracking-wide text-suave">{rotulo}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-tinta">{valor}</p>
      <p className="truncate text-[13px] text-suave">{detalhe}</p>
    </Cartao>
  );
}

/** Os assuntos de um lado, do que mais pede atenção para o que vai melhor; do outro, o assunto escolhido. */
function PorAssunto({ d, escolhido, aoEscolher }: { d: Devolutiva; escolhido: number | null; aoEscolher: (id: number) => void }) {
  const atual = d.assuntos.find((a) => a.id === escolhido) ?? d.assuntos[0];
  return (
    <div className="flex flex-col gap-3">
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
        <Assunto key={atual.id} a={atual} recomendacoes={d.onde_revisar.filter((r) => r.assunto_id === atual.id)} />
      </div>
    </div>
  );
}

/** O assunto escolhido: cada sub-assunto com o acerto dele e, onde houve erro, o que estudar. */
function Assunto({ a, recomendacoes }: { a: AssuntoNaDevolutiva; recomendacoes: Recomendacao[] }) {
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
          {doAssunto ? <Estudar r={doAssunto} /> : <SemErros />}
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
                      <Estudar r={r} />
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
                  <Estudar r={doAssunto} />
                </div>
              </details>
            </li>
          )}
        </ul>
      )}
      <p className="border-t border-borda bg-canvas/50 px-5 py-3 text-[13px] text-suave">
        Os temas vêm do que mais pede atenção para o que vai melhor. Com poucas questões, um tema fica mais perto da sua média geral, para um erro isolado não virar alarme.
      </p>
    </Cartao>
  );
}

function Estudar({ r }: { r: Recomendacao }) {
  return (
    <>
      <p className="text-[13px] text-suave">{plural(r.erros, "erro", "erros")} aqui. Para estudar:</p>
      <MaterialParaRevisar t={r} />
    </>
  );
}

function SemErros() {
  return <p className="text-[15px] text-suave">Sem erros neste assunto até agora.</p>;
}

function Simulados({ h }: { h: Historico }) {
  if (!h.simulados.length) {
    return <Vazio titulo="Nenhum simulado com resultado ainda">Depois que um simulado fecha e o professor libera o resultado, ele aparece aqui, com a sua nota e a sua posição.</Vazio>;
  }
  return (
    <Cartao className="overflow-x-auto">
      <table className="tabela min-w-[36rem]">
        <thead>
          <tr>
            <th scope="col">Simulado</th>
            <th scope="col">Fechou</th>
            <th scope="col">Acertos</th>
            <th scope="col" className="w-2/5">Nota</th>
            <th scope="col">Posição</th>
          </tr>
        </thead>
        <tbody>
          {h.simulados.map((s) => (
            <tr key={s.simulado_id}>
              <td>
                <LinkDeTitulo href={`/simulados/resultado/?id=${s.simulado_id}`}>{s.titulo}</LinkDeTitulo>
              </td>
              <td className="whitespace-nowrap text-suave">{emBrasilia(s.fechou_em)}</td>
              <td className="whitespace-nowrap">{s.acertos} de {s.total}</td>
              <td>
                <div className="flex items-center gap-2">
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-lilas" aria-hidden="true">
                    <div className="h-full rounded-full bg-acento" style={{ width: `${s.percentual}%` }} />
                  </div>
                  <span className="w-14 text-right font-semibold">{porcento(s.percentual)}</span>
                </div>
              </td>
              <td className="whitespace-nowrap">{s.posicao}º de {s.participantes}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Cartao>
  );
}
