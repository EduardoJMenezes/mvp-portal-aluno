"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PorAssunto, ResumoDaDevolutiva } from "@/components/Devolutiva";
import { Aviso, BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type QuestaoDaAulaNaTurma } from "@/lib/api";
import { plural, porcento } from "@/lib/formato";
import { LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDoDesempenhoDaTurma() {
  return (
    <Suspense>
      <DesempenhoDaTurma />
    </Suspense>
  );
}

/** Onde a turma está errando: por assunto, e questão a questão nas aulas, com o que cada um marcou. */
function DesempenhoDaTurma() {
  const turma = Number(useSearchParams().get("turma"));
  const dados = useDados(() => api.devolutivaDaTurma(turma), [turma]);

  return (
    <Pagina
      titulo={dados.dados ? `Desempenho · ${dados.dados.turma}` : "Desempenho da turma"}
      legenda="Onde a turma acerta e onde erra, juntando as questões das aulas e os simulados entregues."
      voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}
      acoes={<BotaoLink href={`/admin/turmas/alunos/?turma=${turma}`}>Ver aluno por aluno</BotaoLink>}
    >
      <Estado {...dados} linhas={4}>
        {(d) => {
          const respondidas = d.questoes_da_aula.filter((q) => q.respostas > 0);
          const semResposta = d.questoes_da_aula.length - respondidas.length;
          return d.alunos === 0 ? (
            <Vazio titulo="Esta turma ainda não tem alunos">Quando houver alunos respondendo, o desempenho aparece aqui.</Vazio>
          ) : (
            <>
              <section className="flex flex-col gap-3" aria-labelledby="titulo-por-assunto">
                <TituloDeSecao>
                  <span id="titulo-por-assunto">Por assunto</span>
                </TituloDeSecao>
                <div className="-mt-1 flex flex-col gap-0.5">
                  <ResumoDaDevolutiva dados={d.geral} quem="A turma" />
                  <p className="text-[15px] text-suave">
                    {d.responderam} de {plural(d.alunos, "aluno")} já {d.responderam === 1 ? "respondeu" : "responderam"} alguma questão.
                  </p>
                </div>
                {d.geral.assuntos.length > 0 ? (
                  <PorAssunto dados={d.geral} />
                ) : (
                  d.geral.respostas > 0 && <Vazio titulo="Nenhuma questão respondida tem assunto">Classifique as questões no banco para ver o desempenho separado por assunto.</Vazio>
                )}
                {d.geral.sem_assunto > 0 && (
                  <Aviso tom="atencao">
                    {d.geral.questoes_sem_assunto === 1 ? "1 questão respondida não tem" : `${d.geral.questoes_sem_assunto} questões respondidas não têm`} assunto, e por isso {d.geral.sem_assunto === 1 ? "1 resposta fica" : `${d.geral.sem_assunto} respostas ficam`} de fora desta leitura.{" "}
                    <Link href="/admin/questoes/" className="font-semibold underline">Classificar no banco de questões</Link>
                  </Aviso>
                )}
              </section>

              <section className="flex flex-col gap-3" aria-labelledby="titulo-questoes">
                <TituloDeSecao>
                  <span id="titulo-questoes">Questões das aulas</span>
                </TituloDeSecao>
                <p className="-mt-1 text-[15px] text-suave">Das mais erradas para as mais acertadas, com quantos alunos marcaram cada alternativa.</p>
                {respondidas.length === 0 ? (
                  <Vazio titulo={d.questoes_da_aula.length === 0 ? "A turma não tem questões nas aulas" : "Ninguém respondeu ainda"}>
                    {d.questoes_da_aula.length === 0 ? "Ponha questões nos módulos, em Montar o curso." : `${plural(d.questoes_da_aula.length, "questão publicada", "questões publicadas")} nas aulas, ainda sem resposta.`}
                  </Vazio>
                ) : (
                  <ul className="grid gap-3 lg:grid-cols-2">
                    {respondidas.map((q) => (
                      <Questao key={q.item_id} questao={q} />
                    ))}
                  </ul>
                )}
                {respondidas.length > 0 && semResposta > 0 && (
                  <p className="text-[13px] text-suave">{semResposta === 1 ? "Outra questão das aulas ainda não teve" : `Outras ${semResposta} questões das aulas ainda não tiveram`} resposta.</p>
                )}
              </section>
            </>
          );
        }}
      </Estado>
    </Pagina>
  );
}

function Questao({ questao: q }: { questao: QuestaoDaAulaNaTurma }) {
  // As letras que a questão tem não vêm na resposta: mostramos de A a D, mais a E se alguém marcou ou se ela é o gabarito.
  const letras = LETRAS.filter((l) => l !== "E" || q.distribuicao.E || q.gabarito === "E");
  const erradas = letras.filter((l) => l !== q.gabarito && (q.distribuicao[l] ?? 0) > 0);
  const maisEnganou = erradas.length ? erradas.reduce((a, b) => ((q.distribuicao[b] ?? 0) > (q.distribuicao[a] ?? 0) ? b : a)) : null;
  const tom = q.percentual < 50 ? "erro" : q.percentual < 75 ? "atencao" : "sucesso";

  return (
    <Cartao como="li" className="flex flex-col gap-3 p-5">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1.5">
        <div className="min-w-0">
          <p className="text-[13px] text-suave">{q.modulo}</p>
          <h3 className="font-semibold text-tinta">{q.item}</h3>
        </div>
        <Etiqueta tom={tom}>{porcento(q.percentual)} de acerto</Etiqueta>
      </div>
      {/* O enunciado traz Markdown e fórmula: sem formatar, apareceria o código da fórmula. */}
      <div className="line-clamp-2 text-[15px] text-tinta-2">
        <TextoFormatado texto={q.resumo} compacto />
      </div>
      <ul className="flex flex-col gap-1.5" aria-label="Quantos marcaram cada alternativa">
        {letras.map((l) => {
          const n = q.distribuicao[l] ?? 0;
          const certa = l === q.gabarito;
          return (
            <li key={l} className="grid grid-cols-[1.75rem_minmax(0,1fr)_auto] items-center gap-2.5 text-sm">
              <span className={`flex size-7 items-center justify-center rounded-full text-xs font-bold ${certa ? "bg-sucesso-vivo text-white" : "border border-borda-campo/70 text-tinta-2"}`}>
                {l}
                <span className="sr-only">{certa ? " (gabarito)" : ""}</span>
              </span>
              <span aria-hidden="true" className="block h-2 overflow-hidden rounded-full bg-gelo">
                <span className={`block h-full rounded-full ${certa ? "bg-sucesso-vivo" : l === maisEnganou ? "bg-erro-vivo" : "bg-apagado"}`} style={{ width: `${q.respostas ? (n / q.respostas) * 100 : 0}%` }} />
              </span>
              <span className="tabular-nums text-suave">
                {n}
                {l === maisEnganou && n > 1 && <span className="ml-1.5 font-semibold text-erro">a que mais enganou</span>}
              </span>
            </li>
          );
        })}
      </ul>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-borda pt-3 text-[13px] text-suave">
        <span>
          {plural(q.respostas, "resposta")}
          {q.topico ? `, ${q.topico}` : ", sem assunto"}
        </span>
        <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="font-semibold text-acento hover:underline">Abrir a questão #{q.questao_id}</Link>
      </div>
    </Cartao>
  );
}
