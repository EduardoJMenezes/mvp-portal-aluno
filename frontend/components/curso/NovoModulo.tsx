"use client";

import { Plus, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import { CampoCategoria } from "@/components/Categoria";
import { EscolherCapa, capaPronta, type Capa } from "@/components/EscolherCapa";
import { Botao, BotaoLink, Campo, Cartao } from "@/components/ui";
import { api } from "@/lib/api";
import { BIBLIOTECA, type Executar } from "./comum";

/** Os dois de sempre: o que o backend criaria sozinho. Aqui o professor vê e muda antes. */
const DE_SEMPRE = ["Aulas", "Questões da apostila"];

export function NovoModulo({
  todasAsTurmas,
  turmaInicial,
  categorias,
  cancelar,
  executar,
  aoCriar,
}: {
  todasAsTurmas: string[];
  /** A turma pela qual a tela está olhando: já vem marcada. */
  turmaInicial?: string;
  categorias: string[];
  /** Para onde o "Cancelar" leva. */
  cancelar: string;
  executar: Executar;
  aoCriar: (modulo: number) => void;
}) {
  const [nome, setNome] = useState("");
  const [categoria, setCategoria] = useState("");
  const [capa, setCapa] = useState<Capa>({ tipo: "automatico" });
  const [turmas, setTurmas] = useState<string[]>(turmaInicial ? [turmaInicial] : []);
  const [subs, setSubs] = useState<string[]>(DE_SEMPRE);
  const [novoSub, setNovoSub] = useState("");
  const [criando, setCriando] = useState(false);

  function acrescentar() {
    const limpo = novoSub.trim();
    if (limpo && !subs.some((s) => s.toLowerCase() === limpo.toLowerCase())) setSubs([...subs, limpo]);
    setNovoSub("");
  }

  async function criar(e: FormEvent) {
    e.preventDefault();
    setCriando(true);
    let criado = 0;
    const ok = await executar(async () => {
      criado = (await api.criarModulo(BIBLIOTECA, nome.trim(), subs, categoria.trim() || undefined, capa.tipo === "icone" ? capa.icone : undefined)).modulo_id;
      if (turmas.length) await api.turmasDoModulo(criado, turmas);
      // A foto precisa do módulo já criado: vai logo em seguida.
      if (capa.tipo === "foto" && capa.arquivo) await api.fotoDoModulo(criado, capa.arquivo);
    }, `Módulo "${nome.trim()}" criado${turmas.length ? ` para ${turmas.join(", ")}` : ", só na biblioteca"}.`);
    setCriando(false);
    if (ok) aoCriar(criado);
  }

  return (
    <Cartao como="article" className="p-5 sm:p-6">
      <form onSubmit={criar} className="flex flex-col gap-5">
        <div>
          <h2 className="text-[22px] font-bold leading-tight tracking-[-0.015em] text-tinta sm:text-2xl">Novo módulo</h2>
          <p className="mt-1 text-sm text-suave">Um capítulo do curso. Depois de criar, você põe os vídeos, os PDFs e as questões dentro dele.</p>
        </div>

        <div className="grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <Campo rotulo="Nome" dica="Ex.: K01 - Introdução à química orgânica">
            {(id) => <input id={id} autoFocus required maxLength={160} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Categoria" dica="Opcional. É como o menu do aluno separa os capítulos.">
            {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
          </Campo>
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-semibold text-tinta-2">Turmas que recebem</legend>
          {todasAsTurmas.length === 0 ? (
            <p className="text-[13px] text-suave">Ainda não há turmas. O módulo fica na biblioteca até você criar uma.</p>
          ) : (
            <>
              <div className="flex flex-wrap gap-2">
                {todasAsTurmas.map((t) => {
                  const marcada = turmas.includes(t);
                  return (
                    <label
                      key={t}
                      className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-sm font-medium has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                        marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda-campo/70 bg-papel text-tinta"
                      }`}
                    >
                      <input type="checkbox" className="sr-only" checked={marcada} onChange={() => setTurmas(marcada ? turmas.filter((x) => x !== t) : [...turmas, t])} />
                      {t}
                    </label>
                  );
                })}
              </div>
              <p className="text-[13px] text-suave">
                {turmas.length ? "Os alunos dessas turmas veem o módulo assim que ele tiver algo publicado." : "Nenhuma marcada: o módulo fica só na biblioteca, e nenhum aluno vê."}
              </p>
            </>
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-semibold text-tinta-2">Sub-módulos</legend>
          <p className="text-[13px] text-suave">As divisões dentro do módulo. Dá para criar, renomear e remover depois.</p>
          <ul className="flex flex-wrap gap-2">
            {subs.map((s) => (
              <li key={s} className="inline-flex items-center gap-1 rounded-full border border-borda bg-canvas py-1 pl-3 pr-1 text-sm font-medium text-tinta">
                {s}
                <button type="button" onClick={() => setSubs(subs.filter((x) => x !== s))} aria-label={`Tirar ${s}`} className="flex size-6 items-center justify-center rounded-full text-suave hover:bg-erro-fundo hover:text-erro">
                  <X aria-hidden="true" className="size-3.5" strokeWidth={2.4} />
                </button>
              </li>
            ))}
            {subs.length === 0 && <li className="text-sm text-suave">Nenhum: o módulo nasce vazio.</li>}
          </ul>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="novo-sub" className="sr-only">Outro sub-módulo</label>
            <input
              id="novo-sub"
              value={novoSub}
              maxLength={120}
              onChange={(e) => setNovoSub(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  acrescentar();
                }
              }}
              placeholder="Outro, ex.: Resumos"
              className="campo max-w-xs py-2"
            />
            <Botao tamanho="pequeno" disabled={!novoSub.trim()} onClick={acrescentar}>
              <Plus aria-hidden="true" className="size-4" strokeWidth={2.4} />
              Acrescentar
            </Botao>
          </div>
        </fieldset>

        <EscolherCapa nomeDoModulo={nome} valor={capa} aoMudar={setCapa} />

        <div className="flex flex-wrap gap-2 border-t border-borda pt-4">
          <Botao type="submit" variante="primario" disabled={criando || !nome.trim() || !capaPronta(capa)}>{criando ? "Criando…" : "Criar módulo"}</Botao>
          <BotaoLink href={cancelar}>Cancelar</BotaoLink>
        </div>
      </form>
    </Cartao>
  );
}
