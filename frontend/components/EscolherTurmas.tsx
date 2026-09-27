"use client";

import { useState } from "react";
import { Botao } from "@/components/ui";

/**
 * As turmas de um módulo, ou de uma aula dentro dele (decisão 0011): o texto de hoje, e um clique
 * abre as caixas para marcar. {@code vazio} é o que a lista vazia quer dizer ali.
 */
export function EscolherTurmas({
  turmas,
  marcadas,
  vazio,
  aoSalvar,
}: {
  turmas: string[];
  marcadas: string[];
  vazio: string;
  aoSalvar: (turmas: string[]) => Promise<boolean>;
}) {
  const [aberto, setAberto] = useState(false);
  const [escolhidas, setEscolhidas] = useState<string[]>(marcadas);

  if (!aberto) {
    return (
      <span className="inline-flex flex-wrap items-center gap-1 text-[13px] text-suave">
        {marcadas.length ? marcadas.join(", ") : vazio}
        <Botao variante="texto" onClick={() => { setEscolhidas(marcadas); setAberto(true); }}>
          Turmas
        </Botao>
      </span>
    );
  }
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        if (await aoSalvar(escolhidas)) setAberto(false);
      }}
    >
      <fieldset className="flex flex-wrap gap-1.5">
        <legend className="sr-only">Turmas</legend>
        {turmas.map((nome) => {
          const marcada = escolhidas.includes(nome);
          return (
            <label
              key={nome}
              className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1 text-[13px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel text-tinta"
              }`}
            >
              <input
                type="checkbox"
                className="sr-only"
                checked={marcada}
                onChange={() => setEscolhidas(marcada ? escolhidas.filter((t) => t !== nome) : [...escolhidas, nome])}
              />
              {nome}
            </label>
          );
        })}
      </fieldset>
      <Botao type="submit" tamanho="pequeno" variante="primario">Salvar</Botao>
      <Botao tamanho="pequeno" onClick={() => setAberto(false)}>Cancelar</Botao>
      <span className="basis-full text-[13px] text-suave">Nenhuma marcada: {vazio.toLowerCase()}.</span>
    </form>
  );
}
