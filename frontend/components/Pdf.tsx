"use client";

// O PDF da aula (decisão 0013): um material que a aula ao vivo ou a linha do módulo leva junto.

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { Botao } from "@/components/ui";
import { api, type Aula, type Material, type MaterialLigado } from "@/lib/api";

/** O link que abre o PDF no leitor de materiais, com a marcação do aluno. */
export function LinkDoPdf({ material, className = "" }: { material: MaterialLigado; className?: string }) {
  return (
    <Link href={`/materiais/ler/?id=${material.material_id}`} className={`inline-flex w-fit items-center gap-1.5 text-sm font-semibold text-acento hover:underline ${className}`}>
      <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
        <path d="M14 3v5h5" />
      </svg>
      Material da aula
    </Link>
  );
}

/**
 * Escolher o PDF: um material que já existe ou um arquivo novo, que sobe na hora. O material novo
 * não precisa ser publicado para a turma: quem vê a aula abre o PDF.
 */
export function EscolherPdf({
  rotulo = "Anexar PDF",
  abertoDeInicio = false,
  moldura = true,
  aoEscolher,
  aoFechar,
}: {
  rotulo?: string;
  abertoDeInicio?: boolean;
  /** Sem moldura, para morar dentro de um painel que já tem a dele. */
  moldura?: boolean;
  aoEscolher: (materialId: number) => Promise<boolean>;
  aoFechar?: () => void;
}) {
  const [aberto, setAberto] = useState(abertoDeInicio);
  const [materiais, setMateriais] = useState<Material[] | null>(null);
  const [escolhido, setEscolhido] = useState("");
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState("");

  useEffect(() => {
    if (aberto && !materiais) api.materiaisDoProfessor().then(setMateriais, (ex: Error) => setErro(ex.message));
  }, [aberto, materiais]);

  function fechar() {
    setAberto(false);
    setEscolhido("");
    setArquivo(null);
    aoFechar?.();
  }

  async function salvar() {
    setEnviando(true);
    setErro("");
    try {
      const id = arquivo ? (await api.enviarMaterial(arquivo, "", [], [])).material_id : Number(escolhido);
      if (await aoEscolher(id)) fechar();
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  if (!aberto) {
    return <Botao variante="texto" tamanho="pequeno" className="w-fit" onClick={() => setAberto(true)}>{rotulo}</Botao>;
  }
  return (
    <div className={`flex flex-col gap-2 ${moldura ? "rounded-cartao border border-borda bg-canvas p-3" : ""}`}>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-52 flex-1 flex-col gap-1 text-sm font-semibold text-tinta-2">
          Um material que já existe
          <select value={escolhido} disabled={!!arquivo || !materiais} onChange={(e) => setEscolhido(e.target.value)} className="campo font-normal">
            <option value="">{materiais ? "Escolha…" : "Carregando…"}</option>
            {materiais?.map((m) => (
              <option key={m.material_id} value={m.material_id}>{m.titulo}</option>
            ))}
          </select>
        </label>
        <span className="pb-2.5 text-sm text-suave">ou</span>
        <label className="flex min-w-52 flex-1 flex-col gap-1 text-sm font-semibold text-tinta-2">
          Enviar um PDF novo
          <input type="file" accept="application/pdf,.pdf" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} className="text-sm font-normal" />
        </label>
      </div>
      {erro && <p role="alert" className="text-[13px] text-erro">{erro}</p>}
      <div className="flex gap-2">
        <Botao variante="primario" tamanho="pequeno" disabled={enviando || (!arquivo && !escolhido)} onClick={() => void salvar()}>
          {enviando ? (arquivo ? "Enviando…" : "Salvando…") : "Anexar"}
        </Botao>
        <Botao tamanho="pequeno" onClick={fechar}>Cancelar</Botao>
      </div>
    </div>
  );
}

/** O PDF atual, com trocar e tirar; sem PDF, o botão de anexar. `extra` fica ao lado (a chave "só no dia"). */
export function PdfDaAula({
  material,
  aoTrocar,
  aoTirar,
  extra,
}: {
  material?: MaterialLigado | null;
  aoTrocar: (materialId: number) => Promise<boolean>;
  aoTirar?: () => Promise<unknown>;
  extra?: ReactNode;
}) {
  const [trocando, setTrocando] = useState(false);
  if (!material || trocando) {
    return <EscolherPdf abertoDeInicio={trocando} aoEscolher={aoTrocar} aoFechar={() => setTrocando(false)} />;
  }
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
      <span className="text-suave">PDF:</span>
      <Link href={`/materiais/ler/?id=${material.material_id}`} className="font-semibold text-acento hover:underline">{material.titulo}</Link>
      <Botao variante="texto" tamanho="pequeno" onClick={() => setTrocando(true)}>Trocar</Botao>
      {aoTirar && <Botao variante="texto" tamanho="pequeno" className="text-erro" onClick={() => void aoTirar()}>Tirar</Botao>}
      {extra}
    </div>
  );
}

/** O PDF da aula ao vivo, com a chave "só no dia". Vai junto para a gravação. */
export function PdfDaAulaAoVivo({ aula, executar }: { aula: Aula; executar: (acao: () => Promise<unknown>) => Promise<boolean> }) {
  return (
    <PdfDaAula
      material={aula.material}
      aoTrocar={(id) => executar(() => api.materialDaAula(aula.aula_id, id))}
      aoTirar={() => executar(() => api.materialDaAula(aula.aula_id, null))}
      extra={
        aula.material && (
          <label className="flex items-center gap-2 text-[13px] text-tinta-2">
            <input
              type="checkbox"
              checked={!!aula.material_no_dia}
              onChange={(e) => void executar(() => api.materialDaAula(aula.aula_id, aula.material!.material_id, e.target.checked))}
            />
            Aluno só vê no dia da aula
          </label>
        )
      }
    />
  );
}
