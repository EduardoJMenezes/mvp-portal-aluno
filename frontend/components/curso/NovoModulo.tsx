"use client";

import { Plus, X } from "lucide-react";
import { useId, useState, type FormEvent } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { CampoCategoria } from "@/components/Categoria";
import { EscolherCapa, capaPronta, type Capa } from "@/components/EscolherCapa";
import { Botao, Campo } from "@/components/ui";
import { api } from "@/lib/api";
import { BIBLIOTECA, type Executar } from "./comum";

/** Os dois de sempre: o que o backend criaria sozinho. Aqui o professor vê e muda antes. */
const DE_SEMPRE = ["Aulas", "Questões da apostila"];

export function NovoModulo({
  todasAsTurmas,
  turmaInicial,
  categorias,
  aoCancelar,
  executar,
  aoCriar,
}: {
  todasAsTurmas: string[];
  /** A turma pela qual a tela está olhando: já vem marcada. */
  turmaInicial?: string;
  categorias: string[];
  /** Fechou sem criar. */
  aoCancelar: () => void;
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
  // O que acontece depois que o modal sai da tela: abrir o módulo criado, ou só voltar.
  const [criado, setCriado] = useState<number | null>(null);
  const saida = useSaida(() => (criado === null ? aoCancelar() : aoCriar(criado)));
  const idDoFormulario = useId();

  function acrescentar() {
    const limpo = novoSub.trim();
    if (limpo && !subs.some((s) => s.toLowerCase() === limpo.toLowerCase())) setSubs([...subs, limpo]);
    setNovoSub("");
  }

  async function criar(e: FormEvent) {
    e.preventDefault();
    setCriando(true);
    let novo = 0;
    const ok = await executar(async () => {
      novo = (await api.criarModulo(BIBLIOTECA, nome.trim(), subs, categoria.trim() || undefined, capa.tipo === "icone" ? capa.icone : undefined)).modulo_id;
      if (turmas.length) await api.turmasDoModulo(novo, turmas);
      // A foto precisa do módulo já criado: vai logo em seguida.
      if (capa.tipo === "foto" && capa.arquivo) await api.fotoDoModulo(novo, capa.arquivo);
    }, `Módulo "${nome.trim()}" criado${turmas.length ? ` para ${turmas.join(", ")}` : ", só na biblioteca"}.`);
    setCriando(false);
    if (ok) {
      setCriado(novo);
      saida.fechar();
    }
  }

  return (
    <Modal
      {...saida}
      aoFechar={() => !criando && saida.fechar()}
      fechaClicandoFora={false}
      tamanho="grande"
      titulo="Novo módulo"
      legenda="Um capítulo do curso. Depois de criar, você põe os vídeos, os PDFs e as questões dentro dele."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={criando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={criando || !nome.trim() || !capaPronta(capa)}>{criando ? "Criando…" : "Criar módulo"}</Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={criar} className="flex flex-col gap-5">
        <div className="grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]" data-foco-inicial>
          <Campo rotulo="Nome" dica="Ex.: K01 - Introdução à química orgânica">
            {(id) => <input id={id} required maxLength={160} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
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
      </form>
    </Modal>
  );
}
