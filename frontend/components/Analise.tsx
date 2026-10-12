"use client";

// A análise escrita de um aluno. Quem escreve é o Claude do professor, a partir das respostas e
// dos comentários aprovados em cada alternativa. São duas: a de um simulado, escrita para o aluno,
// que a lê no resultado da prova; e a geral, escrita para o professor, que só ele vê. Quem dá o
// título de cada bloco é a tela, não o texto.

import { Sparkles } from "lucide-react";
import { useState } from "react";
import { Aviso, Botao, BotaoLink, Cartao, Etiqueta, Revelar, TituloDeSecao } from "@/components/ui";
import { api, type AnaliseParaOProfessor, type TextoDaAnalise } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

type Bloco = "padroes" | "melhorou" | "proximos_passos";
const CAMPOS: Bloco[] = ["padroes", "melhorou", "proximos_passos"];
// Os mesmos três campos, lidos de dois jeitos: a do simulado fala com o aluno sobre uma prova; a
// geral fala com o professor sobre o histórico inteiro, e termina no que levar para a conversa.
const DO_SIMULADO: Record<Bloco, string> = { padroes: "Outros padrões nos erros", melhorou: "O que melhorou", proximos_passos: "Próximos passos" };
const DA_GERAL: Record<Bloco, string> = { padroes: "Onde erra e por quê", melhorou: "Evolução", proximos_passos: "Para a conversa" };

function Blocos({ analise, titulos = DO_SIMULADO }: { analise: TextoDaAnalise; titulos?: Record<Bloco, string> }) {
  return (
    <>
      <div className="flex gap-3 border-b border-borda bg-lilas/40 px-5 py-4 last:border-b-0">
        <Sparkles aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-acento" strokeWidth={2} />
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold uppercase tracking-wide text-acento-forte">O ponto principal</h3>
          <TextoFormatado texto={analise.ponto_principal} compacto className="mt-1 text-tinta" />
        </div>
      </div>
      {CAMPOS.map((campo) => {
        const texto = analise[campo];
        if (!texto) return null;
        return (
          <div key={campo} className="break-inside-avoid border-b border-borda px-5 py-4 last:border-b-0">
            <h3 className="text-[15px] font-semibold text-tinta">{titulos[campo]}</h3>
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

/** Os blocos da análise geral, sem moldura: a página para imprimir põe a dela. */
export function TextoDaAnaliseGeral({ analise }: { analise: TextoDaAnalise }) {
  return <Blocos analise={analise} titulos={DA_GERAL} />;
}

function Anteriores({ anteriores, titulos }: { anteriores: TextoDaAnalise[]; titulos?: Record<Bloco, string> }) {
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
            <Blocos analise={a} titulos={titulos} />
          </Cartao>
        ))}
    </div>
  );
}

/** A frase que o professor diz ao Claude, pronta para copiar. */
function Pedido({ frase }: { frase: string }) {
  return <span className="rounded-md bg-lilas/60 px-1.5 py-0.5 font-medium text-tinta">&ldquo;{frase}&rdquo;</span>;
}

/** O pedido pronto para colar no Claude: o professor não precisa lembrar a frase nem digitar o nome. */
function CopiarPedido({ frase, rotulo }: { frase: string; rotulo: string }) {
  const [copiado, setCopiado] = useState(false);
  return (
    <Botao
      tamanho="mini"
      title={`"${frase}"`}
      onClick={async () => {
        await navigator.clipboard?.writeText(frase);
        setCopiado(true);
        setTimeout(() => setCopiado(false), 2500);
      }}
    >
      {copiado ? "Copiado: cole no Claude" : rotulo}
    </Botao>
  );
}

/**
 * Para o professor: a análise geral do aluno, de tudo o que ele respondeu. O aluno não vê.
 * `novas`: quantas respostas chegaram depois de ela ser escrita.
 */
export function AnaliseGeral({ dados, novas, aoAtualizar }: { dados: AnaliseParaOProfessor; novas?: number | null; aoAtualizar: () => unknown }) {
  const { analise } = dados;
  const velha = !!novas && novas > 0;
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-analise-geral">
      <TituloDeSecao
        acao={
          <span className="flex flex-wrap items-center gap-2">
            {analise && <BotaoLink tamanho="mini" href={`/admin/alunos/relatorio/?aluno=${dados.aluno_id}`} outraAba>Página para imprimir</BotaoLink>}
            <Botao tamanho="mini" onClick={() => aoAtualizar()}>Atualizar</Botao>
          </span>
        }
      >
        <span id="titulo-analise-geral">Análise geral</span>
      </TituloDeSecao>
      {!analise ? (
        <Aviso tom="info" titulo="Ainda sem análise geral">
          <p>
            É a leitura de tudo o que o aluno respondeu, em aulas e simulados, escrita pelo Claude para você. O aluno não vê. Peça ao Claude: <Pedido frase={`Faça a análise geral de ${dados.aluno}`} />. Ele mostra o texto na conversa e, com o seu ok, grava aqui.
          </p>
          <p className="mt-2">
            <CopiarPedido frase={`Faça a análise geral de ${dados.aluno}`} rotulo="Copiar o pedido" />
          </p>
        </Aviso>
      ) : (
        <>
          <Cartao como="div" className="overflow-hidden">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-borda px-5 py-3">
              <Etiqueta tom={velha ? "atencao" : "sucesso"}>{velha ? `${plural(novas ?? 0, "resposta nova", "respostas novas")} desde então` : "Em dia com as respostas"}</Etiqueta>
              <span className="text-[13px] text-suave">
                Escrita em {emBrasilia(analise.escrita_em)}
                {dados.escrita_por ? `, no Claude de ${dados.escrita_por}` : ""}. Só você vê.
              </span>
              <span className="ml-auto">
                <CopiarPedido frase={`Refaça a análise geral de ${dados.aluno}`} rotulo="Copiar o pedido para refazer" />
              </span>
            </div>
            <Blocos analise={analise} titulos={DA_GERAL} />
          </Cartao>
          <Anteriores anteriores={dados.anteriores} titulos={DA_GERAL} />
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
