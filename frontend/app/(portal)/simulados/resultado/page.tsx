"use client";

import { ClipboardCheck, SquarePlay, type LucideIcon } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AnaliseDoSimulado } from "@/components/Analise";
import { GradeQuestoes } from "@/components/GradeQuestoes";
import { GraficosDoSimulado } from "@/components/GraficosDoSimulado";
import { VideoSobDemanda } from "@/components/Player";
import { Anel, OndeRevisar } from "@/components/Resultado";
import { Abas, Botao, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Resultado } from "@/lib/api";
import { plural } from "@/lib/formato";
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

// O resultado tinha tudo numa coluna só, e o aluno rolava muito para achar a prova e o que revisar.
// Agora são três perguntas, cada uma numa aba: como fui, o que errei, o que estudar.
type Aba = "geral" | "prova" | "revisar";
const ABAS: Aba[] = ["geral", "prova", "revisar"];

function Painel({ r }: { r: Resultado }) {
  const router = useRouter();
  const pedida = useSearchParams().get("aba") as Aba | null;
  const aba: Aba = pedida && ABAS.includes(pedida) ? pedida : "geral";
  const trocar = (nova: Aba) => router.replace(`/simulados/resultado/?id=${r.simulado_id}${nova === "geral" ? "" : `&aba=${nova}`}`, { scroll: false });
  const erradas = r.total - r.acertos;
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

      <Abas
        abas={[
          { valor: "geral", rotulo: "Visão geral" },
          { valor: "prova", rotulo: "Prova corrigida" },
          { valor: "revisar", rotulo: r.analise.length ? `Onde revisar (${r.analise.length})` : "Onde revisar" },
        ]}
        atual={aba}
        aoTrocar={trocar}
      />

      {aba === "geral" && (
        <>
          {r.analise_escrita && <AnaliseDoSimulado analise={r.analise_escrita} />}
          <GraficosDoSimulado r={r} />
          <div className="grid gap-3 md:grid-cols-2">
            <Atalho
              Icone={ClipboardCheck}
              titulo="Prova corrigida"
              texto={erradas ? `Veja ${plural(erradas, "questão que você não acertou", "questões que você não acertou")}, com o gabarito, a resolução e o comentário da alternativa que você marcou.` : "Reveja as questões, com o gabarito e a resolução de cada uma."}
              acao="Ver a prova"
              aoClicar={() => trocar("prova")}
            />
            <Atalho
              Icone={SquarePlay}
              titulo="Onde revisar"
              texto={r.analise.length ? `${plural(r.analise.length, "tema", "temas")} em que você errou, com as aulas e os vídeos que explicam cada um.` : "As aulas e os vídeos que explicam o que você errou aparecem aqui."}
              acao="Ver onde revisar"
              aoClicar={() => trocar("revisar")}
            />
          </div>
        </>
      )}

      {aba === "prova" && (
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
      )}

      {aba === "revisar" &&
        (r.analise.length > 0 ? (
          <OndeRevisar analise={r.analise} semTitulo legenda="Os temas em que você errou nesta prova, do que mais pesou para o que menos pesou, com as aulas e os vídeos que explicam cada um." />
        ) : (
          <Vazio titulo={erradas ? "Ainda sem material ligado ao que você errou" : "Nada a revisar nesta prova"}>
            {erradas ? "Quando houver aula ou vídeo sobre os temas desta prova, eles aparecem aqui." : "Você acertou todas as questões."}
          </Vazio>
        ))}
    </>
  );
}

/** A porta para as outras abas, no fim da visão geral: o aluno não precisa descobrir que elas existem. */
function Atalho({ Icone, titulo, texto, acao, aoClicar }: { Icone: LucideIcon; titulo: string; texto: string; acao: string; aoClicar: () => void }) {
  return (
    <Cartao como="div" className="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <Icone aria-hidden="true" className="size-5 text-acento" strokeWidth={2} />
        <h3 className="font-semibold text-tinta">{titulo}</h3>
      </div>
      <p className="text-[15px] text-suave">{texto}</p>
      <div>
        <Botao variante="secundario" tamanho="pequeno" onClick={aoClicar}>{acao}</Botao>
      </div>
    </Cartao>
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
                {(gabarito || letra === q.marcada) && q.comentarios?.[letra] && (
                  <div className="mt-1.5 border-t border-current/10 pt-1.5 text-tinta-2">
                    <TextoFormatado texto={q.comentarios[letra]} compacto />
                  </div>
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

