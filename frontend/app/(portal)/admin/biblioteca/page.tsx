"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { categoriasDe } from "@/components/Categoria";
import { comoATurmaVe, encaixar, type Executar } from "@/components/curso/comum";
import { CopiarEntreTurmas } from "@/components/curso/CopiarEntreTurmas";
import { EditorDoModulo } from "@/components/curso/EditorDoModulo";
import { Indice } from "@/components/curso/Indice";
import { NovoModulo } from "@/components/curso/NovoModulo";
import { useRecado } from "@/components/Recado";
import { Botao, Estado, Pagina, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Modulo } from "@/lib/api";

export default function PaginaDoCurso() {
  return (
    <Suspense>
      <Curso />
    </Suspense>
  );
}

const ROTA = "/admin/biblioteca/";

/**
 * Montar o curso: à esquerda o índice dos módulos, agrupados pela categoria (a mesma do menu do
 * aluno); à direita o módulo aberto, com os sub-módulos e o que há em cada um. O que está aberto
 * mora no endereço (`?modulo=`), para o voltar do navegador e o link de volta do editor de questão.
 */
function Curso() {
  const router = useRouter();
  const parametros = useSearchParams();
  const filtro = parametros.get("turma") ?? "";
  const aberto = parametros.get("modulo");
  const dados = useDados(async () => {
    const [modulos, turmas, assuntos, aulas] = await Promise.all([api.bibliotecaArvore(), api.turmas(), api.assuntos(), api.aulasDoProfessor()]);
    return { modulos, turmas, assuntos, aulas };
  });
  const [copiar, setCopiar] = useState(false);
  // Sem módulo no endereço, abre-se o primeiro. Se a ordem mudar, quem estava aberto continua aberto.
  const [ancora, setAncora] = useState<number | null>(null);
  const [dialogo, confirmar] = useConfirmar();
  const [recado, recados] = useRecado();

  const endereco = (mudancas: { modulo?: number | "novo" | null; turma?: string | null }) => {
    const p = new URLSearchParams(parametros);
    for (const [chave, valor] of Object.entries(mudancas)) {
      if (valor === null || valor === "") p.delete(chave);
      else p.set(chave, String(valor));
    }
    const q = p.toString();
    return q ? `${ROTA}?${q}` : ROTA;
  };
  const ir = (mudancas: Parameters<typeof endereco>[0]) => router.replace(endereco(mudancas), { scroll: false });

  const executar: Executar = async (acao, mensagem) => {
    recados.limpar();
    try {
      await acao();
      if (mensagem) recados.sucesso(typeof mensagem === "function" ? mensagem() : mensagem);
      await dados.recarregar();
      return true;
    } catch (e) {
      recados.erro((e as Error).message);
      return false;
    }
  };

  /** A ordem nova aparece na hora; se o servidor recusar, a árvore relida desfaz. */
  const reordenar = async (naTela: (modulos: Modulo[]) => Modulo[], noServidor: () => Promise<unknown>) => {
    dados.setDados((d) => (d ? { ...d, modulos: naTela(d.modulos) } : d));
    if (!(await executar(noServidor))) await dados.recarregar();
  };

  // O filtro aceita o id (vindo do link do painel) ou o nome da turma.
  const turmaDoFiltro = dados.dados?.turmas.find((t) => String(t.id) === filtro || t.nome === filtro);

  return (
    <Pagina
      titulo="Montar o curso"
      legenda="Monte cada módulo com vídeos, PDFs e questões. O que você põe aqui já entra publicado; o que chega pelo Claude passa antes por Rascunhos."
      acoes={<Botao onClick={() => setCopiar(!copiar)} aria-expanded={copiar}>Copiar entre turmas</Botao>}
    >
      {dialogo}
      {recado}
      {copiar && dados.dados && <CopiarEntreTurmas turmas={dados.dados.turmas} executar={executar} aoFechar={() => setCopiar(false)} />}
      <Estado {...dados} linhas={4}>
        {({ modulos: todos, assuntos, aulas, turmas }) => {
          const modulos = turmaDoFiltro ? comoATurmaVe(todos, turmaDoFiltro.nome) : todos;
          // Grupos pela categoria, na ordem em que aparecem; sem categoria vai para o fim.
          const mapa = new Map<string, Modulo[]>();
          for (const m of modulos) {
            const chave = m.categoria?.trim() || "";
            mapa.set(chave, [...(mapa.get(chave) ?? []), m]);
          }
          const grupos = [...mapa.entries()].sort(([a], [b]) => (a === "" ? 1 : b === "" ? -1 : 0));
          const naOrdem = grupos.flatMap(([, doGrupo]) => doGrupo);

          const novo = aberto === "novo";
          // Sem módulo no endereço, a tela larga abre o primeiro; a estreita fica no índice.
          const modulo = novo ? undefined : (modulos.find((m) => String(m.id) === aberto) ?? modulos.find((m) => m.id === ancora) ?? naOrdem[0]);
          const doGrupo = modulo ? (grupos.find(([, lista]) => lista.includes(modulo))?.[1] ?? []) : [];
          const posicao = modulo ? doGrupo.indexOf(modulo) : -1;

          const reordenarModulos = (ids: number[]) => {
            if (modulo) setAncora(modulo.id);
            return reordenar((ms) => encaixar(ms, ids), () => api.ordemDosModulos(ids));
          };
          const noModulo = (id: number, muda: (m: Modulo) => Modulo) => (ms: Modulo[]) => ms.map((m) => (m.id === id ? muda(m) : m));

          return (
            <div className="grid gap-5 lg:grid-cols-[19rem_minmax(0,1fr)] lg:items-start">
              <aside className={`${aberto ? "hidden lg:block" : ""} lg:sticky lg:top-20 lg:-mx-2 lg:max-h-[calc(100dvh-6rem)] lg:overflow-y-auto lg:px-2 lg:pb-2`}>
                <div className="mb-4 flex flex-col gap-1.5">
                  <label htmlFor="ver-como" className="px-2 text-[13px] font-semibold text-suave">Ver como a turma</label>
                  <select id="ver-como" value={turmaDoFiltro?.nome ?? ""} onChange={(e) => ir({ turma: e.target.value || null })} className="campo py-2">
                    <option value="">Todas (a biblioteca inteira)</option>
                    {turmas.map((t) => (
                      <option key={t.id} value={t.nome}>{t.nome}</option>
                    ))}
                  </select>
                </div>
                {modulos.length === 0 && (
                  <p className="mb-4 px-2 text-[15px] text-suave">
                    {turmaDoFiltro ? `${turmaDoFiltro.nome} ainda não recebe nenhum módulo. Crie um, ou copie os de outra turma.` : "O curso ainda não tem módulos. Crie o primeiro."}
                  </p>
                )}
                <Indice
                  grupos={grupos}
                  escolhido={modulo?.id ?? null}
                  soNaTelaLarga={!aberto}
                  novo={novo}
                  href={(m) => endereco({ modulo: m })}
                  aoReordenar={(ids) => void reordenarModulos(ids)}
                />
              </aside>

              <div className={`${aberto ? "" : "hidden lg:block"} min-w-0`}>
                <Link href={endereco({ modulo: null })} scroll={false} className="mb-3 inline-flex items-center gap-1.5 text-sm font-medium text-suave hover:text-acento lg:hidden">
                  <ArrowLeft aria-hidden="true" className="size-4" /> Todos os módulos
                </Link>
                {novo ? (
                  <NovoModulo
                    todasAsTurmas={turmas.map((t) => t.nome)}
                    turmaInicial={turmaDoFiltro?.nome}
                    categorias={categoriasDe(todos)}
                    cancelar={endereco({ modulo: null })}
                    executar={executar}
                    aoCriar={(id) => ir({ modulo: id })}
                  />
                ) : modulo ? (
                  <EditorDoModulo
                    key={modulo.id}
                    modulo={modulo}
                    vizinhos={{ acima: posicao > 0, abaixo: posicao < doGrupo.length - 1 }}
                    aoMover={(passo) => {
                      const ids = doGrupo.map((m) => m.id);
                      [ids[posicao], ids[posicao + passo]] = [ids[posicao + passo], ids[posicao]];
                      void reordenarModulos(ids);
                    }}
                    aoReordenarItens={(sub, ids) =>
                      void reordenar(
                        noModulo(modulo.id, (m) => ({ ...m, submodulos: m.submodulos.map((s) => (s.id === sub ? { ...s, itens: encaixar(s.itens, ids) } : s)) })),
                        () => api.ordemDosItens(sub, ids),
                      )
                    }
                    aoReordenarSubmodulos={(ids) =>
                      void reordenar(
                        noModulo(modulo.id, (m) => ({ ...m, submodulos: encaixar(m.submodulos, ids) })),
                        () => api.ordemDosSubmodulos(modulo.id, ids),
                      )
                    }
                    aoRemover={() => ir({ modulo: null })}
                    categorias={categoriasDe(todos)}
                    categoriasDeAula={categoriasDe(aulas)}
                    nomeDaTurma={turmaDoFiltro?.nome ?? ""}
                    todasAsTurmas={turmas.map((t) => t.nome)}
                    assuntos={assuntos}
                    aulas={aulas}
                    executar={executar}
                    confirmar={confirmar}
                  />
                ) : (
                  <Vazio titulo="Nenhum módulo ainda">
                    Um módulo é um capítulo do curso, como &quot;K01 - Modelos atômicos&quot;. Crie o primeiro em &quot;Novo módulo&quot;.
                  </Vazio>
                )}
              </div>
            </div>
          );
        }}
      </Estado>
    </Pagina>
  );
}
