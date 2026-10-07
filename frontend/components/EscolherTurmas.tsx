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
  acao = "Turmas",
  abertoDeInicio = false,
  aoSalvar,
  aoFechar,
}: {
  turmas: string[];
  marcadas: string[];
  vazio: string;
  /** O nome do botão que abre as caixas. */
  acao?: string;
  /** Já abre nas caixas, e avisa quando fecha: é o modo de quem chamou por um menu. */
  abertoDeInicio?: boolean;
  aoSalvar: (turmas: string[]) => Promise<boolean>;
  aoFechar?: () => void;
}) {
  const [aberto, setAberto] = useState(abertoDeInicio);
  const fechar = () => {
    setAberto(false);
    aoFechar?.();
  };
  const [escolhidas, setEscolhidas] = useState<string[]>(marcadas);

  if (!aberto) {
    return (
      <span className="inline-flex flex-wrap items-center gap-1 text-[13px] text-suave">
        {marcadas.length ? marcadas.join(", ") : vazio}
        <Botao variante="texto" onClick={() => { setEscolhidas(marcadas); setAberto(true); }}>
          {acao}
        </Botao>
      </span>
    );
  }
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        if (await aoSalvar(escolhidas)) fechar();
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
      <Botao tamanho="pequeno" onClick={fechar}>Cancelar</Botao>
      <span className="basis-full text-[13px] text-suave">Nenhuma marcada: {vazio.toLowerCase()}.</span>
    </form>
  );
}
