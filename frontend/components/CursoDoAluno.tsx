"use client";

// O curso de um aluno, visto pelo professor: quanto ele já fez em cada turma e, abrindo o módulo,
// item por item — assistiu, parou no meio ou nem começou.

import { Check, CircleHelp, FileText, Play, X, type LucideIcon } from "lucide-react";
import { Aviso, Cartao, Carregando, Progresso, TituloDeSecao } from "@/components/ui";
import { api, useDados, type ItemDoProgresso } from "@/lib/api";
import { dataCurta, emBrasilia, haQuantoTempo, relogio } from "@/lib/formato";

const ICONE: Record<ItemDoProgresso["tipo"], LucideIcon> = { VIDEO: Play, PDF: FileText, QUESTAO: CircleHelp };

export function CursoDoAluno({ aluno }: { aluno: string }) {
  const progresso = useDados(() => api.progressoDoAluno(aluno), [aluno]);

  if (progresso.erro) return <Aviso tom="erro" titulo="Não deu para carregar o curso do aluno">{progresso.erro}</Aviso>;
  if (!progresso.dados) return <Carregando linhas={2} />;
  const { turmas, ultima_atividade } = progresso.dados;

  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-no-curso">
      <TituloDeSecao acao={<p className="text-sm text-suave" title={ultima_atividade ? emBrasilia(ultima_atividade) : undefined}>{ultima_atividade ? `Última atividade ${haQuantoTempo(ultima_atividade)}` : "Ainda não começou o curso"}</p>}>
        <span id="titulo-no-curso">No curso</span>
      </TituloDeSecao>
      {turmas.length === 0 && <Aviso tom="info">Este aluno não está em nenhuma turma.</Aviso>}
      {turmas.map((turma) => (
        <Cartao key={turma.turma_id} className="overflow-hidden">
          <div className="flex flex-col gap-2 px-5 py-4">
            <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
              <h3 className="text-lg font-bold text-tinta">{turma.turma}</h3>
              <p className="text-sm tabular-nums text-tinta-2">
                {turma.total === 0 ? "Nada publicado para esta turma" : `${turma.concluidos} de ${turma.total} concluídos (${Math.round((turma.concluidos / turma.total) * 100)}%)`}
              </p>
            </div>
            {turma.total > 0 && <Progresso feitos={turma.concluidos} total={turma.total} rotulo={`Progresso em ${turma.turma}`} className="h-2" />}
          </div>
          {turma.modulos.map((modulo) => (
            <details key={modulo.id} className="group border-t border-borda">
              <summary className="flex cursor-pointer list-none items-center gap-4 px-5 py-3 hover:bg-canvas/60 [&::-webkit-details-marker]:hidden">
                <span className="min-w-0 flex-1 truncate font-semibold text-tinta">{modulo.nome}</span>
                <Progresso feitos={modulo.concluidos} total={modulo.total} rotulo={`Progresso em ${modulo.nome}`} className="hidden w-32 shrink-0 sm:block" />
                <span className="w-16 shrink-0 text-right text-sm tabular-nums text-suave">{modulo.concluidos} de {modulo.total}</span>
                <span aria-hidden="true" className="text-suave transition-transform group-open:rotate-90">›</span>
              </summary>
              <div className="flex flex-col gap-3 bg-canvas/40 px-5 pb-4 pt-2">
                {modulo.submodulos.map((sub) => (
                  <div key={sub.nome}>
                    <p className="mb-1 text-[13px] font-semibold text-suave">{sub.nome}</p>
                    <ul className="divide-y divide-borda/70 rounded-xl border border-borda bg-papel">
                      {sub.itens.map((item) => (
                        <Linha key={item.id} item={item} />
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </details>
          ))}
        </Cartao>
      ))}
    </section>
  );
}

function Linha({ item }: { item: ItemDoProgresso }) {
  const Icone = ICONE[item.tipo];
  const comecou = !item.concluido && item.tipo === "VIDEO" && (item.posicao_segundos ?? 0) > 0;
  return (
    <li className="flex items-center gap-3 px-3 py-2 text-[15px]">
      <Icone aria-hidden="true" className="size-4 shrink-0 text-suave" strokeWidth={1.9} />
      <span className="min-w-0 flex-1 truncate text-tinta">{item.nome}</span>
      {item.tipo === "QUESTAO" && item.concluido ? (
        <span className={`inline-flex shrink-0 items-center gap-1 text-sm font-semibold ${item.correta ? "text-sucesso" : "text-erro"}`} title={emBrasilia(item.concluido_em)}>
          {item.correta ? <Check aria-hidden="true" className="size-4" strokeWidth={3} /> : <X aria-hidden="true" className="size-4" strokeWidth={3} />}
          {item.correta ? "Acertou" : "Errou"}
        </span>
      ) : item.concluido ? (
        <span className="inline-flex shrink-0 items-center gap-1 text-sm font-semibold text-sucesso" title={emBrasilia(item.concluido_em)}>
          <Check aria-hidden="true" className="size-4" strokeWidth={3} />
          {item.tipo === "VIDEO" ? "Assistiu" : "Leu"} em {dataCurta(item.concluido_em)}
        </span>
      ) : comecou ? (
        <span className="shrink-0 text-sm tabular-nums text-atencao">
          Parou em {relogio(item.posicao_segundos ?? 0)}
          {item.duracao_segundos ? ` de ${relogio(item.duracao_segundos)}` : ""}
        </span>
      ) : (
        <span className="shrink-0 text-sm text-suave">{item.tipo === "QUESTAO" ? "Não respondeu" : "Não começou"}</span>
      )}
    </li>
  );
}
