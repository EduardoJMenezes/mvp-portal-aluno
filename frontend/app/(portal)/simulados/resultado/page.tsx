"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { GradeQuestoes } from "@/components/GradeQuestoes";
import { VideoSobDemanda } from "@/components/Player";
import { Anel, OndeRevisar } from "@/components/Resultado";
import { Botao, Cartao, Estado, Etiqueta, Pagina } from "@/components/ui";
import { api, useDados, type Resultado } from "@/lib/api";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDoResultado() {
  return (
    <Suspense>
      <ResultadoDoAluno />
    </Suspense>
  );
}

function ResultadoDoAluno() {
  const id = Number(useSearchParams().get("id"));
  const resultado = useDados(() => api.resultado(id), [id]);
  return (
    <Pagina titulo={resultado.dados?.titulo ?? "Resultado"} voltar={{ href: "/simulados/", rotulo: "Simulados" }}>
      <Estado {...resultado} linhas={4}>
        {(r) => <Painel r={r} />}
      </Estado>
    </Pagina>
  );
}

function Painel({ r }: { r: Resultado }) {
  const [indice, setIndice] = useState(() => Math.max(0, r.questoes.findIndex((q) => !q.correta)));
  const questao = r.questoes[indice];
  const mensagem = r.percentual >= 80 ? "Ótimo resultado." : r.percentual >= 60 ? "Bom resultado." : "Veja abaixo onde vale revisar.";

  return (
    <>
      <Cartao className="flex flex-col gap-6 p-6 sm:flex-row sm:items-center">
        <Anel percentual={r.percentual} />
        <div className="min-w-0 flex-1">
          <p className="text-lg font-semibold text-tinta">{mensagem}</p>
          <dl className="mt-3 grid grid-cols-3 gap-3">
            <Numero rotulo="Acertos" valor={`${r.acertos} de ${r.total}`} />
            <Numero rotulo="Posição" valor={`${r.posicao}º de ${r.participantes}`} />
            <Numero rotulo="Em branco" valor={String(r.em_branco)} />
          </dl>
          {r.entregue_automaticamente && <p className="mt-3 text-sm text-suave">A prova foi entregue automaticamente quando o tempo acabou.</p>}
        </div>
      </Cartao>

      <div className="grid gap-5 lg:grid-cols-[17rem_minmax(0,1fr)]">
        <aside className="lg:sticky lg:top-20 lg:self-start">
          <Cartao className="p-4">
            <h2 className="mb-3 text-sm font-semibold text-tinta">Questão a questão</h2>
            <GradeQuestoes
              estados={r.questoes.map((q) => (q.correta ? "acertou" : q.em_branco ? "sem_resposta" : "errou"))}
              atual={indice}
              aoEscolher={setIndice}
              rotulo="Ver a questão"
            />
            <ul className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-[13px] text-suave">
              <li className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-sucesso" aria-hidden="true" />acertou</li>
              <li className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-erro" aria-hidden="true" />errou</li>
              <li className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-atencao" aria-hidden="true" />em branco</li>
            </ul>
          </Cartao>
        </aside>

        {questao && <QuestaoCorrigida q={questao} indice={indice} total={r.questoes.length} aoTrocar={setIndice} />}
      </div>

      {r.analise.length > 0 && <OndeRevisar analise={r.analise} />}
    </>
  );
}

function Numero({ rotulo, valor }: { rotulo: string; valor: string }) {
  return (
    <div className="rounded-cartao bg-canvas px-3 py-2.5">
      <dt className="text-xs font-semibold uppercase tracking-wide text-suave">{rotulo}</dt>
      <dd className="mt-0.5 text-lg font-semibold tabular-nums text-tinta">{valor}</dd>
    </div>
  );
}

function QuestaoCorrigida({ q, indice, total, aoTrocar }: { q: Resultado["questoes"][number]; indice: number; total: number; aoTrocar: (i: number) => void }) {
  return (
    <section aria-label={`Questão ${indice + 1}`} className="min-w-0 rounded-cartao border border-borda bg-papel p-5 sm:p-7">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold text-tinta">Questão {q.ordem}</h2>
          {q.correta ? <Etiqueta tom="sucesso">Acertou</Etiqueta> : q.em_branco ? <Etiqueta tom="atencao">Em branco</Etiqueta> : <Etiqueta tom="erro">Errou</Etiqueta>}
        </div>
        <div className="flex gap-2">
          <Botao tamanho="pequeno" disabled={indice === 0} onClick={() => aoTrocar(indice - 1)}>← Anterior</Botao>
          <Botao tamanho="pequeno" disabled={indice >= total - 1} onClick={() => aoTrocar(indice + 1)}>Próxima →</Botao>
        </div>
      </div>

      <TextoFormatado texto={q.enunciado} />

      <ul className="mt-5 flex flex-col gap-2">
        {Object.entries(q.alternativas).map(([letra, texto]) => {
          const gabarito = letra === q.gabarito;
          const errada = letra === q.marcada && !q.correta;
          return (
            <li
              key={letra}
              className={`flex items-start gap-3 rounded-cartao border px-4 py-3 ${
                gabarito ? "border-sucesso-borda bg-sucesso-fundo" : errada ? "border-erro-borda bg-erro-fundo" : "border-borda bg-papel"
              }`}
            >
              <span
                aria-hidden="true"
                className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border text-sm font-semibold ${
                  gabarito ? "border-sucesso bg-sucesso text-white" : errada ? "border-erro bg-erro text-white" : "border-borda-campo text-tinta-2"
                }`}
              >
                {gabarito ? "✓" : errada ? "✗" : letra}
              </span>
              <div className="min-w-0 flex-1">
                <TextoFormatado texto={texto} compacto />
                {(gabarito || letra === q.marcada) && (
                  <p className={`mt-1 text-[13px] font-semibold ${gabarito ? "text-sucesso" : "text-erro"}`}>
                    {gabarito && letra === q.marcada ? "Gabarito · sua resposta" : gabarito ? `Gabarito (${letra})` : `Sua resposta (${letra})`}
                  </p>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      {(q.resolucao_comentada || q.resolucao) && (
        <div className="mt-6 flex flex-col gap-3 rounded-cartao bg-lilas p-4">
          <h3 className="text-sm font-semibold text-acento-forte">Resolução</h3>
          {q.resolucao_comentada && <TextoFormatado texto={q.resolucao_comentada} compacto />}
          {q.resolucao && <VideoSobDemanda video={q.resolucao} rotulo="Assistir resolução" />}
        </div>
      )}
    </section>
  );
}

