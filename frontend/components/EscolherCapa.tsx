"use client";

import { ImageUp } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { CATALOGO, palpiteDeIcone } from "@/lib/icones";
import { Aviso } from "./ui";

// A capa do cartão do módulo, do jeito que o professor escolhe: deixar o portal decidir pelo nome,
// apontar um ícone do catálogo ou subir uma foto.

export type Capa =
  | { tipo: "automatico" }
  | { tipo: "icone"; icone: string }
  /** `arquivo` nulo com `previa` preenchida: a foto que o módulo já tem, sem troca. */
  | { tipo: "foto"; arquivo: Blob | null; previa: string };

/** A capa que o módulo tem hoje, para a edição começar dela. */
export function capaDe(modulo: { id: number; icone?: string | null; foto_versao?: number | null }): Capa {
  if (modulo.foto_versao) return { tipo: "foto", arquivo: null, previa: `/api/aluno/modulos/${modulo.id}/foto?v=${modulo.foto_versao}` };
  return modulo.icone ? { tipo: "icone", icone: modulo.icone } : { tipo: "automatico" };
}

/** Foto escolhida sem arquivo nem prévia ainda não é uma capa: falta escolher a imagem. */
export const capaPronta = (capa: Capa) => capa.tipo !== "foto" || capa.arquivo !== null || capa.previa !== "";

const LADO_DA_FOTO = 480;

/**
 * Recorta o quadrado do centro e reduz: o cartão mostra a foto pequena, e assim o envio pesa
 * dezenas de KB em vez de MB. JPEG, que todo navegador sabe gerar; o fundo branco entra no lugar
 * da transparência.
 */
async function prepararFoto(arquivo: File): Promise<Blob> {
  const imagem = await createImageBitmap(arquivo);
  const lado = Math.min(imagem.width, imagem.height);
  const tela = document.createElement("canvas");
  tela.width = tela.height = LADO_DA_FOTO;
  const pincel = tela.getContext("2d");
  if (!pincel) throw new Error("Este navegador não conseguiu preparar a foto.");
  pincel.fillStyle = "#ffffff";
  pincel.fillRect(0, 0, LADO_DA_FOTO, LADO_DA_FOTO);
  pincel.drawImage(imagem, (imagem.width - lado) / 2, (imagem.height - lado) / 2, lado, lado, 0, 0, LADO_DA_FOTO, LADO_DA_FOTO);
  imagem.close();
  const pronta = await new Promise<Blob | null>((resolver) => tela.toBlob(resolver, "image/jpeg", 0.88));
  if (!pronta) throw new Error("Este navegador não conseguiu preparar a foto.");
  return pronta;
}

const MODOS: { tipo: Capa["tipo"]; rotulo: string }[] = [
  { tipo: "automatico", rotulo: "Automática" },
  { tipo: "icone", rotulo: "Ícone" },
  { tipo: "foto", rotulo: "Foto" },
];

const FOCO = "has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-acento";

export function EscolherCapa({ nomeDoModulo, valor, aoMudar, semTitulo = false }: { nomeDoModulo: string; valor: Capa; aoMudar: (capa: Capa) => void; semTitulo?: boolean }) {
  const grupo = useId();
  const [erro, setErro] = useState("");
  const [preparando, setPreparando] = useState(false);
  const palpite = palpiteDeIcone(nomeDoModulo);
  const doPalpite = CATALOGO.find((c) => c.nome === palpite)!;

  // A prévia da foto recém-escolhida é um endereço temporário do navegador: quando sai de cena, é solto.
  const temporaria = useRef("");
  useEffect(() => () => URL.revokeObjectURL(temporaria.current), []);

  function trocarModo(tipo: Capa["tipo"]) {
    setErro("");
    if (tipo === valor.tipo) return;
    if (tipo === "automatico") aoMudar({ tipo });
    // O ícone começa no que o portal escolheria: quase sempre é só confirmar ou trocar por um vizinho.
    else if (tipo === "icone") aoMudar({ tipo, icone: palpite });
    else aoMudar({ tipo, arquivo: null, previa: "" });
  }

  async function escolherFoto(arquivo: File | undefined) {
    if (!arquivo) return;
    setErro("");
    setPreparando(true);
    try {
      const pronta = await prepararFoto(arquivo);
      URL.revokeObjectURL(temporaria.current);
      temporaria.current = URL.createObjectURL(pronta);
      aoMudar({ tipo: "foto", arquivo: pronta, previa: temporaria.current });
    } catch {
      setErro("Não deu para abrir esta imagem. Tente um arquivo JPG, PNG ou WEBP.");
    } finally {
      setPreparando(false);
    }
  }

  return (
    <fieldset className="flex flex-col gap-3">
      {/* Dentro de um modal, o título e a explicação já estão no cabeçalho dele. */}
      <legend className={semTitulo ? "sr-only" : "text-sm font-semibold text-tinta-2"}>Capa do cartão</legend>
      {!semTitulo && <p className="-mt-1 text-[13px] text-suave">É o que o aluno vê ao lado do nome do módulo, em &quot;Meu curso&quot;.</p>}

      <div className="inline-flex w-fit rounded-campo border border-borda bg-canvas p-1">
        {MODOS.map((modo) => {
          const ativo = valor.tipo === modo.tipo;
          return (
            <label key={modo.tipo} className={`cursor-pointer rounded-lg px-3.5 py-1.5 text-sm font-semibold transition-colors ${FOCO} ${ativo ? "bg-papel text-acento-forte shadow-botao" : "text-suave hover:text-tinta"}`}>
              <input type="radio" name={`${grupo}-modo`} checked={ativo} onChange={() => trocarModo(modo.tipo)} className="sr-only" />
              {modo.rotulo}
            </label>
          );
        })}
      </div>

      {valor.tipo === "automatico" && (
        <div className="flex items-center gap-3">
          <span aria-hidden="true" className="flex size-14 shrink-0 items-center justify-center rounded-2xl bg-gelo/60 text-acento">
            <doPalpite.Icone className="size-7" strokeWidth={1.6} />
          </span>
          <p className="text-[15px] text-suave">
            O portal escolhe o ícone pelo nome do módulo.{" "}
            {nomeDoModulo.trim() ? `Para este nome, fica o de ${doPalpite.rotulo.toLowerCase()}.` : "Escreva o nome para ver qual fica."}
          </p>
        </div>
      )}

      {valor.tipo === "icone" && (
        <>
          <div className="grid grid-cols-6 gap-2 sm:grid-cols-8 lg:grid-cols-11">
            {CATALOGO.map((c) => {
              const escolhido = valor.icone === c.nome;
              return (
                <label
                  key={c.nome}
                  title={c.rotulo}
                  className={`flex aspect-square cursor-pointer items-center justify-center rounded-xl border-2 transition-colors ${FOCO} ${
                    escolhido ? "border-acento bg-lilas text-acento-forte" : "border-transparent bg-gelo/50 text-tinta hover:border-apagado"
                  }`}
                >
                  <input type="radio" name={`${grupo}-icone`} value={c.nome} checked={escolhido} onChange={() => aoMudar({ tipo: "icone", icone: c.nome })} className="sr-only" />
                  <c.Icone aria-hidden="true" className="size-6" strokeWidth={1.7} />
                  <span className="sr-only">{c.rotulo}</span>
                </label>
              );
            })}
          </div>
          <p className="text-[13px] text-suave">Escolhido: {CATALOGO.find((c) => c.nome === valor.icone)?.rotulo ?? "nenhum"}.</p>
        </>
      )}

      {valor.tipo === "foto" && (
        <div className="flex flex-wrap items-center gap-4">
          {valor.previa ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={valor.previa} alt="Prévia da foto do módulo" width={72} height={72} className="size-[72px] shrink-0 rounded-2xl bg-gelo/60 object-cover" />
          ) : (
            <span aria-hidden="true" className="flex size-[72px] shrink-0 items-center justify-center rounded-2xl border-2 border-dashed border-apagado/70 text-apagado">
              <ImageUp className="size-7" strokeWidth={1.6} />
            </span>
          )}
          <div className="flex min-w-0 flex-col gap-1.5">
            <label className={`inline-flex w-fit cursor-pointer items-center gap-2 rounded-campo border border-borda bg-papel px-4 py-2 text-sm font-semibold text-acento hover:border-acento hover:bg-lilas ${FOCO}`}>
              <input type="file" accept="image/png,image/jpeg,image/webp" className="sr-only" disabled={preparando} onChange={(e) => void escolherFoto(e.target.files?.[0])} />
              {preparando ? "Preparando…" : valor.previa ? "Trocar a foto" : "Escolher foto"}
            </label>
            <p className="text-[13px] text-suave">JPG, PNG ou WEBP. A foto é recortada em quadrado, pelo centro.</p>
          </div>
        </div>
      )}

      {erro && <Aviso tom="erro">{erro}</Aviso>}
    </fieldset>
  );
}
