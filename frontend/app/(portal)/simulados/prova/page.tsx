"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { GradeQuestoes } from "@/components/GradeQuestoes";
import { Aviso, Botao, BotaoLink, Cartao, Carregando, Pagina, useConfirmar } from "@/components/ui";
import { api, ErroApi, type Prova } from "@/lib/api";
import { emBrasilia, plural, relogio } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDaProva() {
  return (
    <Suspense>
      <ProvaDoAluno />
    </Suspense>
  );
}

// Quem decide o estado — agendada, em andamento, entregue, encerrada — e o
// prazo é o backend; esta tela só desenha.
function ProvaDoAluno() {
  const id = Number(useSearchParams().get("id"));
  const [prova, setProva] = useState<Prova | null>(null);
  const [erro, setErro] = useState("");

  const carregar = useCallback(async () => {
    setErro("");
    try {
      setProva(await api.prova(id));
    } catch (e) {
      setErro((e as Error).message);
    }
  }, [id]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  const voltar = { href: "/simulados/", rotulo: "Simulados" };
  if (erro) {
    return (
      <Pagina titulo="Simulado" voltar={voltar} estreita>
        <Aviso tom="erro">{erro}</Aviso>
      </Pagina>
    );
  }
  if (!prova) {
    return (
      <Pagina titulo="Simulado" voltar={voltar} estreita>
        <Carregando linhas={3} />
      </Pagina>
    );
  }
  if (prova.estado === "EM_ANDAMENTO") return <EmAndamento prova={prova} aoAcabar={carregar} />;

  return (
    <Pagina titulo={prova.titulo} voltar={voltar} estreita>
      {prova.estado === "AGENDADO" && <Aviso tom="info" titulo="Ainda não abriu">A prova abre {emBrasilia(prova.abre_em)}.</Aviso>}
      {prova.estado === "ENTREGUE" && (
        <Aviso tom="sucesso" titulo="Prova entregue">
          {prova.entregue_automaticamente ? "O tempo acabou e a prova foi entregue com o que estava respondido. " : ""}
          O resultado sai {emBrasilia(prova.resultado_em)}, quando o simulado fechar.
        </Aviso>
      )}
      {prova.estado === "ENCERRADO" &&
        (prova.resultado_disponivel ? (
          <Cartao className="flex flex-wrap items-center justify-between gap-3 p-5">
            <p className="text-[15px] text-tinta">Este simulado fechou e o seu resultado já saiu.</p>
            <BotaoLink variante="primario" href={`/simulados/resultado/?id=${prova.simulado_id}`}>Ver resultado</BotaoLink>
          </Cartao>
        ) : (
          <Aviso tom="neutro">Este simulado já fechou, e você não chegou a fazer a prova.</Aviso>
        ))}
    </Pagina>
  );
}

function EmAndamento({ prova, aoAcabar }: { prova: Prova; aoAcabar: () => Promise<void> }) {
  const questoes = prova.questoes ?? [];
  const total = questoes.length;
  const [indice, setIndice] = useState(() => Math.max(0, questoes.findIndex((q) => !q.marcada)));
  const [marcadas, setMarcadas] = useState<Record<number, string>>(() =>
    Object.fromEntries(questoes.filter((q) => q.marcada).map((q) => [q.questao_id, q.marcada as string])),
  );
  const [restante, setRestante] = useState(prova.segundos_restantes ?? 0);
  const [erro, setErro] = useState("");
  const [entregando, setEntregando] = useState(false);
  const [dialogo, confirmar] = useConfirmar();
  const primeiraVez = useRef(true);

  // O relógio daqui só mostra o prazo que o backend deu. Zerou, recarrega: é
  // lá que a entrega automática acontece.
  useEffect(() => {
    const fim = Date.now() + (prova.segundos_restantes ?? 0) * 1000;
    const tique = setInterval(() => {
      const faltam = Math.max(0, Math.round((fim - Date.now()) / 1000));
      setRestante(faltam);
      if (faltam === 0) {
        clearInterval(tique);
        void aoAcabar();
      }
    }, 1000);
    return () => clearInterval(tique);
  }, [prova, aoAcabar]);

  useEffect(() => {
    if (primeiraVez.current) {
      primeiraVez.current = false;
      return;
    }
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, [indice]);

  const questao = questoes[indice];
  const respondidas = Object.keys(marcadas).length;
  const acabando = restante <= 300;

  // Cada marcação é gravada na hora: se o tempo acabar, entra o que já foi marcado.
  async function marcar(questaoId: number, letra: string) {
    const antes = marcadas[questaoId];
    if (antes === letra) return;
    setMarcadas((m) => ({ ...m, [questaoId]: letra }));
    setErro("");
    try {
      await api.responder(prova.simulado_id, questaoId, letra);
    } catch (e) {
      setMarcadas((m) => {
        const volta = { ...m };
        if (antes) volta[questaoId] = antes;
        else delete volta[questaoId];
        return volta;
      });
      setErro((e as Error).message);
      if (e instanceof ErroApi && e.status === 400) void aoAcabar();
    }
  }

  async function entregar() {
    const brancas = total - respondidas;
    const sim = await confirmar({
      titulo: "Entregar a prova?",
      texto: brancas
        ? `${plural(brancas, "questão ficou", "questões ficaram")} em branco, e em branco conta como erro. Depois de entregar, não dá para mudar.`
        : "Todas as questões estão respondidas. Depois de entregar, não dá para mudar.",
      confirmar: "Entregar prova",
    });
    if (!sim) return;
    setEntregando(true);
    try {
      await api.entregar(prova.simulado_id);
      await aoAcabar();
    } catch (e) {
      setErro((e as Error).message);
      setEntregando(false);
    }
  }

  if (!questao) {
    return (
      <Pagina titulo={prova.titulo} estreita>
        <Aviso tom="atencao">Esta prova não tem questões.</Aviso>
      </Pagina>
    );
  }

  return (
    <main className="mx-auto w-full max-w-6xl px-4 pb-24 sm:px-6">
      {dialogo}
      <div className="sticky top-14 z-20 -mx-4 border-b border-borda bg-canvas/95 px-4 py-3 backdrop-blur sm:-mx-6 sm:px-6">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <h1 className="truncate text-lg font-semibold text-tinta">{prova.titulo}</h1>
            <p className="text-sm text-suave">
              Questão {indice + 1} de {total} · {plural(respondidas, "respondida")}
            </p>
          </div>
          <div
            role="timer"
            aria-label="Tempo restante"
            className={`shrink-0 rounded-full border px-4 py-1.5 font-mono text-xl font-semibold tabular-nums ${
              acabando ? "border-erro-borda bg-erro-fundo text-erro" : "border-borda bg-papel text-tinta"
            }`}
          >
            {relogio(restante)}
          </div>
        </div>
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-borda" aria-hidden="true">
          <div className="h-full rounded-full bg-acento transition-[width]" style={{ width: `${total ? (respondidas / total) * 100 : 0}%` }} />
        </div>
      </div>
      <p aria-live="assertive" className="sr-only">
        {acabando && restante > 0 ? "Faltam menos de 5 minutos de prova." : ""}
      </p>

      <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1fr)_17rem]">
        <section aria-labelledby="rotulo-questao" className="min-w-0 rounded-cartao border border-borda bg-papel p-5 sm:p-7">
          <p id="rotulo-questao" className="mb-3 text-xs font-semibold uppercase tracking-wide text-suave">
            Questão {indice + 1}
          </p>
          <TextoFormatado texto={questao.enunciado} />

          <fieldset className="mt-6 flex flex-col gap-2.5">
            <legend className="sr-only">Alternativas da questão {indice + 1}</legend>
            {Object.entries(questao.alternativas).map(([letra, texto]) => {
              const marcada = marcadas[questao.questao_id] === letra;
              return (
                <label
                  key={letra}
                  className={`flex cursor-pointer items-start gap-3 rounded-cartao border px-4 py-3 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                    marcada ? "border-acento bg-lilas" : "border-borda bg-papel hover:border-suave"
                  }`}
                >
                  <input
                    type="radio"
                    name={`questao-${questao.questao_id}`}
                    value={letra}
                    checked={marcada}
                    onChange={() => void marcar(questao.questao_id, letra)}
                    className="sr-only"
                  />
                  <span
                    aria-hidden="true"
                    className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border text-sm font-semibold ${
                      marcada ? "border-acento bg-acento text-white" : "border-borda-campo text-tinta-2"
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

          {erro && (
            <Aviso tom="erro" className="mt-4">
              {erro}
            </Aviso>
          )}

          <div className="mt-6 flex flex-wrap items-center justify-between gap-2 border-t border-borda pt-4">
            <Botao disabled={indice === 0} onClick={() => setIndice(indice - 1)}>
              ← Anterior
            </Botao>
            {indice < total - 1 ? (
              <Botao variante="primario" onClick={() => setIndice(indice + 1)}>
                Próxima →
              </Botao>
            ) : (
              <Botao variante="primario" onClick={() => void entregar()} disabled={entregando}>
                {entregando ? "Entregando…" : "Entregar prova"}
              </Botao>
            )}
          </div>
        </section>

        <aside className="flex flex-col gap-3 lg:sticky lg:top-36 lg:self-start">
          <Cartao className="p-4">
            <h2 className="mb-3 text-sm font-semibold text-tinta">Questões</h2>
            <GradeQuestoes
              estados={questoes.map((q) => (marcadas[q.questao_id] ? "respondida" : "branco"))}
              atual={indice}
              aoEscolher={setIndice}
              rotulo="Ir para a questão"
            />
            <p className="mt-3 text-[13px] text-suave">{plural(total - respondidas, "em branco", "em branco")}</p>
          </Cartao>
          <Botao variante="secundario" onClick={() => void entregar()} disabled={entregando} className="w-full">
            {entregando ? "Entregando…" : "Entregar prova"}
          </Botao>
          <Link href="/simulados/" className="text-center text-sm text-suave hover:text-acento">
            Sair sem entregar (o tempo continua)
          </Link>
        </aside>
      </div>
    </main>
  );
}
