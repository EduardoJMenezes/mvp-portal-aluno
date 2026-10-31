"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type FolhaDeRespostas as Dados } from "@/lib/api";
import { AVISO_DA_PREVIA, useNaPrevia } from "@/lib/sessao";
import { Aviso, Botao, Carregamento, Esqueleto, Etiqueta, Progresso } from "./ui";

// A folha de respostas: o aluno resolve a apostila no papel e lança aqui as letras, todas de uma
// vez. Até enviar, nenhuma correção chega ao navegador; depois, a folha volta corrigida e cada
// questão abre com a resolução. O rascunho das letras fica no navegador, para não se perder.

const chave = (submodulo: number) => `folha-de-respostas:${submodulo}`;

function lerRascunho(submodulo: number): Record<number, string> {
  try {
    return JSON.parse(localStorage.getItem(chave(submodulo)) ?? "{}");
  } catch {
    return {};
  }
}

function gravarRascunho(submodulo: number, marcadas: Record<number, string> | null) {
  try {
    if (marcadas) localStorage.setItem(chave(submodulo), JSON.stringify(marcadas));
    else localStorage.removeItem(chave(submodulo));
  } catch {
    // Sem armazenamento (aba anônima): a folha funciona, só não guarda o rascunho.
  }
}

export function FolhaDeRespostas({
  submodulo,
  moduloId,
  aoEnviar,
}: {
  submodulo: number;
  moduloId: number;
  aoEnviar?: (folha: Dados) => void;
}) {
  const [dados, setDados] = useState<Dados | null>(null);
  const [marcadas, setMarcadas] = useState<Record<number, string>>({});
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);
  const previa = useNaPrevia();

  useEffect(() => {
    let vivo = true;
    setDados(null);
    setErro("");
    api.folhaDeRespostas(submodulo).then(
      (d) => {
        if (!vivo) return;
        setDados(d);
        // Do rascunho, só as letras de linhas que ainda estão na folha e que a questão tem.
        const guardadas = lerRascunho(submodulo);
        const validas: Record<number, string> = {};
        for (const linha of d.linhas) {
          const letra = guardadas[linha.item_id];
          if (!linha.marcada && letra && linha.letras.includes(letra)) validas[linha.item_id] = letra;
        }
        setMarcadas(validas);
      },
      (ex) => vivo && setErro(ex instanceof Error ? ex.message : "Não foi possível abrir a folha de respostas."),
    );
    return () => {
      vivo = false;
    };
  }, [submodulo]);

  if (!dados) {
    return erro ? <Aviso tom="erro">{erro}</Aviso> : <EsqueletoDaFolha />;
  }

  const abertas = dados.linhas.filter((l) => !l.marcada);
  const faltam = abertas.filter((l) => !marcadas[l.item_id]).length;
  const corrigida = dados.enviada || dados.linhas.some((l) => l.gabarito !== null);

  function marcar(item: number, letra: string) {
    setMarcadas((atual) => {
      const nova = { ...atual, [item]: letra };
      gravarRascunho(submodulo, nova);
      return nova;
    });
  }

  async function enviar() {
    if (faltam > 0 || enviando) return;
    setEnviando(true);
    setErro("");
    try {
      const respostas: Record<number, string> = {};
      for (const linha of abertas) respostas[linha.item_id] = marcadas[linha.item_id];
      const folha = await api.enviarFolha(submodulo, respostas);
      gravarRascunho(submodulo, null);
      setDados(folha);
      aoEnviar?.(folha);
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível enviar a folha.");
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section aria-label={`Folha de respostas: ${dados.submodulo}`} className="min-w-0 rounded-cartao border border-borda bg-papel p-5 sm:p-7">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-suave">Folha de respostas</p>
        {dados.enviada && <Etiqueta tom="sucesso">Enviada</Etiqueta>}
        {corrigida && !dados.enviada && <Etiqueta tom="info">Prévia do professor</Etiqueta>}
      </div>
      <h2 className="text-xl font-semibold text-tinta">{dados.submodulo}</h2>

      {dados.enviada ? (
        <div className="mt-4 rounded-cartao bg-lilas px-4 py-3">
          <p className="text-2xl font-semibold tabular-nums text-acento-forte">
            {dados.acertos} de {dados.total}
            <span className="ml-2 text-base font-normal text-tinta-2">acertos</span>
          </p>
          <Progresso feitos={dados.acertos ?? 0} total={dados.total} rotulo="Acertos na folha" className="mt-2" />
          <p className="mt-2 text-[13px] text-tinta-2">Clique numa questão para ver a resolução.</p>
        </div>
      ) : (
        <p className="mt-2 text-[15px] text-tinta-2">
          Resolveu as questões na apostila? Marque aqui a letra que você escolheu em cada uma. A correção aparece quando você enviar a folha inteira.
        </p>
      )}

      <ol className="mt-5 grid gap-2 xl:grid-cols-2">
        {dados.linhas.map((linha) => {
          const marcada = linha.marcada ?? marcadas[linha.item_id] ?? null;
          const travada = !!linha.marcada || corrigida;
          return (
            <li key={linha.item_id} className="flex items-center gap-3 rounded-campo border border-borda px-3 py-2">
              {dados.enviada ? (
                <Link
                  href={`/curso/aula/?modulo=${moduloId}&item=${linha.item_id}`}
                  scroll={false}
                  className="w-10 shrink-0 text-sm font-semibold text-acento-forte underline underline-offset-2"
                  aria-label={`${linha.nome}: ${linha.correta ? "você acertou" : "você errou"}. Ver a resolução`}
                >
                  {linha.nome}
                </Link>
              ) : (
                <span className="w-10 shrink-0 text-sm font-semibold text-tinta">{linha.nome}</span>
              )}
              <fieldset disabled={travada || enviando} className="flex gap-1.5">
                <legend className="sr-only">Resposta de {linha.nome}</legend>
                {linha.letras.map((letra) => {
                  const escolhida = marcada === letra;
                  const gabarito = corrigida && letra === linha.gabarito;
                  const errada = dados.enviada && escolhida && !linha.correta;
                  const cor = gabarito
                    ? "border-sucesso bg-sucesso text-white"
                    : errada
                      ? "border-erro bg-erro text-white"
                      : escolhida
                        ? "border-acento bg-acento text-white"
                        : "border-borda-campo text-tinta-2 hover:border-suave";
                  return (
                    <label
                      key={letra}
                      className={`flex size-8 items-center justify-center rounded-full border text-sm font-semibold transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${travada ? "cursor-default" : "cursor-pointer"} ${cor}`}
                    >
                      <input
                        type="radio"
                        name={`folha-${linha.item_id}`}
                        value={letra}
                        checked={escolhida}
                        onChange={() => marcar(linha.item_id, letra)}
                        className="sr-only"
                      />
                      <span aria-hidden="true">{letra}</span>
                      <span className="sr-only">
                        {letra}
                        {gabarito ? ", gabarito" : ""}
                        {errada ? ", sua resposta" : ""}
                      </span>
                    </label>
                  );
                })}
              </fieldset>
              {dados.enviada && (
                <span className={`ml-auto text-sm font-bold ${linha.correta ? "text-sucesso" : "text-erro"}`} aria-hidden="true">
                  {linha.correta ? "✓" : "✗"}
                </span>
              )}
            </li>
          );
        })}
      </ol>

      {!dados.enviada && (
        <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-borda pt-4">
          <Botao variante="primario" disabled={faltam > 0 || previa || corrigida} ocupado={enviando} onClick={() => enviar()}>
            {enviando ? "Enviando…" : "Enviar folha"}
          </Botao>
          <p className="text-[13px] text-suave">
            {previa || corrigida
              ? `${AVISO_DA_PREVIA} O aluno marca as letras e envia a folha inteira de uma vez.`
              : faltam > 0
                ? `Falta${faltam > 1 ? "m" : ""} ${faltam} de ${abertas.length}. Só vale o primeiro envio: confira antes.`
                : "Tudo marcado. Só vale o primeiro envio: confira antes de enviar."}
          </p>
        </div>
      )}

      {erro && (
        <Aviso tom="erro" className="mt-4">
          {erro}
        </Aviso>
      )}
    </section>
  );
}

function EsqueletoDaFolha() {
  return (
    <Carregamento rotulo="Carregando a folha de respostas" className="flex flex-col gap-4 rounded-cartao border border-borda bg-papel p-5">
      <Esqueleto className="h-5 w-1/3" />
      <div className="grid gap-2 xl:grid-cols-2">
        {Array.from({ length: 8 }, (_, i) => (
          <div key={i} className="flex items-center gap-3 rounded-campo border border-borda/70 px-3 py-2">
            <Esqueleto className="h-4 w-8" />
            {Array.from({ length: 5 }, (_, j) => (
              <Esqueleto key={j} className="size-8 rounded-full" />
            ))}
          </div>
        ))}
      </div>
    </Carregamento>
  );
}
