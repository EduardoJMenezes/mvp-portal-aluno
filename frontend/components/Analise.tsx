"use client";

// A análise escrita de um aluno. Quem escreve é o Claude do professor, a partir das respostas e
// dos comentários aprovados em cada alternativa. São duas: a de um simulado, escrita para o aluno,
// que a lê no resultado da prova; e a geral, escrita para o professor, que só ele vê. Quem dá o
// título de cada bloco é a tela, não o texto.

import { Sparkles } from "lucide-react";
import { useState } from "react";
import { Aviso, Botao, Cartao, Etiqueta, Revelar, TituloDeSecao } from "@/components/ui";
import { api, type AnaliseParaOProfessor, type TextoDaAnalise } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

const BLOCOS: { campo: "padroes" | "melhorou" | "proximos_passos"; titulo: string }[] = [
  { campo: "padroes", titulo: "Outros padrões nos erros" },
  { campo: "melhorou", titulo: "O que melhorou" },
  { campo: "proximos_passos", titulo: "Próximos passos" },
];

function Blocos({ analise }: { analise: TextoDaAnalise }) {
  return (
    <>
      <div className="flex gap-3 border-b border-borda bg-lilas/40 px-5 py-4 last:border-b-0">
        <Sparkles aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-acento" strokeWidth={2} />
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold uppercase tracking-wide text-acento-forte">O ponto principal</h3>
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

/** Para o aluno, no resultado do simulado. */
export function AnaliseDoSimulado({ analise }: { analise: TextoDaAnalise }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise">
      <TituloDeSecao>
        <span id="titulo-analise">Sua análise</span>
      </TituloDeSecao>
      <Cartao como="div" className="overflow-hidden">
        <Blocos analise={analise} />
        <p className="border-t border-borda bg-canvas/50 px-5 py-3 text-[13px] text-suave">
          Escrita por inteligência artificial em {emBrasilia(analise.escrita_em)}, a partir das suas respostas nesta prova e do que o professor comentou em cada alternativa.
        </p>
      </Cartao>
    </section>
  );
}

function Anteriores({ anteriores }: { anteriores: TextoDaAnalise[] }) {
  const [aberto, setAberto] = useState(false);
  if (!anteriores.length) return null;
  return (
    <div className="flex flex-col gap-3">
      <div>
        <Revelar aberto={aberto} aoAlternar={() => setAberto(!aberto)}>
          {aberto ? "Esconder as anteriores" : `Ver ${plural(anteriores.length, "análise anterior", "análises anteriores")}`}
        </Revelar>
      </div>
      {aberto &&
        anteriores.map((a) => (
          <Cartao key={a.escrita_em} como="div" className="overflow-hidden opacity-90">
            <p className="border-b border-borda bg-canvas/50 px-5 py-2 text-[13px] font-semibold text-suave">Escrita em {emBrasilia(a.escrita_em)}</p>
            <Blocos analise={a} />
          </Cartao>
        ))}
    </div>
  );
}

/** A frase que o professor diz ao Claude, pronta para copiar. */
function Pedido({ frase }: { frase: string }) {
  return <span className="rounded-md bg-lilas/60 px-1.5 py-0.5 font-medium text-tinta">&ldquo;{frase}&rdquo;</span>;
}

/** Para o professor: a análise geral do aluno, de tudo o que ele respondeu. O aluno não vê. */
export function AnaliseGeral({ dados, aoAtualizar }: { dados: AnaliseParaOProfessor; aoAtualizar: () => unknown }) {
  const { analise } = dados;
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise-geral">
      <TituloDeSecao acao={<Botao tamanho="mini" onClick={() => aoAtualizar()}>Atualizar</Botao>}>
        <span id="titulo-analise-geral">Análise geral</span>
      </TituloDeSecao>
      <p className="-mt-1 text-[15px] text-suave">
        A leitura de tudo o que o aluno respondeu, em aulas e simulados, escrita pelo Claude para você. O aluno não vê.
      </p>
      {!analise ? (
        <Aviso tom="info" titulo="Ainda sem análise geral">
          Peça ao Claude: <Pedido frase={`Faça a análise geral de ${dados.aluno}`} />. Ele mostra o texto na conversa e, com o seu ok, grava aqui.
        </Aviso>
      ) : (
        <>
          <Cartao como="div" className="overflow-hidden">
            <p className="border-b border-borda px-5 py-3 text-[13px] text-suave">
              Escrita em {emBrasilia(analise.escrita_em)}
              {dados.escrita_por ? `, no Claude de ${dados.escrita_por}` : ""}. Para uma nova, peça ao Claude: <Pedido frase={`Refaça a análise geral de ${dados.aluno}`} />.
            </p>
            <Blocos analise={analise} />
          </Cartao>
          <Anteriores anteriores={dados.anteriores} />
        </>
      )}
    </section>
  );
}

/** Para o professor: a análise que o aluno lê no resultado de um simulado, e o botão de ocultar. */
export function AnaliseDoSimuladoParaOProfessor({ aluno, simuladoId, dados, aoMudar }: { aluno: string; simuladoId: number; dados: AnaliseParaOProfessor; aoMudar: (novo: AnaliseParaOProfessor) => void }) {
  const [erro, setErro] = useState("");
  const { analise } = dados;

  async function alternar() {
    setErro("");
    try {
      aoMudar(await api.ocultarAnalise(aluno, simuladoId, !dados.oculta));
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível alterar a análise.");
    }
  }

  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise-do-simulado">
      <TituloDeSecao>
        <span id="titulo-analise-do-simulado">Análise deste simulado</span>
      </TituloDeSecao>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {!analise ? (
        <Aviso tom="info">
          O aluno ainda não tem análise de &ldquo;{dados.simulado}&rdquo;. Peça ao Claude: <Pedido frase={`Gere o resultado do ${dados.simulado}`} />.
        </Aviso>
      ) : (
        <>
          <Cartao como="div" className="overflow-hidden">
            <div className="flex flex-wrap items-center gap-2 border-b border-borda px-5 py-3">
              <Etiqueta tom={dados.oculta ? "atencao" : "sucesso"}>{dados.oculta ? "Oculta para o aluno" : "É a que o aluno lê no resultado"}</Etiqueta>
              <span className="text-[13px] text-suave">
                Escrita em {emBrasilia(analise.escrita_em)}
                {dados.escrita_por ? `, no Claude de ${dados.escrita_por}` : ""}
              </span>
              <span className="ml-auto">
                <Botao tamanho="mini" onClick={alternar}>
                  {dados.oculta ? "Mostrar ao aluno" : "Ocultar do aluno"}
                </Botao>
              </span>
            </div>
            <Blocos analise={analise} />
          </Cartao>
          <Anteriores anteriores={dados.anteriores} />
        </>
      )}
    </section>
  );
}
