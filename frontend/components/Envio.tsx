"use client";

import { useEffect, useMemo, useState } from "react";
import { Botao } from "./ui";

// Print vem de qualquer lugar — prova, PDF, site — e quase sempre está na área
// de transferência: colar (Ctrl+V) é o caminho principal; escolher e arrastar
// também valem. A ordem é a de chegada, que é a das questões.
export function EscolherPrints({ enviando, aoEnviar }: { enviando: boolean; aoEnviar: (prints: File[]) => void }) {
  const [prints, setPrints] = useState<File[]>([]);
  const [arrastando, setArrastando] = useState(false);
  const adicionar = (arquivos: Iterable<File>) =>
    setPrints((atuais) => [...atuais, ...[...arquivos].filter((f) => f.type.startsWith("image/"))]);
  const enderecos = useMemo(() => prints.map((f) => URL.createObjectURL(f)), [prints]);
  useEffect(() => () => enderecos.forEach((e) => URL.revokeObjectURL(e)), [enderecos]);

  useEffect(() => {
    const colar = (e: ClipboardEvent) => adicionar(e.clipboardData?.files ?? []);
    window.addEventListener("paste", colar);
    return () => window.removeEventListener("paste", colar);
  }, []);

  return (
    <div className="flex flex-col gap-4">
      <label
        onDragOver={(e) => {
          e.preventDefault();
          setArrastando(true);
        }}
        onDragLeave={() => setArrastando(false)}
        onDrop={(e) => {
          e.preventDefault();
          setArrastando(false);
          adicionar(e.dataTransfer.files);
        }}
        className={`flex cursor-pointer flex-col items-center gap-1 rounded-cartao border-2 border-dashed px-6 py-8 text-center transition-colors ${
          arrastando ? "border-acento bg-lilas" : "border-borda bg-canvas hover:border-suave"
        }`}
      >
        <span className="font-semibold text-tinta">Cole com Ctrl+V, arraste ou clique para escolher</span>
        <span className="text-sm text-suave">PNG, JPEG, WEBP ou GIF de até 5 MB, na ordem das questões (até 50)</span>
        <input
          type="file"
          accept="image/png,image/jpeg,image/webp,image/gif"
          multiple
          className="sr-only"
          onChange={(e) => {
            adicionar(e.target.files ?? []);
            e.target.value = "";
          }}
        />
      </label>

      {prints.length > 0 && (
        <ol className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3">
          {prints.map((arquivo, i) => (
            <li key={enderecos[i]} className="rounded-cartao border border-borda bg-papel p-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={enderecos[i]} alt={`Print ${i + 1}: ${arquivo.name}`} className="h-28 w-full rounded object-contain" />
              <div className="mt-1.5 flex items-center justify-between text-sm">
                <span className="font-semibold tabular-nums text-tinta">{i + 1}</span>
                <button type="button" className="text-erro hover:underline" onClick={() => setPrints(prints.filter((_, j) => j !== i))}>
                  remover
                </button>
              </div>
            </li>
          ))}
        </ol>
      )}

      <div>
        <Botao variante="primario" disabled={!prints.length || enviando} onClick={() => aoEnviar(prints)}>
          {enviando ? "Enviando…" : prints.length === 1 ? "Enviar 1 print" : `Enviar ${prints.length} prints`}
        </Botao>
      </div>
    </div>
  );
}

export function EscolherDocx({ enviando, aoEnviar, rotulo = "Enviar" }: { enviando: boolean; aoEnviar: (arquivo: File) => void; rotulo?: string }) {
  const [arquivo, setArquivo] = useState<File | null>(null);
  return (
    <div className="flex flex-col gap-4">
      <label className="flex cursor-pointer flex-col items-center gap-1 rounded-cartao border-2 border-dashed border-borda bg-canvas px-6 py-8 text-center hover:border-suave">
        <span className="font-semibold text-tinta">{arquivo ? arquivo.name : "Escolha o arquivo .docx do simulado"}</span>
        <span className="text-sm text-suave">{arquivo ? `${Math.ceil(arquivo.size / 1024)} KB` : "Até 25 MB"}</span>
        <input type="file" accept=".docx" className="sr-only" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
      </label>
      <div>
        <Botao variante="primario" disabled={!arquivo || enviando} onClick={() => arquivo && aoEnviar(arquivo)}>
          {enviando ? "Lendo o documento…" : rotulo}
        </Botao>
      </div>
    </div>
  );
}
