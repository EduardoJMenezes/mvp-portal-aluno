"use client";

import { Search } from "lucide-react";
import { useState } from "react";
import { BancoDeQuestoes } from "@/components/BancoDeQuestoes";
import { OffCanvas, useSaida } from "@/components/Camadas";
import { Aviso, Botao, Campo, Etiqueta } from "@/components/ui";
import type { Questao } from "@/lib/api";
import { plural } from "@/lib/formato";
import { DIFICULDADE, LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

// Uma questão da prova: do banco (pelo id) ou nova, que nasce no rascunho do simulado.
export type EntradaDaProva =
  | { chave: string; questao_id: number; enunciado: string; gabarito?: string | null }
  | { chave: string; questao_id?: undefined; enunciado: string; alternativas: Record<string, string>; gabarito: string; dificuldade: string };

export const paraApi = (entradas: EntradaDaProva[]) =>
  entradas.map((e) =>
    e.questao_id !== undefined
      ? e.questao_id
      : { enunciado: e.enunciado, alternativas: e.alternativas, gabarito: e.gabarito, dificuldade: e.dificuldade },
  );

export const doBanco = (q: { questao_id: number; enunciado: string; gabarito?: string | null }): EntradaDaProva => ({
  chave: `q${q.questao_id}`,
  questao_id: q.questao_id,
  enunciado: q.enunciado,
  gabarito: q.gabarito,
});

export function MontarProva({
  entradas,
  aoMudar,
  permiteNova,
}: {
  entradas: EntradaDaProva[];
  aoMudar: (entradas: EntradaDaProva[]) => void;
  permiteNova: boolean;
}) {
  const [painel, setPainel] = useState<"banco" | "nova" | null>(null);

  const mover = (i: number, passo: -1 | 1) => {
    const nova = [...entradas];
    [nova[i], nova[i + passo]] = [nova[i + passo], nova[i]];
    aoMudar(nova);
  };

  return (
    <div className="flex flex-col gap-3">
      {entradas.length === 0 ? (
        <p className="rounded-cartao border border-dashed border-borda px-4 py-6 text-center text-[15px] text-suave">Nenhuma questão ainda. Escolha do banco ou escreva uma nova.</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {entradas.map((e, i) => (
            <li key={e.chave} className="flex items-start gap-3 rounded-cartao border border-borda bg-papel px-3 py-2.5">
              <span className="mt-0.5 w-7 shrink-0 text-right font-semibold tabular-nums text-suave">{i + 1}.</span>
              <div className="min-w-0 flex-1">
                <div className="mb-1 flex flex-wrap gap-1.5">
                  {e.questao_id !== undefined ? <Etiqueta>#{e.questao_id}</Etiqueta> : <Etiqueta tom="atencao">Nova</Etiqueta>}
                  {e.gabarito && <Etiqueta tom="sucesso">Gabarito {e.gabarito}</Etiqueta>}
                </div>
                <div className="line-clamp-2 text-[15px]">
                  <TextoFormatado texto={e.enunciado} compacto />
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-0.5">
                <BotaoIcone rotulo={`Subir questão ${i + 1}`} disabled={i === 0} onClick={() => mover(i, -1)}>↑</BotaoIcone>
                <BotaoIcone rotulo={`Descer questão ${i + 1}`} disabled={i === entradas.length - 1} onClick={() => mover(i, 1)}>↓</BotaoIcone>
                <BotaoIcone rotulo={`Tirar questão ${i + 1}`} onClick={() => aoMudar(entradas.filter((_, j) => j !== i))}>×</BotaoIcone>
              </div>
            </li>
          ))}
        </ol>
      )}

      <div className="flex flex-wrap gap-2">
        <Botao tamanho="pequeno" variante="secundario" onClick={() => setPainel("banco")}>
          <Search aria-hidden="true" className="size-4" />
          Escolher do banco
        </Botao>
        {permiteNova && (
          <Botao tamanho="pequeno" variante={painel === "nova" ? "secundario" : "neutro"} onClick={() => setPainel(painel === "nova" ? null : "nova")}>Escrever questão nova</Botao>
        )}
      </div>
      {painel === "banco" && <EscolherDoBanco entradas={entradas} aoEscolher={(q) => aoMudar([...entradas, doBanco(q)])} aoFechar={() => setPainel(null)} />}
      {painel === "nova" && (
        <QuestaoNova
          aoCriar={(q) => {
            aoMudar([...entradas, { ...q, chave: `n${Date.now()}` }]);
            setPainel(null);
          }}
        />
      )}
    </div>
  );
}

function BotaoIcone({ rotulo, children, ...resto }: { rotulo: string; children: string; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" aria-label={rotulo} title={rotulo} className="flex size-8 items-center justify-center rounded-md text-lg text-suave hover:bg-canvas hover:text-tinta disabled:opacity-30 disabled:hover:bg-transparent" {...resto}>
      {children}
    </button>
  );
}

/**
 * O banco de questões aberto ao lado: a prova continua à vista atrás, e cada "Adicionar" põe a
 * questão no fim dela sem fechar o painel.
 */
function EscolherDoBanco({ entradas, aoEscolher, aoFechar }: { entradas: EntradaDaProva[]; aoEscolher: (q: Questao) => void; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const doBancoNaProva = new Map(entradas.flatMap((e, i) => (e.questao_id !== undefined ? [[e.questao_id, i + 1] as const] : [])));
  return (
    <OffCanvas
      {...saida}
      aoFechar={saida.fechar}
      lado="direita"
      tamanho="grande"
      semMargem
      titulo="Questões do banco"
      legenda="Só as publicadas. Cada uma entra no fim da prova; a ordem você acerta depois, na lista."
      rodape={
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-tinta-2">{entradas.length ? `${plural(entradas.length, "questão", "questões")} na prova` : "A prova ainda está vazia"}</p>
          <Botao variante="primario" onClick={saida.fechar}>Pronto</Botao>
        </div>
      }
    >
      <BancoDeQuestoes
        jaEsta={new Set(doBancoNaProva.keys())}
        marcaDeJaEsta={(q) => `Na prova, questão ${doBancoNaProva.get(q.questao_id)}`}
        aoEscolher={aoEscolher}
        esconder="Esconder as que já estão na prova"
        avisarAula
      />
    </OffCanvas>
  );
}

function QuestaoNova({ aoCriar }: { aoCriar: (q: { enunciado: string; alternativas: Record<string, string>; gabarito: string; dificuldade: string }) => void }) {
  const [enunciado, setEnunciado] = useState("");
  const [alternativas, setAlternativas] = useState<Record<string, string>>({ A: "", B: "", C: "", D: "", E: "" });
  const [gabarito, setGabarito] = useState("");
  const [dificuldade, setDificuldade] = useState("MEDIA");
  const [erro, setErro] = useState("");

  function adicionar() {
    if (!enunciado.trim()) return setErro("Escreva o enunciado.");
    if (LETRAS.some((l) => l !== "E" && !alternativas[l].trim())) return setErro("Preencha de A a D; a E é opcional.");
    if (!gabarito) return setErro("Marque o gabarito.");
    if (gabarito === "E" && !alternativas.E.trim()) return setErro("O gabarito é a E, mas a alternativa E está em branco.");
    // Sem a E, a questão vai de A a D.
    aoCriar({ enunciado, alternativas: Object.fromEntries(Object.entries(alternativas).filter(([l, t]) => l !== "E" || t.trim())), gabarito, dificuldade });
  }

  return (
    <div className="flex flex-col gap-3 rounded-cartao border border-borda bg-canvas p-4">
      <p className="text-[13px] text-suave">A questão nova nasce em rascunho junto com o simulado e é aprovada com ele. Figura e vídeo de resolução entram depois, no editor da questão.</p>
      <Campo rotulo="Enunciado" dica="Markdown; fórmula entre $…$.">
        {(id) => <textarea id={id} rows={4} value={enunciado} onChange={(e) => setEnunciado(e.target.value)} className="campo" />}
      </Campo>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-semibold text-tinta-2">Alternativas (clique na letra do gabarito)</legend>
        {LETRAS.map((letra) => (
          <div key={letra} className="flex items-center gap-2">
            <label className={`flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full border text-sm font-semibold ${gabarito === letra ? "border-sucesso bg-sucesso text-white" : "border-borda-campo bg-papel text-tinta-2"}`}>
              <input type="radio" name="gabarito-nova" checked={gabarito === letra} onChange={() => setGabarito(letra)} className="sr-only" />
              <span aria-hidden="true">{letra}</span>
              <span className="sr-only">Gabarito {letra}</span>
            </label>
            <input aria-label={letra === "E" ? "Alternativa E (opcional)" : `Alternativa ${letra}`} placeholder={letra === "E" ? "Opcional" : undefined} value={alternativas[letra]} onChange={(e) => setAlternativas((a) => ({ ...a, [letra]: e.target.value }))} className="campo" />
          </div>
        ))}
      </fieldset>
      <Campo rotulo="Dificuldade" className="max-w-48">
        {(id) => (
          <select id={id} value={dificuldade} onChange={(e) => setDificuldade(e.target.value)} className="campo">
            {Object.entries(DIFICULDADE).map(([valor, rotulo]) => (
              <option key={valor} value={valor}>{rotulo}</option>
            ))}
          </select>
        )}
      </Campo>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <div>
        <Botao variante="secundario" onClick={adicionar}>Pôr na prova</Botao>
      </div>
    </div>
  );
}
