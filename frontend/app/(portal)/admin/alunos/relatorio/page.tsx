"use client";

// A análise geral de um aluno numa página só, para o professor imprimir ou salvar em PDF e levar
// para a conversa: o texto do Claude, e ao lado os números de onde ele saiu. Continua sendo do
// professor: o aluno não tem como abrir esta página.

import { ArrowLeft, Printer, Sparkles } from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { TextoDaAnaliseGeral } from "@/components/Analise";
import { NIVEL } from "@/components/Devolutiva";
import { AssuntosAoLadoDosColegas, EvolucaoNosSimulados, TabelaDeSimulados } from "@/components/PanoramaDoAluno";
import { Aviso, Botao, Estado } from "@/components/ui";
import { api, useDados, type Panorama } from "@/lib/api";
import { dataCurta, emBrasilia, plural, porcento } from "@/lib/formato";

export default function PaginaDoRelatorio() {
  return (
    <Suspense>
      <Relatorio />
    </Suspense>
  );
}

function Relatorio() {
  const aluno = useSearchParams().get("aluno") ?? "";
  const dados = useDados(async () => {
    const [panorama, analise] = await Promise.all([api.panorama(aluno), api.analiseDoAluno(aluno)]);
    return { panorama, analise };
  }, [aluno]);

  return (
    <main className="mx-auto w-full max-w-4xl px-4 pb-16 pt-6 sm:px-6 print:max-w-none print:p-0">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Link href={`/admin/alunos/?aluno=${encodeURIComponent(aluno)}`} className="inline-flex items-center gap-1.5 text-sm font-medium text-suave hover:text-acento">
          <ArrowLeft aria-hidden="true" className="size-4" /> Acompanhamento do aluno
        </Link>
        <Botao variante="primario" onClick={() => window.print()}>
          <Printer aria-hidden="true" className="size-4" /> Imprimir ou salvar em PDF
        </Botao>
      </div>
      <Estado {...dados} linhas={6}>
        {({ panorama: p, analise }) => (
          <article className="relatorio flex flex-col gap-6 rounded-cartao border border-borda/70 bg-papel p-6 shadow-suave sm:p-8 print:rounded-none print:border-0 print:p-0 print:shadow-none">
            <header className="flex flex-col gap-1 border-b border-borda pb-4">
              <p className="text-[13px] font-semibold uppercase tracking-wide text-acento-forte">Acompanhamento individual</p>
              <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em] text-tinta">{p.aluno}</h1>
              <p className="text-[15px] text-suave">
                {p.turmas.join(", ") || "Sem turma"} · {dataCurta(new Date().toISOString())}
              </p>
            </header>

            <Numeros p={p} />

            <section className="flex flex-col gap-3">
              <h2 className="text-xl font-bold tracking-[-0.01em] text-tinta">Análise</h2>
              {analise.analise ? (
                <>
                  <div className="overflow-hidden rounded-cartao border border-borda/70">
                    <TextoDaAnaliseGeral analise={analise.analise} />
                  </div>
                  <p className="flex items-start gap-1.5 text-[13px] text-suave">
                    <Sparkles aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                    <span>
                      Escrita por inteligência artificial em {emBrasilia(analise.analise.escrita_em)}, a partir das respostas do aluno e do que o professor comentou em cada alternativa.
                      {!!p.respostas_novas && ` Desde então, ${plural(p.respostas_novas, "resposta nova", "respostas novas")}: os números abaixo já as contam.`}
                    </span>
                  </p>
                </>
              ) : (
                <Aviso tom="info" titulo="Ainda sem análise geral">
                  Peça ao Claude: &ldquo;Faça a análise geral de {p.aluno}&rdquo;. Os números abaixo já estão prontos.
                </Aviso>
              )}
            </section>

            <section className="flex flex-col gap-3">
              <h2 className="text-xl font-bold tracking-[-0.01em] text-tinta">Simulados</h2>
              <EvolucaoNosSimulados simulados={p.simulados} quem={p.aluno.split(" ")[0]} />
              <TabelaDeSimulados simulados={p.simulados} />
            </section>

            <section className="flex flex-col gap-3">
              <h2 className="text-xl font-bold tracking-[-0.01em] text-tinta">Por assunto</h2>
              <AssuntosAoLadoDosColegas p={p} />
              <Subassuntos p={p} />
            </section>
          </article>
        )}
      </Estado>
    </main>
  );
}

function Numeros({ p }: { p: Panorama }) {
  const { curso } = p;
  const itens = [
    { rotulo: "Questões respondidas", valor: String(p.acertos + p.erros), detalhe: p.acertos + p.erros ? `${porcento(p.acerto_no_que_respondeu ?? 0)} de acerto` : "nenhuma ainda" },
    { rotulo: "Acertos", valor: String(p.acertos), detalhe: `${p.erros} marcadas erradas` },
    { rotulo: "Em branco", valor: String(p.em_branco), detalhe: "fora da leitura por assunto" },
    { rotulo: "No curso", valor: curso.total ? porcento(Math.round((100 * curso.concluidos) / curso.total)) : "—", detalhe: curso.total ? `${curso.concluidos} de ${curso.total} concluídos` : "nada publicado" },
  ];
  return (
    <dl className="grid break-inside-avoid grid-cols-2 gap-3 sm:grid-cols-4">
      {itens.map((i) => (
        <div key={i.rotulo} className="rounded-cartao border border-borda/70 p-3">
          <dt className="text-[12px] font-semibold uppercase tracking-wide text-suave">{i.rotulo}</dt>
          <dd className="mt-0.5 text-2xl font-semibold tabular-nums text-tinta">{i.valor}</dd>
          <dd className="text-[13px] text-suave">{i.detalhe}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Os sub-assuntos de cada assunto, em uma linha por assunto: é a tabela de consulta do relatório. */
function Subassuntos({ p }: { p: Panorama }) {
  const comSubs = p.por_assunto.filter((a) => a.subassuntos.length > 0);
  if (!comSubs.length) return null;
  return (
    <table className="tabela">
      <thead>
        <tr>
          <th scope="col">Assunto</th>
          <th scope="col">Sub-assuntos (acertos no que respondeu)</th>
        </tr>
      </thead>
      <tbody>
        {comSubs.map((a) => (
          <tr key={a.id} className="break-inside-avoid align-top">
            <td className="whitespace-nowrap font-medium text-tinta">{a.nome}</td>
            <td>
              <ul className="flex flex-wrap gap-x-4 gap-y-1">
                {a.subassuntos.map((s) => (
                  <li key={s.id} className="flex items-center gap-1.5 text-[14px] text-tinta-2">
                    <span className={`size-2 shrink-0 rounded-full ${s.nivel ? NIVEL[s.nivel].barra : "bg-gelo"}`} aria-hidden="true" />
                    {s.nome} <span className="tabular-nums text-suave">{s.respondidas ? `${s.acertos} de ${s.respondidas}` : "não respondeu"}{s.em_branco > 0 ? `, ${s.em_branco} em branco` : ""}</span>
                  </li>
                ))}
              </ul>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
