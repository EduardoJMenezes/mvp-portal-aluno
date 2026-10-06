"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio, useConfirmar } from "@/components/ui";
import { CampoCategoria, EditarCategoria, categoriasDe } from "@/components/Categoria";
import { ColocarVideo } from "@/components/ColocarVideo";
import { EscolherTurmas } from "@/components/EscolherTurmas";
import { BuscaNoBanco } from "@/components/MontarProva";
import { EscolherPdf, PdfDaAula, PdfDaAulaAoVivo } from "@/components/Pdf";
import { abrirEmNovaAba, api, useDados, type Assunto, type Aula, type Modulo, type SubModulo, type Turma, type VideoVimeo } from "@/lib/api";
import { duracao, emBrasilia, plural } from "@/lib/formato";

export default function PaginaDasAulas() {
  return (
    <Suspense>
      <Biblioteca />
    </Suspense>
  );
}

type Executar = (acao: () => Promise<unknown>, mensagem?: ReactNode | (() => ReactNode)) => Promise<boolean>;

/** Nas rotas de edição, a turma "biblioteca" acha o módulo sem turma nenhuma (decisão 0011). */
const BIBLIOTECA = "biblioteca";

/** O que a turma recebe: os módulos dela e as aulas visíveis — a mesma regra do backend. */
function comoATurmaVe(modulos: Modulo[], turma: string): Modulo[] {
  return modulos
    .map((m) => {
      const recebe = (m.turmas ?? []).includes(turma);
      return {
        ...m,
        submodulos: m.submodulos.map((s) => ({
          ...s,
          itens: s.itens.filter((i) => (i.turmas?.length ? i.turmas.includes(turma) : recebe)),
        })),
        recebe,
      };
    })
    .filter((m) => m.recebe || m.submodulos.some((s) => s.itens.length > 0));
}

/**
 * A tela "Aulas": todos os módulos da biblioteca, agrupados pela categoria (a mesma do menu do
 * aluno). Cada módulo diz que turmas o recebem; "Ver como a turma" mostra só o que ela vê.
 */
function Biblioteca() {
  const parametro = useSearchParams().get("turma");
  const dados = useDados(async () => {
    const [modulos, turmas, assuntos, aulas] = await Promise.all([api.bibliotecaArvore(), api.turmas(), api.assuntos(), api.aulasDoProfessor()]);
    return { modulos, turmas, assuntos, aulas };
  });
  const [filtro, setFiltro] = useState(parametro ?? "");
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState<ReactNode>(null);
  const [novoModulo, setNovoModulo] = useState(false);
  const [copiar, setCopiar] = useState(false);
  const [dialogo, confirmar] = useConfirmar();

  const executar: Executar = async (acao, mensagem) => {
    setErro("");
    setAviso(null);
    try {
      await acao();
      if (mensagem) setAviso(typeof mensagem === "function" ? mensagem() : mensagem);
      await dados.recarregar();
      return true;
    } catch (e) {
      setErro((e as Error).message);
      return false;
    }
  };

  // O filtro aceita o id (vindo do link do painel) ou o nome da turma.
  const turmaDoFiltro = dados.dados?.turmas.find((t) => String(t.id) === filtro || t.nome === filtro);

  return (
    <Pagina
      titulo="Aulas"
      legenda="Todos os módulos, agrupados pela categoria. Cada um diz que turmas o recebem: o que muda num módulo vale para todas elas. Cada linha de um sub-módulo é um vídeo, um PDF ou uma questão, e o que você monta aqui já entra publicado; o que chega pelo Claude passa por Rascunhos."
      acoes={
        <>
          <Botao onClick={() => setCopiar(!copiar)} aria-expanded={copiar}>Copiar entre turmas</Botao>
          <Botao variante="primario" onClick={() => setNovoModulo(true)}>Novo módulo</Botao>
        </>
      }
    >
      {dialogo}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
      {copiar && dados.dados && <CopiarEntreTurmas turmas={dados.dados.turmas} executar={executar} aoFechar={() => setCopiar(false)} />}
      {novoModulo && (
        <NovoModulo
          turma={turmaDoFiltro?.id ?? BIBLIOTECA}
          nomeDaTurma={turmaDoFiltro?.nome}
          categorias={categoriasDe(dados.dados?.modulos ?? [])}
          executar={executar}
          aoFechar={() => setNovoModulo(false)}
        />
      )}
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="ver-como" className="text-sm font-semibold text-tinta-2">Ver como a turma</label>
        <select id="ver-como" value={turmaDoFiltro?.nome ?? ""} onChange={(e) => setFiltro(e.target.value)} className="campo w-auto">
          <option value="">Todas as aulas</option>
          {dados.dados?.turmas.map((t) => (
            <option key={t.id} value={t.nome}>{t.nome}</option>
          ))}
        </select>
      </div>
      <Estado {...dados} linhas={4}>
        {({ modulos: todos, assuntos, aulas, turmas }) => {
          const modulos = turmaDoFiltro ? comoATurmaVe(todos, turmaDoFiltro.nome) : todos;
          if (modulos.length === 0) {
            return (
              <Vazio titulo={turmaDoFiltro ? `${turmaDoFiltro.nome} ainda não recebe nenhum módulo` : "A biblioteca ainda está vazia"}>
                Crie um módulo, ou dê a esta turma os módulos de outra em &quot;Copiar entre turmas&quot;.
              </Vazio>
            );
          }
          // Grupos pela categoria, na ordem em que aparecem; sem categoria vai para o fim.
          const grupos = new Map<string, Modulo[]>();
          for (const m of modulos) {
            const chave = m.categoria?.trim() || "";
            grupos.set(chave, [...(grupos.get(chave) ?? []), m]);
          }
          const ordenados = [...grupos.entries()].sort(([a], [b]) => (a === "" ? 1 : b === "" ? -1 : 0));
          return ordenados.map(([categoria, doGrupo]) => (
            <section key={categoria || "sem-categoria"} className="flex flex-col gap-3" aria-label={categoria || "Sem categoria"}>
              <TituloDeSecao>{categoria || "Sem categoria"}</TituloDeSecao>
              <ol className="flex flex-col gap-4">
                {doGrupo.map((modulo, i) => (
                  <CartaoDoModulo
                    key={modulo.id}
                    turma={BIBLIOTECA}
                    modulo={modulo}
                    vizinhos={{ acima: doGrupo[i - 1], abaixo: doGrupo[i + 1], posicao: i + 1 }}
                    assuntos={assuntos}
                    aulas={aulas}
                    categorias={categoriasDe(todos)}
                    categoriasDeAula={categoriasDe(aulas)}
                    nomeDaTurma={turmaDoFiltro?.nome ?? ""}
                    todasAsTurmas={turmas.map((t) => t.nome)}
                    executar={executar}
                    confirmar={confirmar}
                  />
                ))}
              </ol>
            </section>
          ));
        }}
      </Estado>
    </Pagina>
  );
}

/** A turma "para" passa a receber os módulos e as aulas restritas da turma "de" (decisão 0011). */
function CopiarEntreTurmas({ turmas, executar, aoFechar }: { turmas: Turma[]; executar: Executar; aoFechar: () => void }) {
  const [de, setDe] = useState("");
  const [para, setPara] = useState("");

  async function copiar() {
    const origem = turmas.find((t) => String(t.id) === de);
    const destino = turmas.find((t) => String(t.id) === para);
    if (!origem || !destino) return;
    let copia = { modulos: 0, itens: 0 };
    const ok = await executar(
      async () => {
        copia = await api.copiarModulos(destino.id, origem.id);
      },
      () => `${plural(copia.modulos, "módulo", "módulos")} e ${plural(copia.itens, "aula restrita", "aulas restritas")} de ${origem.nome} agora também são de ${destino.nome}.`,
    );
    if (ok) aoFechar();
  }

  return (
    <Cartao className="flex flex-wrap items-end gap-3 p-5">
      <Campo rotulo="De" className="min-w-48">
        {(id) => (
          <select id={id} value={de} onChange={(e) => setDe(e.target.value)} className="campo">
            <option value="">Escolha…</option>
            {turmas.map((t) => (
              <option key={t.id} value={t.id}>{t.nome}</option>
            ))}
          </select>
        )}
      </Campo>
      <Campo rotulo="Para" dica="Recebe os módulos e as aulas restritas da outra; o que ela já tem continua." className="min-w-48">
        {(id) => (
          <select id={id} value={para} onChange={(e) => setPara(e.target.value)} className="campo">
            <option value="">Escolha…</option>
            {turmas.filter((t) => String(t.id) !== de).map((t) => (
              <option key={t.id} value={t.id}>{t.nome}</option>
            ))}
          </select>
        )}
      </Campo>
      <Botao variante="primario" disabled={!de || !para} onClick={() => void copiar()}>Copiar módulos</Botao>
      <Botao onClick={aoFechar}>Fechar</Botao>
    </Cartao>
  );
}

function NovoModulo({ turma, nomeDaTurma, categorias, executar, aoFechar }: { turma: number | string; nomeDaTurma?: string; categorias: string[]; executar: Executar; aoFechar: () => void }) {
  const [nome, setNome] = useState("");
  const [subs, setSubs] = useState("Aulas, Questões da apostila");
  const [categoria, setCategoria] = useState("");

  async function criar(e: FormEvent) {
    e.preventDefault();
    const lista = subs.split(",").map((s) => s.trim()).filter(Boolean);
    if (await executar(() => api.criarModulo(turma, nome.trim(), lista.length ? lista : undefined, categoria.trim() || undefined), `Módulo "${nome.trim()}" criado.`)) aoFechar();
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={criar} className="flex flex-col gap-4">
        <h2 className="text-lg font-semibold text-tinta">Novo módulo</h2>
        <p className="-mt-2 text-[13px] text-suave">
          {nomeDaTurma ? `Nasce já para ${nomeDaTurma}. Outras turmas se acrescentam em "Turmas".` : 'Nasce só na biblioteca: escolha as turmas depois, em "Turmas".'}
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Campo rotulo="Nome" dica="Ex.: K01 - Introdução à química orgânica">
            {(id) => <input id={id} required maxLength={160} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Sub-módulos" dica="Separados por vírgula">
            {(id) => <input id={id} value={subs} onChange={(e) => setSubs(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Categoria" dica="Opcional, ex.: Extensivo. É o que o botão do menu usa para mostrar só alguns capítulos.">
            {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
          </Campo>
        </div>
        <div className="flex gap-2">
          <Botao type="submit" variante="primario" disabled={!nome.trim()}>Criar módulo</Botao>
          <Botao onClick={aoFechar}>Cancelar</Botao>
        </div>
      </form>
    </Cartao>
  );
}

type Confirmar = ReturnType<typeof useConfirmar>[1];

function CartaoDoModulo({
  turma,
  modulo,
  vizinhos,
  assuntos,
  aulas,
  categorias,
  categoriasDeAula,
  nomeDaTurma,
  todasAsTurmas,
  executar,
  confirmar,
}: {
  turma: number | string;
  modulo: Modulo;
  vizinhos: { acima?: Modulo; abaixo?: Modulo; posicao: number };
  assuntos: Assunto[];
  aulas: Aula[];
  categorias: string[];
  categoriasDeAula: string[];
  nomeDaTurma: string;
  todasAsTurmas: string[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const turmasDoModulo = modulo.turmas ?? [];
  const recebe = turmasDoModulo.includes(nomeDaTurma);
  const [renomeando, setRenomeando] = useState(false);
  const [nome, setNome] = useState(modulo.nome);
  const [novoSub, setNovoSub] = useState(false);
  const [nomeSub, setNomeSub] = useState("");
  const publicados = modulo.submodulos.reduce((n, s) => n + s.itens.filter((i) => i.status === "PUBLICADO").length, 0);

  // A ordem é da biblioteca inteira: trocar de lugar é trocar a ordem dos dois.
  const mover = (outro: Modulo, paraBaixo: boolean) =>
    executar(async () => {
      const outraOrdem = outro.ordem === modulo.ordem ? modulo.ordem + (paraBaixo ? 1 : -1) : outro.ordem;
      await api.editarModulo(turma, modulo.id, { ordem: outraOrdem });
      await api.editarModulo(turma, outro.id, { ordem: modulo.ordem });
    });

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${modulo.nome}" de todas as turmas?`,
      texto: `${turmasDoModulo.length > 1 ? `Sai de ${turmasDoModulo.join(", ")}. Para tirar só de uma turma, use "Turmas". ` : ""}${
        publicados
          ? `${plural(publicados, "vídeo publicado some", "vídeos publicados somem")} da tela dos alunos na hora.`
          : "O módulo não tem vídeo publicado; os alunos não notam."
      } Nada é apagado do banco.`,
      confirmar: "Remover módulo",
      perigo: true,
    });
    if (sim) await executar(() => api.removerModulo(turma, modulo.id), `Módulo "${modulo.nome}" removido.`);
  }

  return (
    <Cartao como="li" className="overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-borda bg-canvas/60 px-5 py-3">
        {renomeando ? (
          <form
            className="flex flex-1 flex-wrap items-center gap-2"
            onSubmit={async (e) => {
              e.preventDefault();
              if (await executar(() => api.editarModulo(turma, modulo.id, { nome: nome.trim() }))) setRenomeando(false);
            }}
          >
            <label className="sr-only" htmlFor={`nome-modulo-${modulo.id}`}>Nome do módulo</label>
            <input id={`nome-modulo-${modulo.id}`} value={nome} onChange={(e) => setNome(e.target.value)} className="campo max-w-md" autoFocus />
            <Botao type="submit" variante="primario" tamanho="pequeno">Salvar</Botao>
            <Botao tamanho="pequeno" onClick={() => setRenomeando(false)}>Cancelar</Botao>
          </form>
        ) : (
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-lilas text-sm font-semibold tabular-nums text-acento-forte">{vizinhos.posicao}</span>
            <h2 className="truncate text-lg font-semibold text-tinta">{modulo.nome}</h2>
            <EditarCategoria
              valor={modulo.categoria}
              sugestoes={categorias}
              aoSalvar={(categoria) => executar(() => api.editarModulo(turma, modulo.id, { categoria }))}
            />
            <EscolherTurmas
              turmas={todasAsTurmas}
              marcadas={turmasDoModulo}
              vazio="Nenhuma turma"
              aoSalvar={(t) => executar(() => api.turmasDoModulo(modulo.id, t), `Turmas de "${modulo.nome}" salvas.`)}
            />
          </div>
        )}
        {!renomeando && (
          <div className="flex flex-wrap items-center gap-1">
            <Botao tamanho="pequeno" disabled={!vizinhos.acima} onClick={() => vizinhos.acima && void mover(vizinhos.acima, false)} aria-label="Subir módulo">↑</Botao>
            <Botao tamanho="pequeno" disabled={!vizinhos.abaixo} onClick={() => vizinhos.abaixo && void mover(vizinhos.abaixo, true)} aria-label="Descer módulo">↓</Botao>
            <Botao variante="texto" onClick={() => setRenomeando(true)}>Renomear</Botao>
            <Botao variante="texto" onClick={() => setNovoSub(!novoSub)}>Novo sub-módulo</Botao>
            <Botao variante="texto" className="text-erro" onClick={() => void remover()}>Remover</Botao>
          </div>
        )}
      </div>

      {novoSub && (
        <form
          className="flex flex-wrap items-end gap-2 border-b border-borda px-5 py-3"
          onSubmit={async (e) => {
            e.preventDefault();
            if (await executar(() => api.criarSubmodulo(turma, modulo.id, nomeSub.trim()), `Sub-módulo "${nomeSub.trim()}" criado.`)) {
              setNovoSub(false);
              setNomeSub("");
            }
          }}
        >
          <Campo rotulo="Nome do sub-módulo" className="min-w-60 flex-1">
            {(id) => <input id={id} required value={nomeSub} onChange={(e) => setNomeSub(e.target.value)} className="campo" />}
          </Campo>
          <Botao type="submit" variante="primario" tamanho="pequeno">Criar</Botao>
        </form>
      )}

      {nomeDaTurma && !recebe && (
        <p className="border-b border-borda bg-atencao-fundo px-5 py-2 text-[13px] text-atencao">
          {nomeDaTurma} não recebe este módulo: vê só as aulas marcadas para ela.
        </p>
      )}
      {modulo.submodulos.length === 0 ? (
        <p className="px-5 py-4 text-[15px] text-suave">Sem sub-módulos.</p>
      ) : (
        <div className="divide-y divide-borda">
          {modulo.submodulos.map((sub) => (
            <SecaoDoSubmodulo
              key={sub.id}
              turma={turma}
              nomeDaTurma={nomeDaTurma}
              todasAsTurmas={todasAsTurmas}
              modulo={modulo}
              sub={sub}
              assuntos={assuntos}
              aulas={aulas.filter((a) => a.submodulo_id === sub.id).sort((a, b) => a.inicio_em.localeCompare(b.inicio_em))}
              categoriasDeAula={categoriasDeAula}
              executar={executar}
              confirmar={confirmar}
            />
          ))}
        </div>
      )}
    </Cartao>
  );
}

function SecaoDoSubmodulo({
  turma,
  nomeDaTurma,
  todasAsTurmas,
  modulo,
  sub,
  assuntos,
  aulas,
  categoriasDeAula,
  executar,
  confirmar,
}: {
  turma: number | string;
  nomeDaTurma: string;
  todasAsTurmas: string[];
  modulo: Modulo;
  sub: SubModulo;
  assuntos: Assunto[];
  aulas: Aula[];
  categoriasDeAula: string[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const [painel, setPainel] = useState<"videos" | "classificar" | "aula" | "pdf" | "questao" | null>(null);
  const [renomeando, setRenomeando] = useState<number | null>(null);
  const [nomeItem, setNomeItem] = useState("");
  const publicados = sub.itens.filter((i) => i.status === "PUBLICADO").length;
  const outros = modulo.submodulos.filter((s) => s.id !== sub.id);

  async function removerSub() {
    const sim = await confirmar({
      titulo: `Remover "${sub.nome}"?`,
      texto: publicados ? `${plural(publicados, "vídeo publicado some", "vídeos publicados somem")} da tela dos alunos na hora.` : "Nenhum vídeo publicado é afetado.",
      confirmar: "Remover sub-módulo",
      perigo: true,
    });
    if (sim) await executar(() => api.removerSubmodulo(turma, modulo.id, sub.id), `Sub-módulo "${sub.nome}" removido.`);
  }

  const trocar = (j: number, k: number) =>
    executar(async () => {
      await api.editarItem(turma, modulo.id, sub.id, sub.itens[j].id, { ordem: k + 1 });
      await api.editarItem(turma, modulo.id, sub.id, sub.itens[k].id, { ordem: j + 1 });
    });

  return (
    <section className="px-5 py-4" aria-label={sub.nome}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="font-semibold text-tinta">{sub.nome}</h3>
          <Etiqueta>{plural(sub.itens.length, "aula")}</Etiqueta>
          {publicados > 0 && <Etiqueta tom="sucesso">{plural(publicados, "publicado")}</Etiqueta>}
          {sub.itens.length - publicados > 0 && <Etiqueta tom="atencao">{plural(sub.itens.length - publicados, "em rascunho", "em rascunho")}</Etiqueta>}
        </div>
        <div className="flex flex-wrap gap-1">
          <Botao variante="texto" onClick={() => setPainel(painel === "aula" ? null : "aula")} aria-expanded={painel === "aula"}>Aula ao vivo</Botao>
          <Botao variante="texto" onClick={() => setPainel(painel === "videos" ? null : "videos")} aria-expanded={painel === "videos"}>Adicionar vídeos</Botao>
          <Botao variante="texto" onClick={() => setPainel(painel === "pdf" ? null : "pdf")} aria-expanded={painel === "pdf"}>Adicionar PDF</Botao>
          <Botao variante="texto" onClick={() => setPainel(painel === "questao" ? null : "questao")} aria-expanded={painel === "questao"}>Adicionar questão</Botao>
          <Botao variante="texto" onClick={() => setPainel(painel === "classificar" ? null : "classificar")} aria-expanded={painel === "classificar"}>Classificar</Botao>
          <Botao variante="texto" className="text-erro" onClick={() => void removerSub()}>Remover</Botao>
        </div>
      </div>

      {painel === "videos" && <AdicionarVideos turma={turma} modulo={modulo} sub={sub} executar={executar} aoFechar={() => setPainel(null)} />}
      {painel === "pdf" && (
        <div className="mt-3">
          <EscolherPdf
            abertoDeInicio
            aoEscolher={(id) => executar(() => api.pdfNoSubmodulo(sub.id, id), `PDF adicionado em ${sub.nome}. Entra publicado, com as regras das aulas do módulo.`)}
            aoFechar={() => setPainel(null)}
          />
        </div>
      )}
      {painel === "questao" && <AdicionarQuestao modulo={modulo} sub={sub} executar={executar} aoFechar={() => setPainel(null)} />}
      {painel === "classificar" && <Classificar turma={turma} modulo={modulo} sub={sub} assuntos={assuntos} executar={executar} aoFechar={() => setPainel(null)} />}
      {painel === "aula" && <NovaAulaAoVivo turmas={modulo.turmas?.length ? modulo.turmas : [nomeDaTurma]} sub={sub} categorias={categoriasDeAula} executar={executar} aoFechar={() => setPainel(null)} />}
      {aulas.length > 0 && <AulasDoSubmodulo aulas={aulas} executar={executar} />}

      {sub.itens.length > 0 && (
        <div className="mt-3 overflow-x-auto">
          <table className="tabela min-w-[40rem]">
            <thead>
              <tr>
                <th scope="col" className="w-12">#</th>
                <th scope="col">Nome</th>
                <th scope="col">Situação</th>
                <th scope="col">Turmas</th>
                <th scope="col"><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {sub.itens.map((item, j) => (
                <tr key={item.id}>
                  <td className="tabular-nums text-suave">{j + 1}</td>
                  <td>
                    {renomeando === item.id ? (
                      <form
                        className="flex items-center gap-2"
                        onSubmit={async (e) => {
                          e.preventDefault();
                          if (await executar(() => api.editarItem(turma, modulo.id, sub.id, item.id, { nome: nomeItem.trim() }))) setRenomeando(null);
                        }}
                      >
                        <label htmlFor={`nome-item-${item.id}`} className="sr-only">Nome da aula</label>
                        <input id={`nome-item-${item.id}`} value={nomeItem} onChange={(e) => setNomeItem(e.target.value)} className="campo py-1" autoFocus />
                        <Botao type="submit" tamanho="pequeno" variante="primario">Salvar</Botao>
                        <Botao tamanho="pequeno" onClick={() => setRenomeando(null)}>Cancelar</Botao>
                      </form>
                    ) : (
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{item.nome}</span>
                        {item.questao ? <Etiqueta tom="info">Questão</Etiqueta> : !item.video_id && <Etiqueta tom="info">Só PDF</Etiqueta>}
                      </span>
                    )}
                    {item.questao ? (
                      <p className="mt-1 max-w-xl text-[13px] text-suave">
                        <span className="line-clamp-2">{item.questao.resumo}</span>
                        <Link href={`/admin/questoes/editar/?id=${item.questao.questao_id}`} className="font-semibold text-acento hover:underline">Editar questão #{item.questao.questao_id}</Link>
                      </p>
                    ) : (
                      <PdfDaAula
                        material={item.material}
                        aoTrocar={(id) => executar(() => api.materialDoItem(item.id, id), `PDF de "${item.nome}" salvo.`)}
                        aoTirar={item.video_id ? () => executar(() => api.materialDoItem(item.id, null), `"${item.nome}" ficou sem PDF.`) : undefined}
                      />
                    )}
                  </td>
                  <td>{item.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicado</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}</td>
                  <td>
                    <EscolherTurmas
                      turmas={todasAsTurmas}
                      marcadas={item.turmas ?? []}
                      vazio="Todas as do módulo"
                      aoSalvar={(t) => executar(() => api.turmasDoItem(item.id, t), `Turmas de "${item.nome}" salvas.`)}
                    />
                    {nomeDaTurma && !((item.turmas?.length ? item.turmas : modulo.turmas ?? []).includes(nomeDaTurma)) && (
                      <span className="block text-[13px] text-atencao">Não aparece para {nomeDaTurma}</span>
                    )}
                  </td>
                  <td>
                    <div className="flex flex-wrap items-center justify-end gap-1">
                      <Botao tamanho="pequeno" disabled={j === 0} onClick={() => void trocar(j, j - 1)} aria-label={`Subir ${item.nome}`}>↑</Botao>
                      <Botao tamanho="pequeno" disabled={j === sub.itens.length - 1} onClick={() => void trocar(j, j + 1)} aria-label={`Descer ${item.nome}`}>↓</Botao>
                      <Botao variante="texto" onClick={() => { setRenomeando(item.id); setNomeItem(item.nome); }}>Renomear</Botao>
                      {outros.length > 0 && (
                        <select
                          aria-label={`Mover ${item.nome} para outro sub-módulo`}
                          className="campo w-auto py-1 text-sm"
                          value=""
                          onChange={(e) => {
                            const destino = e.target.value;
                            if (destino) void executar(() => api.editarItem(turma, modulo.id, sub.id, item.id, { mover_para_submodulo: destino }), `"${item.nome}" mudou de sub-módulo.`);
                          }}
                        >
                          <option value="">Mover para…</option>
                          {outros.map((o) => (
                            <option key={o.id} value={o.id}>{o.nome}</option>
                          ))}
                        </select>
                      )}
                      <Botao
                        variante="texto"
                        className="text-erro"
                        onClick={async () => {
                          const sim = await confirmar({
                            titulo: `Remover "${item.nome}"?`,
                            texto: item.status === "PUBLICADO" ? "A aula sai da tela dos alunos na hora." : "A aula ainda estava em rascunho; os alunos não notam.",
                            confirmar: "Remover aula",
                            perigo: true,
                          });
                          if (sim) await executar(() => api.removerItem(turma, modulo.id, sub.id, item.id), `"${item.nome}" removido.`);
                        }}
                      >
                        Remover
                      </Botao>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// --- aula ao vivo no sub-módulo ----------------------------------------------

/** Agenda e já publica: a sala do Zoom abre agora, e a aula aparece no capítulo para a turma. */
function NovaAulaAoVivo({ turmas, sub, categorias, executar, aoFechar }: { turmas: string[]; sub: SubModulo; categorias: string[]; executar: Executar; aoFechar: () => void }) {
  const [categoria, setCategoria] = useState("");
  const [titulo, setTitulo] = useState("");
  const [quando, setQuando] = useState("");
  const [minutos, setMinutos] = useState(90);
  const [descricao, setDescricao] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function agendar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    const ok = await executar(async () => {
      // O campo é hora local; o backend guarda em UTC. A conversão é do navegador.
      const aula = await api.agendarAula({
        titulo: titulo.trim(),
        inicio_em: new Date(quando).toISOString(),
        minutos,
        descricao: descricao.trim(),
        turmas,
        submodulo_id: sub.id,
        categoria: categoria.trim(),
      });
      await api.editarAula(aula.aula_id, { status: "PUBLICADO" });
    }, `"${titulo.trim()}" agendada em ${sub.nome}. A turma já vê no capítulo.`);
    setSalvando(false);
    if (ok) aoFechar();
  }

  return (
    <form onSubmit={agendar} className="mt-3 flex flex-col gap-3 rounded-cartao border border-borda bg-canvas p-4">
      <div className="grid gap-3 sm:grid-cols-[2fr_1fr_auto]">
        <Campo rotulo="Título">
          {(id) => <input id={id} required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} placeholder="Estequiometria — aula 1" className="campo" />}
        </Campo>
        <Campo rotulo="Começa em">
          {(id) => <input id={id} type="datetime-local" required value={quando} onChange={(e) => setQuando(e.target.value)} className="campo" />}
        </Campo>
        <Campo rotulo="Minutos">
          {(id) => <input id={id} type="number" min={5} max={480} value={minutos} onChange={(e) => setMinutos(Number(e.target.value))} className="campo w-24" />}
        </Campo>
      </div>
      <div className="grid gap-3 sm:grid-cols-[2fr_1fr]">
        <Campo rotulo="Descrição" dica="Opcional; aparece para o aluno.">
          {(id) => <input id={id} maxLength={2000} value={descricao} onChange={(e) => setDescricao(e.target.value)} className="campo" />}
        </Campo>
        <Campo rotulo="Categoria" dica="Ex.: Aula, Monitoria. Separa as lives no menu.">
          {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
        </Campo>
      </div>
      <p className="text-[13px] text-suave">
        A sala do Zoom é criada agora, para {turmas.join(", ")} (as turmas do módulo). A aula é gravada, e a gravação entra publicada aqui, em {sub.nome}.
      </p>
      <div className="flex gap-2">
        <Botao type="submit" variante="primario" disabled={salvando || !titulo.trim() || !quando}>{salvando ? "Agendando…" : "Agendar aula"}</Botao>
        <Botao onClick={aoFechar}>Cancelar</Botao>
      </div>
    </form>
  );
}

const ESTADO_DA_AULA: Record<Aula["estado"], string> = {
  RASCUNHO: "Rascunho, sem sala",
  AGENDADA: "Agendada",
  AGUARDANDO: "Sala aberta",
  ABERTA: "Ao vivo agora",
  ENCERRADA: "Encerrada",
};

function AulasDoSubmodulo({ aulas, executar }: { aulas: Aula[]; executar: Executar }) {
  const [erro, setErro] = useState("");

  const iniciar = async (aula: Aula) => {
    setErro("");
    try {
      await abrirEmNovaAba(() => api.iniciarAula(aula.aula_id));
    } catch (ex) {
      setErro((ex as Error).message);
    }
  };

  return (
    <div className="mt-3 flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-tinta-2">Aulas ao vivo</h4>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <ul className="divide-y divide-borda rounded-cartao border border-borda">
        {aulas.map((aula) => {
          const semGravacao = aula.estado === "ENCERRADA" && !aula.gravacao_item_id && aula.gravacao !== "enviando";
          return (
            <li key={aula.aula_id} className="flex flex-col gap-2 px-3 py-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <p className="truncate font-medium text-tinta">{aula.titulo}</p>
                  <p className="text-[13px] text-suave">
                    {emBrasilia(aula.inicio_em)} · {aula.minutos} min · {ESTADO_DA_AULA[aula.estado]}
                    {aula.gravacao === "enviando" && " · gravação indo para o Vimeo"}
                    {aula.gravacao_item_id ? " · gravação publicada no curso" : ""}
                    {semGravacao && " · sem gravação"}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-1">
                  {aula.tem_sala && aula.estado !== "ENCERRADA" && (
                    <Botao tamanho="pequeno" variante="primario" onClick={() => void iniciar(aula)}>Iniciar</Botao>
                  )}
                  <Link href="/admin/aulas/" className="px-1 text-sm text-acento hover:underline">Gerenciar</Link>
                </div>
              </div>
              <PdfDaAulaAoVivo aula={aula} executar={executar} />
              {aula.estado === "ENCERRADA" && (
                <ColocarVideo
                  aula={aula}
                  aoColocar={(link) => executar(() => api.colocarVideoNaAula(aula.aula_id, link), `Vídeo publicado no lugar da gravação de "${aula.titulo}".`)}
                />
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function AdicionarVideos({ turma, modulo, sub, executar, aoFechar }: { turma: number | string; modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const [busca, setBusca] = useState("");
  const [resultados, setResultados] = useState<VideoVimeo[] | null>(null);
  const [escolhidos, setEscolhidos] = useState<Record<string, VideoVimeo>>({});
  const [buscando, setBuscando] = useState(false);
  const [erro, setErro] = useState("");

  async function buscar(e: FormEvent) {
    e.preventDefault();
    setBuscando(true);
    setErro("");
    try {
      setResultados(await api.videosVimeo({ busca: busca.trim(), limite: 25 }));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setBuscando(false);
    }
  }

  async function adicionar() {
    const videos = Object.values(escolhidos).map((v) => ({ vimeo_id: v.id, titulo: v.titulo, embed_url: v.embed_url }));
    let recusados: string[] = [];
    const ok = await executar(
      async () => {
        recusados = (await api.adicionarVideos(turma, modulo.id, sub.id, videos)).erros;
      },
      () =>
        `${plural(videos.length - recusados.length, "vídeo adicionado", "vídeos adicionados")} em ${sub.nome}, já ${videos.length - recusados.length === 1 ? "publicado" : "publicados"}.` +
        (recusados.length ? ` Ficaram de fora: ${recusados.join("; ")}.` : ""),
    );
    if (ok) aoFechar();
  }

  const quantos = Object.keys(escolhidos).length;

  return (
    <div className="mt-3 flex flex-col gap-3 rounded-cartao border border-borda bg-canvas p-4">
      <form onSubmit={buscar} className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Buscar no Vimeo" className="min-w-60 flex-1">
          {(id) => <input id={id} value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Título do vídeo, ex.: Q04" className="campo" />}
        </Campo>
        <Botao type="submit" variante="secundario" disabled={buscando}>{buscando ? "Buscando…" : "Buscar"}</Botao>
      </form>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {resultados && resultados.length === 0 && <p className="text-[15px] text-suave">Nenhum vídeo encontrado.</p>}
      {resultados && resultados.length > 0 && (
        <ul className="max-h-72 divide-y divide-borda overflow-y-auto rounded-cartao border border-borda bg-papel">
          {resultados.map((v) => (
            <li key={v.id}>
              <label className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-canvas">
                <input
                  type="checkbox"
                  checked={!!escolhidos[v.id]}
                  onChange={(e) =>
                    setEscolhidos((atual) => {
                      const novo = { ...atual };
                      if (e.target.checked) novo[v.id] = v;
                      else delete novo[v.id];
                      return novo;
                    })
                  }
                  className="size-4 accent-acento"
                />
                <span className="min-w-0 flex-1 truncate text-[15px]">{v.titulo}</span>
                <span className="shrink-0 text-[13px] tabular-nums text-suave">{duracao(v.duracao_segundos)}</span>
              </label>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-2">
        <Botao variante="primario" disabled={!quantos} onClick={() => void adicionar()}>
          {quantos ? `Adicionar ${plural(quantos, "vídeo")}` : "Escolha os vídeos"}
        </Botao>
        <Botao onClick={aoFechar}>Fechar</Botao>
      </div>
    </div>
  );
}

/** Uma linha de questão: criada agora, no editor, ou tirada do acervo. Já sai publicada. */
function AdicionarQuestao({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const [doAcervo, setDoAcervo] = useState(false);
  const jaNaAula = new Set<number | undefined>(sub.itens.map((i) => i.questao?.questao_id).filter((id) => id !== undefined));
  const destino = encodeURIComponent(`${modulo.nome} › ${sub.nome}`);

  return (
    <div className="mt-3 flex flex-col gap-3 rounded-cartao border border-borda bg-canvas p-4">
      <p className="text-[13px] text-suave">
        A questão vira uma linha de {sub.nome}: o aluno responde ali, uma vez só, e vê o gabarito e a resolução na hora. Entra publicada.
      </p>
      <div className="flex flex-wrap gap-2">
        <BotaoLink variante="primario" href={`/admin/questoes/editar/?submodulo=${sub.id}&destino=${destino}`}>Criar questão nova</BotaoLink>
        <Botao variante="secundario" onClick={() => setDoAcervo(!doAcervo)} aria-expanded={doAcervo}>Escolher do banco de questões</Botao>
        <Botao onClick={aoFechar}>Fechar</Botao>
      </div>
      {doAcervo && (
        <BuscaNoBanco
          jaNaProva={jaNaAula}
          rotuloJaEsta="Na aula"
          avisarReuso={false}
          aoEscolher={(q) => void executar(() => api.questaoNoSubmodulo(sub.id, { questao_id: q.questao_id }), `Questão #${q.questao_id} adicionada em ${sub.nome}, já publicada.`)}
        />
      )}
    </div>
  );
}

function Classificar({ turma, modulo, sub, assuntos, executar, aoFechar }: { turma: number | string; modulo: Modulo; sub: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const [assunto, setAssunto] = useState("");
  const [subassunto, setSubassunto] = useState("");
  const [faixa, setFaixa] = useState("");
  const escolhido = assuntos.find((a) => String(a.id) === assunto);

  async function aplicar(e: FormEvent) {
    e.preventDefault();
    let classificados: string[] = [];
    const ok = await executar(
      async () => {
        classificados = (await api.classificar(turma, modulo.id, sub.id, { assunto, subassunto: subassunto || undefined, itens: faixa.trim() || undefined })).videos_classificados;
      },
      `Etiqueta aplicada em ${sub.nome}.`,
    );
    if (ok && classificados) aoFechar();
  }

  if (!assuntos.length) {
    return (
      <Aviso tom="atencao" className="mt-3">
        Nenhum assunto cadastrado. <Link href="/admin/assuntos/" className="font-semibold underline">Cadastre em Assuntos</Link>.
      </Aviso>
    );
  }

  return (
    <form onSubmit={aplicar} className="mt-3 grid gap-3 rounded-cartao border border-borda bg-canvas p-4 sm:grid-cols-[1fr_1fr_10rem_auto] sm:items-end">
      <Campo rotulo="Assunto">
        {(id) => (
          <select id={id} required value={assunto} onChange={(e) => { setAssunto(e.target.value); setSubassunto(""); }} className="campo">
            <option value="">Escolha…</option>
            {assuntos.map((a) => (
              <option key={a.id} value={a.id}>{a.nome}</option>
            ))}
          </select>
        )}
      </Campo>
      <Campo rotulo="Sub-assunto">
        {(id) => (
          <select id={id} value={subassunto} onChange={(e) => setSubassunto(e.target.value)} className="campo" disabled={!escolhido?.subassuntos.length}>
            <option value="">Nenhum</option>
            {escolhido?.subassuntos.map((s) => (
              <option key={s.id} value={s.id}>{s.nome}</option>
            ))}
          </select>
        )}
      </Campo>
      <Campo rotulo="Faixa" dica="Vazio = todos">
        {(id) => <input id={id} value={faixa} onChange={(e) => setFaixa(e.target.value)} placeholder="Q01-Q03" className="campo" />}
      </Campo>
      <div className="flex gap-2">
        <Botao type="submit" variante="primario" disabled={!assunto}>Aplicar</Botao>
        <Botao onClick={aoFechar}>Fechar</Botao>
      </div>
    </form>
  );
}
