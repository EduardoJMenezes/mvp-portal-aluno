"use client";

// A categoria livre de cada feature (decisão 0009): o botão do menu leva a
// `/simulados/?categoria=Rodmelo`, e a tela mostra só aquela gaveta.

import { useSearchParams } from "next/navigation";
import { useId, useState } from "react";
import { Botao, Etiqueta } from "@/components/ui";

/** A categoria que o botão do menu pediu. Quem usa precisa estar dentro de um <Suspense>. */
export function useCategoria() {
  return useSearchParams().get("categoria")?.trim() || null;
}

/** As categorias já usadas, sem repetir, para sugerir no campo. */
export function categoriasDe(itens: { categoria?: string | null }[]) {
  return [...new Set(itens.map((i) => i.categoria?.trim()).filter((c): c is string => !!c))].sort();
}

/** Campo de texto livre com as categorias já usadas como sugestão (datalist do navegador). */
export function CampoCategoria({
  id,
  valor,
  aoMudar,
  sugestoes,
  className = "campo",
}: {
  id: string;
  valor: string;
  aoMudar: (v: string) => void;
  sugestoes: string[];
  className?: string;
}) {
  const lista = `${id}-sugestoes`;
  return (
    <>
      <input id={id} list={lista} maxLength={60} value={valor} onChange={(e) => aoMudar(e.target.value)} placeholder="Ex.: Rodmelo, Monitoria" className={className} />
      <datalist id={lista}>
        {sugestoes.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </>
  );
}

/** A etiqueta da categoria, que vira campo ao clicar em "Categoria". Vazio tira a categoria. */
export function EditarCategoria({
  valor,
  sugestoes,
  acao = "Categoria",
  aoSalvar,
}: {
  valor?: string | null;
  sugestoes: string[];
  /** O nome do botão que abre o campo. */
  acao?: string;
  aoSalvar: (categoria: string) => Promise<unknown>;
}) {
  const id = useId();
  const [editando, setEditando] = useState(false);
  const [texto, setTexto] = useState(valor ?? "");

  if (!editando) {
    return (
      <span className="inline-flex items-center gap-1">
        {valor ? <Etiqueta tom="info">{valor}</Etiqueta> : <Etiqueta>Sem categoria</Etiqueta>}
        <Botao variante="texto" onClick={() => { setTexto(valor ?? ""); setEditando(true); }}>
          {acao}
        </Botao>
      </span>
    );
  }
  return (
    <form
      className="inline-flex flex-wrap items-center gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        // Quem salva devolve false quando deu erro (e mostra o erro): aí o campo fica aberto.
        if ((await aoSalvar(texto.trim())) !== false) setEditando(false);
      }}
    >
      <label htmlFor={id} className="sr-only">Categoria</label>
      <CampoCategoria id={id} valor={texto} aoMudar={setTexto} sugestoes={sugestoes} className="campo w-48 py-1" />
      <Botao type="submit" tamanho="pequeno" variante="primario">Salvar</Botao>
      <Botao tamanho="pequeno" onClick={() => setEditando(false)}>Cancelar</Botao>
    </form>
  );
}
