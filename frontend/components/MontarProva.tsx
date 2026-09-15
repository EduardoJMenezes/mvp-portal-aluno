"use client";

import { useState } from "react";
import { Aviso, Botao, Campo, Etiqueta } from "@/components/ui";
import { api, type Questao } from "@/lib/api";
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
        <p className="rounded-cartao border border-dashed border-borda px-4 py-6 text-center text-[15px] text-suave">Nenhuma questão ainda. Busque no banco ou escreva uma nova.</p>
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
        <Botao tamanho="pequeno" variante={painel === "banco" ? "secundario" : "neutro"} onClick={() => setPainel(painel === "banco" ? null : "banco")}>Buscar no banco</Botao>
        {permiteNova && (
          <Botao tamanho="pequeno" variante={painel === "nova" ? "secundario" : "neutro"} onClick={() => setPainel(painel === "nova" ? null : "nova")}>Escrever questão nova</Botao>
        )}
      </div>
      {painel === "banco" && <BuscaNoBanco jaNaProva={new Set(entradas.map((e) => e.questao_id))} aoEscolher={(q) => aoMudar([...entradas, doBanco(q)])} />}
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

function BuscaNoBanco({ jaNaProva, aoEscolher }: { jaNaProva: Set<number | undefined>; aoEscolher: (q: Questao) => void }) {
  const [busca, setBusca] = useState("");
  const [resultados, setResultados] = useState<Questao[] | null>(null);
  const [erro, setErro] = useState("");
  const [buscando, setBuscando] = useState(false);

  async function buscar() {
    setBuscando(true);
    setErro("");
    try {
      setResultados(await api.questoes({ busca: busca.trim(), status: "PUBLICADO", limite: 20 }));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setBuscando(false);
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-cartao border border-borda bg-canvas p-4">
      <p className="text-[13px] text-suave">Só questões publicadas entram por aqui.</p>
      <div className="flex flex-wrap gap-2">
        <label htmlFor="busca-banco" className="sr-only">Trecho do enunciado</label>
        <input
          id="busca-banco"
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              void buscar();
            }
          }}
          placeholder="Trecho do enunciado (vazio lista as primeiras)"
          className="campo min-w-48 flex-1"
        />
        <Botao tamanho="pequeno" variante="secundario" disabled={buscando} onClick={() => void buscar()}>{buscando ? "Buscando…" : "Buscar"}</Botao>
      </div>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {resultados && (
        <ul className="flex max-h-80 flex-col divide-y divide-borda overflow-y-auto rounded-cartao border border-borda bg-papel">
          {resultados.length === 0 && <li className="px-3 py-3 text-[15px] text-suave">Nenhuma questão publicada com esse trecho.</li>}
          {resultados.map((q) => {
            const esta = jaNaProva.has(q.questao_id);
            return (
              <li key={q.questao_id} className="flex items-start gap-3 px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex flex-wrap gap-1.5">
                    <Etiqueta>#{q.questao_id}</Etiqueta>
                    <Etiqueta>{DIFICULDADE[q.dificuldade] ?? q.dificuldade}</Etiqueta>
                    {q.classificacao[0] && <Etiqueta tom="info">{q.classificacao[0].assunto}</Etiqueta>}
                  </div>
                  <div className="line-clamp-2 text-[15px]">
                    <TextoFormatado texto={q.enunciado} compacto />
                  </div>
                </div>
                <Botao tamanho="pequeno" disabled={esta} onClick={() => aoEscolher(q)}>{esta ? "Na prova" : "Adicionar"}</Botao>
              </li>
            );
          })}
        </ul>
      )}
    </div>
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
    if (LETRAS.some((l) => !alternativas[l].trim())) return setErro("Preencha as cinco alternativas.");
    if (!gabarito) return setErro("Marque o gabarito.");
    aoCriar({ enunciado, alternativas, gabarito, dificuldade });
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
            <input aria-label={`Alternativa ${letra}`} value={alternativas[letra]} onChange={(e) => setAlternativas((a) => ({ ...a, [letra]: e.target.value }))} className="campo" />
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
