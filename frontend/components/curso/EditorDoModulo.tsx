"use client";

// O módulo aberto na tela de montar o curso: a ficha dele (capa, nome, categoria, turmas) e, embaixo,
// os sub-módulos com as linhas.

import { ArrowDown, ArrowUp, ImageIcon, Pencil, Plus, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { CapaDoModulo } from "@/components/CapaDoModulo";
import { EditarCategoria } from "@/components/Categoria";
import { EscolherCapa, capaDe, capaPronta, type Capa } from "@/components/EscolherCapa";
import { EscolherTurmas } from "@/components/EscolherTurmas";
import { Menu } from "@/components/Menu";
import { Aviso, Botao, Cartao } from "@/components/ui";
import { api, type Assunto, type Aula, type Modulo } from "@/lib/api";
import { plural } from "@/lib/formato";
import { BIBLIOTECA, composicao, type Confirmar, type Executar } from "./comum";
import { SecaoDoSubmodulo } from "./Submodulo";

export function EditorDoModulo({
  modulo,
  vizinhos,
  aoMover,
  aoReordenarItens,
  aoReordenarSubmodulos,
  aoRemover,
  categorias,
  categoriasDeAula,
  nomeDaTurma,
  todasAsTurmas,
  assuntos,
  aulas,
  executar,
  confirmar,
}: {
  modulo: Modulo;
  vizinhos: { acima: boolean; abaixo: boolean };
  aoMover: (passo: -1 | 1) => void;
  aoReordenarItens: (submodulo: number, ids: number[]) => void;
  aoReordenarSubmodulos: (ids: number[]) => void;
  aoRemover: () => void;
  categorias: string[];
  categoriasDeAula: string[];
  nomeDaTurma: string;
  todasAsTurmas: string[];
  assuntos: Assunto[];
  aulas: Aula[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const turmasDoModulo = modulo.turmas ?? [];
  const recebe = turmasDoModulo.includes(nomeDaTurma);
  const [renomeando, setRenomeando] = useState(false);
  const [nome, setNome] = useState(modulo.nome);
  const [trocandoCapa, setTrocandoCapa] = useState(false);
  const [novoSub, setNovoSub] = useState(false);
  const [nomeSub, setNomeSub] = useState("");
  const itens = modulo.submodulos.flatMap((s) => s.itens);
  const publicados = itens.filter((i) => i.status === "PUBLICADO").length;
  const rascunhos = itens.length - publicados;
  const idsDosSubs = modulo.submodulos.map((s) => s.id);

  async function renomear(e: FormEvent) {
    e.preventDefault();
    if (nome.trim() === modulo.nome) return setRenomeando(false);
    if (await executar(() => api.editarModulo(BIBLIOTECA, modulo.id, { nome: nome.trim() }))) setRenomeando(false);
  }

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${modulo.nome}" de todas as turmas?`,
      texto: `${turmasDoModulo.length > 1 ? `Sai de ${turmasDoModulo.join(", ")}. Para tirar só de uma turma, mude as turmas do módulo. ` : ""}${
        publicados ? `${plural(publicados, "item publicado some", "itens publicados somem")} da tela dos alunos na hora.` : "O módulo não tem nada publicado; os alunos não notam."
      } Nada é apagado do banco.`,
      confirmar: "Remover módulo",
      perigo: true,
    });
    if (sim && (await executar(() => api.removerModulo(BIBLIOTECA, modulo.id), `Módulo "${modulo.nome}" removido.`))) aoRemover();
  }

  async function criarSub(e: FormEvent) {
    e.preventDefault();
    if (await executar(() => api.criarSubmodulo(BIBLIOTECA, modulo.id, nomeSub.trim()), `Sub-módulo "${nomeSub.trim()}" criado.`)) {
      setNovoSub(false);
      setNomeSub("");
    }
  }

  const moverSub = (id: number, passo: -1 | 1) => {
    const de = idsDosSubs.indexOf(id);
    const nova = [...idsDosSubs];
    [nova[de], nova[de + passo]] = [nova[de + passo], nova[de]];
    aoReordenarSubmodulos(nova);
  };

  return (
    <Cartao como="article" className="overflow-hidden">
      <header className="flex items-start gap-4 px-4 py-5 sm:px-5">
        <button
          type="button"
          onClick={() => setTrocandoCapa(!trocandoCapa)}
          aria-expanded={trocandoCapa}
          aria-label={`Trocar a capa de ${modulo.nome}`}
          title="Trocar a capa"
          className="group/capa relative shrink-0 rounded-2xl"
        >
          <CapaDoModulo modulo={modulo} />
          <span aria-hidden="true" className="absolute -bottom-1 -right-1 flex size-6 items-center justify-center rounded-full border border-borda bg-papel text-suave shadow-suave transition-colors group-hover/capa:border-acento group-hover/capa:text-acento">
            <Pencil className="size-3" strokeWidth={2.4} />
          </span>
        </button>

        <div className="min-w-0 flex-1">
          {renomeando ? (
            <form onSubmit={renomear} className="flex flex-wrap items-center gap-2">
              <label className="sr-only" htmlFor={`nome-modulo-${modulo.id}`}>Nome do módulo</label>
              <input id={`nome-modulo-${modulo.id}`} autoFocus onFocus={(e) => e.target.select()} required maxLength={160} value={nome} onChange={(e) => setNome(e.target.value)} className="campo min-w-0 max-w-lg flex-1" />
              <Botao type="submit" variante="primario" tamanho="pequeno">Salvar</Botao>
              <Botao tamanho="pequeno" onClick={() => setRenomeando(false)}>Cancelar</Botao>
            </form>
          ) : (
            <h2 className="text-[22px] font-bold leading-tight tracking-[-0.015em] text-tinta sm:text-2xl">{modulo.nome}</h2>
          )}
          <p className="mt-1 text-sm text-suave">
            {composicao(itens)}
            {rascunhos > 0 && <span className="font-semibold text-atencao">, {rascunhos} em rascunho</span>}
          </p>
          <dl className="mt-3 grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-4 gap-y-1.5 text-sm">
            <dt className="font-semibold text-tinta-2">Categoria</dt>
            <dd>
              <EditarCategoria
                valor={modulo.categoria}
                sugestoes={categorias}
                acao="Alterar"
                aoSalvar={(categoria) => executar(() => api.editarModulo(BIBLIOTECA, modulo.id, { categoria }))}
              />
            </dd>
            <dt className="font-semibold text-tinta-2">Turmas</dt>
            <dd>
              <EscolherTurmas
                turmas={todasAsTurmas}
                marcadas={turmasDoModulo}
                vazio="Nenhuma: o módulo está só na biblioteca"
                acao="Alterar"
                aoSalvar={(t) => executar(() => api.turmasDoModulo(modulo.id, t), `Turmas de "${modulo.nome}" salvas.`)}
              />
            </dd>
          </dl>
        </div>

        <Menu
          rotulo={`Mais ações de ${modulo.nome}`}
          itens={[
            { rotulo: "Renomear", icone: Pencil, aoEscolher: () => { setNome(modulo.nome); setRenomeando(true); } },
            { rotulo: "Trocar a capa", icone: ImageIcon, aoEscolher: () => setTrocandoCapa(true) },
            { rotulo: "Mover para cima", icone: ArrowUp, desabilitado: !vizinhos.acima, aoEscolher: () => aoMover(-1) },
            { rotulo: "Mover para baixo", icone: ArrowDown, desabilitado: !vizinhos.abaixo, aoEscolher: () => aoMover(1) },
            "divisor",
            { rotulo: "Remover módulo", icone: Trash2, perigo: true, aoEscolher: () => void remover() },
          ]}
        />
      </header>

      {trocandoCapa && <TrocarCapa modulo={modulo} executar={executar} aoFechar={() => setTrocandoCapa(false)} />}

      {nomeDaTurma && !recebe && (
        <Aviso tom="atencao" className="mx-4 mb-4 sm:mx-5">
          {nomeDaTurma} não recebe este módulo: vê só os itens marcados para ela.
        </Aviso>
      )}

      {modulo.submodulos.map((sub, i) => (
        <SecaoDoSubmodulo
          key={sub.id}
          modulo={modulo}
          sub={sub}
          vizinhos={{ acima: i > 0, abaixo: i < modulo.submodulos.length - 1 }}
          aoMover={(passo) => moverSub(sub.id, passo)}
          aoReordenar={(ids) => aoReordenarItens(sub.id, ids)}
          nomeDaTurma={nomeDaTurma}
          todasAsTurmas={todasAsTurmas}
          assuntos={assuntos}
          aulas={aulas.filter((a) => a.submodulo_id === sub.id).sort((a, b) => a.inicio_em.localeCompare(b.inicio_em))}
          categoriasDeAula={categoriasDeAula}
          executar={executar}
          confirmar={confirmar}
        />
      ))}

      <footer className="border-t border-borda px-4 py-3.5 sm:px-5">
        {novoSub ? (
          <form onSubmit={criarSub} className="flex flex-wrap items-center gap-2">
            <label htmlFor={`novo-sub-${modulo.id}`} className="sr-only">Nome do sub-módulo</label>
            <input id={`novo-sub-${modulo.id}`} autoFocus required maxLength={120} value={nomeSub} onChange={(e) => setNomeSub(e.target.value)} placeholder="Ex.: Resumos, Listas, Revisão" className="campo min-w-0 max-w-sm flex-1" />
            <Botao type="submit" variante="primario" tamanho="pequeno" disabled={!nomeSub.trim()}>Criar sub-módulo</Botao>
            <Botao tamanho="pequeno" onClick={() => setNovoSub(false)}>Cancelar</Botao>
          </form>
        ) : (
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Botao tamanho="pequeno" onClick={() => setNovoSub(true)}>
              <Plus aria-hidden="true" className="size-4" strokeWidth={2.4} />
              Novo sub-módulo
            </Botao>
            {modulo.submodulos.length === 0 && <p className="text-[15px] text-suave">O módulo ainda não tem sub-módulos. É dentro deles que entram as aulas.</p>}
          </div>
        )}
      </footer>
    </Cartao>
  );
}

/** A capa de um módulo que já existe: começa da que ele tem e grava só o que mudou. */
function TrocarCapa({ modulo, executar, aoFechar }: { modulo: Modulo; executar: Executar; aoFechar: () => void }) {
  const [capa, setCapa] = useState<Capa>(() => capaDe(modulo));

  async function salvar() {
    // Foto sem arquivo novo é a que já estava: não há o que enviar.
    if (capa.tipo === "foto" && !capa.arquivo) return aoFechar();
    const arquivo = capa.tipo === "foto" ? capa.arquivo : null;
    const salvou = await executar(
      () => (arquivo ? api.fotoDoModulo(modulo.id, arquivo) : api.editarModulo(BIBLIOTECA, modulo.id, { icone: capa.tipo === "icone" ? capa.icone : "automatico" })),
      `Capa de "${modulo.nome}" salva.`,
    );
    if (salvou) aoFechar();
  }

  return (
    <div className="flex flex-col gap-4 border-t border-borda bg-canvas/40 px-4 py-4 sm:px-5">
      <EscolherCapa nomeDoModulo={modulo.nome} valor={capa} aoMudar={setCapa} />
      <div className="flex gap-2">
        <Botao variante="primario" tamanho="pequeno" disabled={!capaPronta(capa)} onClick={() => void salvar()}>Salvar capa</Botao>
        <Botao tamanho="pequeno" onClick={aoFechar}>Cancelar</Botao>
      </div>
    </div>
  );
}
