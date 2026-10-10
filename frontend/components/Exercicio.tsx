"use client";

import { useEffect, useState } from "react";
import { api, type Exercicio as Dados } from "@/lib/api";
import { TextoFormatado } from "@/lib/texto";
import { VideoSobDemanda } from "./Player";
import { Aviso, Botao, Carregamento, Esqueleto, Etiqueta } from "./ui";

// A questão dentro da aula: o aluno marca, confirma e vê o gabarito na hora.
// Vale a primeira resposta, e quem segura isso é o backend — antes de responder,
// gabarito e resolução nem chegam ao navegador.

export function Exercicio({ item, aoResponder }: { item: number; aoResponder?: (correta: boolean) => void }) {
  const [dados, setDados] = useState<Dados | null>(null);
  const [marcada, setMarcada] = useState("");
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    let vivo = true;
    setDados(null);
    setMarcada("");
    setErro("");
    api.questaoDaAula(item).then(
      (d) => vivo && setDados(d),
      (ex) => vivo && setErro(ex instanceof Error ? ex.message : "Não foi possível abrir a questão."),
    );
    return () => {
      vivo = false;
    };
  }, [item]);

  async function confirmar() {
    if (!marcada || enviando) return;
    setEnviando(true);
    setErro("");
    try {
      const corrigida = await api.responderNaAula(item, marcada);
      setDados(corrigida);
      aoResponder?.(corrigida.correta === true);
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível registrar a resposta.");
    } finally {
      setEnviando(false);
    }
  }

  if (!dados) {
    return erro ? <Aviso tom="erro">{erro}</Aviso> : <EsqueletoDaQuestao />;
  }

  // O professor abre a prévia com o gabarito, sem ter respondido.
  const corrigida = dados.gabarito !== null;

  return (
    <section aria-label={dados.nome} className="min-w-0 rounded-cartao border border-borda bg-papel p-5 sm:p-7">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-suave">Questão</p>
        {dados.respondida && (dados.correta ? <Etiqueta tom="sucesso">Você acertou</Etiqueta> : <Etiqueta tom="erro">Você errou</Etiqueta>)}
        {corrigida && !dados.respondida && <Etiqueta tom="info">Prévia do professor</Etiqueta>}
      </div>

      <TextoFormatado texto={dados.enunciado} />

      {corrigida ? (
        <ul className="mt-5 flex flex-col gap-2">
          {Object.entries(dados.alternativas).map(([letra, texto]) => {
            const gabarito = letra === dados.gabarito;
            const errada = letra === dados.marcada && !dados.correta;
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
                  {(gabarito || letra === dados.marcada) && (
                    <p className={`mt-1 text-[13px] font-semibold ${gabarito ? "text-sucesso" : "text-erro"}`}>
                      {gabarito && letra === dados.marcada ? "Gabarito · sua resposta" : gabarito ? `Gabarito (${letra})` : `Sua resposta (${letra})`}
                    </p>
                  )}
                  {/* O comentário da que ele marcou e o da certa: é a devolutiva na hora do erro. */}
                  {(gabarito || letra === dados.marcada) && dados.comentarios?.[letra] && (
                    <div className="mt-1.5 border-t border-current/10 pt-1.5 text-tinta-2">
                      <TextoFormatado texto={dados.comentarios[letra]} compacto />
                    </div>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      ) : (
        <>
          <fieldset className="mt-5 flex flex-col gap-2.5" disabled={enviando}>
            <legend className="sr-only">Alternativas de {dados.nome}</legend>
            {Object.entries(dados.alternativas).map(([letra, texto]) => {
              const escolhida = marcada === letra;
              return (
                <label
                  key={letra}
                  className={`flex cursor-pointer items-start gap-3 rounded-cartao border px-4 py-3 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                    escolhida ? "border-acento bg-lilas" : "border-borda bg-papel hover:border-suave"
                  }`}
                >
                  <input type="radio" name={`exercicio-${dados.item_id}`} value={letra} checked={escolhida} onChange={() => setMarcada(letra)} className="sr-only" />
                  <span
                    aria-hidden="true"
                    className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border text-sm font-semibold ${
                      escolhida ? "border-acento bg-acento text-white" : "border-borda-campo text-tinta-2"
                    }`}
                  >
                    {letra}
                  </span>
                  <span className="sr-only">Alternativa {letra}:</span>
                  <TextoFormatado texto={texto} compacto className="min-w-0 flex-1 pt-0.5" />
                </label>
              );
            })}
          </fieldset>
          <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-borda pt-4">
            <Botao variante="primario" disabled={!marcada} ocupado={enviando} onClick={() => confirmar()}>
              {enviando ? "Enviando…" : "Confirmar resposta"}
            </Botao>
            <p className="text-[13px] text-suave">Só vale a primeira resposta: depois de confirmar, não dá para trocar.</p>
          </div>
        </>
      )}

      {erro && (
        <Aviso tom="erro" className="mt-4">
          {erro}
        </Aviso>
      )}

      {corrigida && (dados.resolucao_comentada || dados.resolucao) && (
        <div className="mt-6 flex flex-col gap-3 rounded-cartao bg-lilas p-4">
          <h3 className="text-sm font-semibold text-acento-forte">Resolução</h3>
          {dados.resolucao_comentada && <TextoFormatado texto={dados.resolucao_comentada} compacto />}
          {dados.resolucao && <VideoSobDemanda video={dados.resolucao} rotulo="Assistir resolução" />}
        </div>
      )}
    </section>
  );
}

/** A questão chegando: o enunciado e as alternativas. */
function EsqueletoDaQuestao() {
  return (
    <Carregamento rotulo="Carregando a questão" className="flex flex-col gap-5 rounded-cartao border border-borda bg-papel p-5">
      <div className="flex flex-col gap-2.5">
        <Esqueleto className="h-4 w-full" />
        <Esqueleto className="h-4 w-11/12" />
        <Esqueleto className="h-4 w-3/5" />
      </div>
      <div className="flex flex-col gap-2.5">
        {["w-2/5", "w-1/2", "w-1/3", "w-3/5"].map((largura, i) => (
          <div key={i} className="flex items-center gap-3 rounded-campo border border-borda/70 px-3.5 py-3">
            <Esqueleto className="size-6 shrink-0 rounded-full" />
            <Esqueleto className={`h-4 ${largura}`} />
          </div>
        ))}
      </div>
    </Carregamento>
  );
}
