"use client";

import { useId, useState } from "react";
import { Modal } from "@/components/Camadas";
import { Botao, Formulario } from "@/components/ui";

/**
 * As turmas de um módulo, ou de uma aula dentro dele (decisão 0011): o texto de hoje, e um clique
 * abre as caixas para marcar. {@code vazio} é o que a lista vazia quer dizer ali.
 * Com `modal`, as caixas abrem num modal com esse título, em vez de no lugar do texto.
 */
export function EscolherTurmas({
  turmas,
  marcadas,
  vazio,
  acao = "Turmas",
  abertoDeInicio = false,
  modal,
  semResumo = false,
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
  /** O título do modal, ex.: `Turmas de "K01"`. */
  modal?: string;
  /** Só o modal, sem o texto de hoje: para quem abriu por um menu e já mostra as turmas noutro lugar. */
  semResumo?: boolean;
  aoSalvar: (turmas: string[]) => Promise<boolean>;
  aoFechar?: () => void;
}) {
  const [aberto, setAberto] = useState(abertoDeInicio);
  const fechar = () => {
    setAberto(false);
    aoFechar?.();
  };
  const [escolhidas, setEscolhidas] = useState<string[]>(marcadas);
  const [salvando, setSalvando] = useState(false);
  const idDoFormulario = useId();

  const resumo = (
    <span className="inline-flex flex-wrap items-center gap-2 text-[13px] text-suave">
      {marcadas.length ? marcadas.join(", ") : vazio}
      <Botao tamanho="mini" onClick={() => { setEscolhidas(marcadas); setAberto(true); }}>
        {acao}
      </Botao>
    </span>
  );

  if (modal) {
    return (
      <>
        {!semResumo && resumo}
        <Modal
          aberto={aberto}
          aoFechar={() => !salvando && setAberto(false)}
          aoSumir={aoFechar}
          tamanho="pequeno"
          titulo={modal}
          legenda={`Nenhuma marcada: ${vazio.toLowerCase()}.`}
          rodape={
            <div className="flex flex-wrap justify-end gap-2">
              <Botao onClick={() => setAberto(false)} disabled={salvando}>Cancelar</Botao>
              <Botao type="submit" form={idDoFormulario} variante="primario" ocupado={salvando}>{salvando ? "Salvando…" : "Salvar"}</Botao>
            </div>
          }
        >
          <Formulario
            id={idDoFormulario}
            onSubmit={async (e) => {
              e.preventDefault();
              setSalvando(true);
              const salvou = await aoSalvar(escolhidas);
              setSalvando(false);
              if (salvou) setAberto(false);
            }}
          >
            {turmas.length === 0 ? (
              <p className="text-[15px] text-suave">Ainda não há turmas cadastradas.</p>
            ) : (
              <fieldset className="flex flex-col gap-1">
                <legend className="sr-only">Turmas</legend>
                {turmas.map((nome) => {
                  const marcada = escolhidas.includes(nome);
                  return (
                    <label key={nome} className={`flex cursor-pointer items-center gap-3 rounded-campo px-3 py-2.5 text-[15px] hover:bg-canvas ${marcada ? "font-semibold text-tinta" : "text-tinta-2"}`}>
                      <input type="checkbox" checked={marcada} onChange={() => setEscolhidas(marcada ? escolhidas.filter((t) => t !== nome) : [...escolhidas, nome])} className="size-4 accent-acento" />
                      {nome}
                    </label>
                  );
                })}
              </fieldset>
            )}
          </Formulario>
        </Modal>
      </>
    );
  }

  if (!aberto) return resumo;
  return (
    <Formulario
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
    </Formulario>
  );
}
