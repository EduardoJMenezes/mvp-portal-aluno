"use client";

// A categoria livre de cada feature (decisão 0009): o botão do menu leva a
// `/simulados/?categoria=Rodmelo`, e a tela mostra só aquela gaveta.

import { useSearchParams } from "next/navigation";
import { useId, useState } from "react";
import { Modal } from "@/components/Camadas";
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

/**
 * A etiqueta da categoria, que vira campo ao clicar em "Categoria". Vazio tira a categoria.
 * Com `modal`, o campo abre num modal com esse título, em vez de no lugar da etiqueta.
 */
export function EditarCategoria({
  valor,
  sugestoes,
  acao = "Categoria",
  modal,
  aoSalvar,
}: {
  valor?: string | null;
  sugestoes: string[];
  /** O nome do botão que abre o campo. */
  acao?: string;
  /** O título do modal, ex.: `Categoria de "K01"`. */
  modal?: string;
  aoSalvar: (categoria: string) => Promise<unknown>;
}) {
  const id = useId();
  const [editando, setEditando] = useState(false);
  const [texto, setTexto] = useState(valor ?? "");
  const [salvando, setSalvando] = useState(false);

  const resumo = (
    <span className="inline-flex items-center gap-1">
      {valor ? <Etiqueta tom="info">{valor}</Etiqueta> : <Etiqueta>Sem categoria</Etiqueta>}
      <Botao variante="texto" onClick={() => { setTexto(valor ?? ""); setEditando(true); }}>
        {acao}
      </Botao>
    </span>
  );

  if (modal) {
    return (
      <>
        {resumo}
        <Modal
          aberto={editando}
          aoFechar={() => !salvando && setEditando(false)}
          tamanho="pequeno"
          titulo={modal}
          legenda="É como o menu do aluno separa os capítulos. Em branco, fica sem categoria."
          rodape={
            <div className="flex flex-wrap justify-end gap-2">
              <Botao onClick={() => setEditando(false)} disabled={salvando}>Cancelar</Botao>
              <Botao type="submit" form={`${id}-form`} variante="primario" disabled={salvando}>{salvando ? "Salvando…" : "Salvar"}</Botao>
            </div>
          }
        >
          <form
            id={`${id}-form`}
            data-foco-inicial
            onSubmit={async (e) => {
              e.preventDefault();
              setSalvando(true);
              // Quem salva devolve false quando deu erro (e mostra o erro): aí o modal fica aberto.
              const salvou = (await aoSalvar(texto.trim())) !== false;
              setSalvando(false);
              if (salvou) setEditando(false);
            }}
          >
            <label htmlFor={id} className="mb-1.5 block text-sm font-semibold text-tinta-2">Categoria</label>
            <CampoCategoria id={id} valor={texto} aoMudar={setTexto} sugestoes={sugestoes} />
          </form>
        </Modal>
      </>
    );
  }

  if (!editando) return resumo;
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
