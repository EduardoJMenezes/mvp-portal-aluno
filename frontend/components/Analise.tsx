"use client";

// A análise do aluno: o retrato dele em texto, escrito pelo Claude do professor a partir das
// respostas e dos comentários aprovados em cada alternativa. O aluno lê; o professor lê a mesma
// e pode ocultar ou pedir outra. Quem dá o título de cada bloco é a tela, não o texto.

import { Sparkles } from "lucide-react";
import { useState } from "react";
import { Aviso, Botao, Campo, Cartao, Etiqueta, Revelar, TituloDeSecao } from "@/components/ui";
import { api, type AnaliseParaOProfessor, type TextoDaAnalise } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

const BLOCOS: { campo: "padroes" | "melhorou" | "proximos_passos"; titulo: string }[] = [
  { campo: "padroes", titulo: "Outros padrões nos erros" },
  { campo: "melhorou", titulo: "O que melhorou" },
  { campo: "proximos_passos", titulo: "Próximos passos" },
];

function Blocos({ analise }: { analise: TextoDaAnalise }) {
  return (
    <>
      <div className="flex gap-3 border-b border-borda bg-lilas/40 px-5 py-4">
        <Sparkles aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-acento" strokeWidth={2} />
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold uppercase tracking-wide text-acento-forte">O ponto principal agora</h3>
          <TextoFormatado texto={analise.ponto_principal} compacto className="mt-1 text-tinta" />
        </div>
      </div>
      {BLOCOS.map(({ campo, titulo }) => {
        const texto = analise[campo];
        if (!texto) return null;
        return (
          <div key={campo} className="border-b border-borda px-5 py-4 last:border-b-0">
            <h3 className="text-[15px] font-semibold text-tinta">{titulo}</h3>
            <TextoFormatado texto={texto} compacto className="mt-1 text-tinta-2" />
          </div>
        );
      })}
    </>
  );
}

/** Para o aluno, no alto de "Meu desempenho". */
export function MinhaAnalise({ analise, temNovidade }: { analise: TextoDaAnalise; temNovidade: boolean }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise">
      <TituloDeSecao>
        <span id="titulo-analise">Sua análise</span>
      </TituloDeSecao>
      <Cartao como="div" className="overflow-hidden">
        <Blocos analise={analise} />
        <p className="border-t border-borda bg-canvas/50 px-5 py-3 text-[13px] text-suave">
          Escrita por inteligência artificial em {emBrasilia(analise.escrita_em)}, a partir das suas respostas e do que o professor comentou em cada alternativa.
          {temNovidade && " Você já respondeu mais questões depois disso: elas entram na próxima."}
        </p>
      </Cartao>
    </section>
  );
}

/** Para o professor, no acompanhamento do aluno: a mesma análise, e o que fazer com ela. */
export function AnaliseDoAluno({ aluno, dados, aoMudar }: { aluno: string; dados: AnaliseParaOProfessor; aoMudar: (novo: AnaliseParaOProfessor) => void }) {
  const [pedindo, setPedindo] = useState(false);
  const [orientacao, setOrientacao] = useState("");
  const [erro, setErro] = useState("");

  async function aplicar(acao: () => Promise<AnaliseParaOProfessor>) {
    setErro("");
    try {
      aoMudar(await acao());
      setPedindo(false);
      setOrientacao("");
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível alterar a análise.");
    }
  }

  const { analise } = dados;
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise">
      <TituloDeSecao>
        <span id="titulo-analise">Análise personalizada</span>
      </TituloDeSecao>
      <p className="-mt-1 text-[15px] text-suave">
        O retrato que o aluno lê em &quot;Meu desempenho&quot;. Quem escreve é o Claude, com as respostas dele e os comentários que você aprovou em cada alternativa.
      </p>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {!analise ? (
        <Aviso tom="info">
          {dados.na_fila
            ? `Ainda sem análise. O aluno está na fila (${dados.motivo_da_fila}): ela é escrita na próxima rodada do Claude.`
            : "Ainda sem análise. O aluno entra na fila quando tiver ao menos 5 questões respondidas, entre aulas e simulados encerrados."}
        </Aviso>
      ) : (
        <Cartao como="div" className="overflow-hidden">
          <div className="flex flex-wrap items-center gap-2 border-b border-borda px-5 py-3">
            <Etiqueta tom={dados.oculta ? "atencao" : "sucesso"}>{dados.oculta ? "Oculta para o aluno" : "O aluno está vendo"}</Etiqueta>
            {dados.na_fila && <Etiqueta tom="info">Na fila: {dados.motivo_da_fila}</Etiqueta>}
            <span className="text-[13px] text-suave">
              Escrita em {emBrasilia(analise.escrita_em)}
              {dados.escrita_por ? `, no Claude de ${dados.escrita_por}` : ""}
            </span>
          </div>
          <Blocos analise={analise} />
          <div className="flex flex-col gap-3 border-t border-borda bg-canvas/50 px-5 py-3">
            <div className="flex flex-wrap items-center gap-3">
              <Botao tamanho="mini" onClick={() => aplicar(() => api.ocultarAnalise(aluno, !dados.oculta))}>
                {dados.oculta ? "Mostrar ao aluno" : "Ocultar do aluno"}
              </Botao>
              {dados.refazer_pedido_em ? (
                <span className="text-[13px] text-suave">
                  Outra análise pedida em {emBrasilia(dados.refazer_pedido_em)}
                  {dados.orientacao ? `, com a orientação: "${dados.orientacao}"` : ""}.
                </span>
              ) : (
                <Revelar aberto={pedindo} aoAlternar={() => setPedindo(!pedindo)}>
                  Pedir outra análise
                </Revelar>
              )}
            </div>
            {pedindo && !dados.refazer_pedido_em && (
              <div className="flex flex-col gap-3">
                <Campo rotulo="Orientação para quem escreve (opcional)" dica="Esta análise sai da frente do aluno, e ele vai para o começo da fila. A nova é escrita na próxima rodada do Claude.">
                  {(id) => (
                    <textarea id={id} className="campo min-h-20" maxLength={500} value={orientacao} onChange={(e) => setOrientacao(e.target.value)} placeholder="Ex.: não fale da questão 8, o gabarito estava em discussão." />
                  )}
                </Campo>
                <div>
                  <Botao variante="primario" tamanho="mini" onClick={() => aplicar(() => api.pedirOutraAnalise(aluno, orientacao))}>
                    Pedir outra
                  </Botao>
                </div>
              </div>
            )}
          </div>
        </Cartao>
      )}
    </section>
  );
}
