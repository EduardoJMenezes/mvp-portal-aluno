"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, Pagina, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Assunto, type Modulo, type SubModulo, type VideoVimeo } from "@/lib/api";
import { duracao, plural } from "@/lib/formato";

export default function PaginaDoCurso() {
  return (
    <Suspense>
      <CursoDaTurma />
    </Suspense>
  );
}

type Executar = (acao: () => Promise<unknown>, mensagem?: ReactNode | (() => ReactNode)) => Promise<boolean>;

function CursoDaTurma() {
  const turmaId = Number(useSearchParams().get("turma"));
  const dados = useDados(async () => {
    const [modulos, turmas, assuntos] = await Promise.all([api.modulos(turmaId), api.turmas(), api.assuntos()]);
    return { modulos, turma: turmas.find((t) => t.id === turmaId), assuntos };
  }, [turmaId]);
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState<ReactNode>(null);
  const [novoModulo, setNovoModulo] = useState(false);
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

  return (
    <Pagina
      titulo={dados.dados?.turma ? `Curso · ${dados.dados.turma.nome}` : "Curso da turma"}
      legenda="Criar, renomear, reordenar e remover vale na hora para os alunos. Vídeo novo entra como rascunho e só aparece depois de aprovado."
      voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}
      acoes={
        <>
          <BotaoLink href={`/admin/turmas/alunos/?turma=${turmaId}`}>Alunos</BotaoLink>
          <Botao variante="primario" onClick={() => setNovoModulo(true)}>Novo módulo</Botao>
        </>
      }
    >
      {dialogo}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
      {novoModulo && <NovoModulo turma={turmaId} executar={executar} aoFechar={() => setNovoModulo(false)} />}
      <Estado {...dados} linhas={4}>
        {({ modulos, assuntos }) =>
          modulos.length === 0 ? (
            <Vazio titulo="Esta turma ainda não tem módulos">Crie o primeiro módulo ou importe uma pasta do Vimeo.</Vazio>
          ) : (
            <ol className="flex flex-col gap-4">
              {modulos.map((modulo, i) => (
                <CartaoDoModulo
                  key={modulo.id}
                  turma={turmaId}
                  modulo={modulo}
                  vizinhos={{ acima: modulos[i - 1], abaixo: modulos[i + 1], posicao: i + 1 }}
                  assuntos={assuntos}
                  executar={executar}
                  confirmar={confirmar}
                />
              ))}
            </ol>
          )
        }
      </Estado>
    </Pagina>
  );
}

function NovoModulo({ turma, executar, aoFechar }: { turma: number; executar: Executar; aoFechar: () => void }) {
  const [nome, setNome] = useState("");
  const [subs, setSubs] = useState("Aulas, Questões da apostila");

  async function criar(e: FormEvent) {
    e.preventDefault();
    const lista = subs.split(",").map((s) => s.trim()).filter(Boolean);
    if (await executar(() => api.criarModulo(turma, nome.trim(), lista.length ? lista : undefined), `Módulo "${nome.trim()}" criado.`)) aoFechar();
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={criar} className="flex flex-col gap-4">
        <h2 className="text-lg font-semibold text-tinta">Novo módulo</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Campo rotulo="Nome" dica="Ex.: K01 - Introdução à química orgânica">
            {(id) => <input id={id} required maxLength={160} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Sub-módulos" dica="Separados por vírgula">
            {(id) => <input id={id} value={subs} onChange={(e) => setSubs(e.target.value)} className="campo" />}
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
  executar,
  confirmar,
}: {
  turma: number;
  modulo: Modulo;
  vizinhos: { acima?: Modulo; abaixo?: Modulo; posicao: number };
  assuntos: Assunto[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const [renomeando, setRenomeando] = useState(false);
  const [nome, setNome] = useState(modulo.nome);
  const [novoSub, setNovoSub] = useState(false);
  const [nomeSub, setNomeSub] = useState("");
  const publicados = modulo.submodulos.reduce((n, s) => n + s.itens.filter((i) => i.status === "PUBLICADO").length, 0);

  // Trocar de lugar é regravar a posição dos dois pela ordem da tela.
  const mover = (outro: Modulo, destino: number) =>
    executar(async () => {
      await api.editarModulo(turma, modulo.id, { ordem: destino });
      await api.editarModulo(turma, outro.id, { ordem: vizinhos.posicao });
    });

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${modulo.nome}"?`,
      texto: publicados
        ? `${plural(publicados, "vídeo publicado some", "vídeos publicados somem")} da tela dos alunos na hora. Nada é apagado do banco.`
        : "O módulo não tem vídeo publicado; os alunos não notam. Nada é apagado do banco.",
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
          </div>
        )}
        {!renomeando && (
          <div className="flex flex-wrap items-center gap-1">
            <Botao tamanho="pequeno" disabled={!vizinhos.acima} onClick={() => vizinhos.acima && void mover(vizinhos.acima, vizinhos.posicao - 1)} aria-label="Subir módulo">↑</Botao>
            <Botao tamanho="pequeno" disabled={!vizinhos.abaixo} onClick={() => vizinhos.abaixo && void mover(vizinhos.abaixo, vizinhos.posicao + 1)} aria-label="Descer módulo">↓</Botao>
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

      {modulo.submodulos.length === 0 ? (
        <p className="px-5 py-4 text-[15px] text-suave">Sem sub-módulos.</p>
      ) : (
        <div className="divide-y divide-borda">
          {modulo.submodulos.map((sub) => (
            <SecaoDoSubmodulo key={sub.id} turma={turma} modulo={modulo} sub={sub} assuntos={assuntos} executar={executar} confirmar={confirmar} />
          ))}
        </div>
      )}
    </Cartao>
  );
}

function SecaoDoSubmodulo({
  turma,
  modulo,
  sub,
  assuntos,
  executar,
  confirmar,
}: {
  turma: number;
  modulo: Modulo;
  sub: SubModulo;
  assuntos: Assunto[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const [painel, setPainel] = useState<"videos" | "classificar" | null>(null);
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
          <Etiqueta>{plural(sub.itens.length, "vídeo")}</Etiqueta>
          {publicados > 0 && <Etiqueta tom="sucesso">{plural(publicados, "publicado")}</Etiqueta>}
          {sub.itens.length - publicados > 0 && <Etiqueta tom="atencao">{plural(sub.itens.length - publicados, "em rascunho", "em rascunho")}</Etiqueta>}
        </div>
        <div className="flex flex-wrap gap-1">
          <Botao variante="texto" onClick={() => setPainel(painel === "videos" ? null : "videos")} aria-expanded={painel === "videos"}>Adicionar vídeos</Botao>
          <Botao variante="texto" onClick={() => setPainel(painel === "classificar" ? null : "classificar")} aria-expanded={painel === "classificar"}>Classificar</Botao>
          <Botao variante="texto" className="text-erro" onClick={() => void removerSub()}>Remover</Botao>
        </div>
      </div>

      {painel === "videos" && <AdicionarVideos turma={turma} modulo={modulo} sub={sub} executar={executar} aoFechar={() => setPainel(null)} />}
      {painel === "classificar" && <Classificar turma={turma} modulo={modulo} sub={sub} assuntos={assuntos} executar={executar} aoFechar={() => setPainel(null)} />}

      {sub.itens.length > 0 && (
        <div className="mt-3 overflow-x-auto">
          <table className="tabela min-w-[40rem]">
            <thead>
              <tr>
                <th scope="col" className="w-12">#</th>
                <th scope="col">Nome</th>
                <th scope="col">Situação</th>
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
                        <label htmlFor={`nome-item-${item.id}`} className="sr-only">Nome do vídeo</label>
                        <input id={`nome-item-${item.id}`} value={nomeItem} onChange={(e) => setNomeItem(e.target.value)} className="campo py-1" autoFocus />
                        <Botao type="submit" tamanho="pequeno" variante="primario">Salvar</Botao>
                        <Botao tamanho="pequeno" onClick={() => setRenomeando(null)}>Cancelar</Botao>
                      </form>
                    ) : (
                      <span className="font-medium">{item.nome}</span>
                    )}
                  </td>
                  <td>{item.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicado</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}</td>
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
                            texto: item.status === "PUBLICADO" ? "O vídeo sai da tela dos alunos na hora." : "O vídeo ainda estava em rascunho; os alunos não notam.",
                            confirmar: "Remover vídeo",
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

function AdicionarVideos({ turma, modulo, sub, executar, aoFechar }: { turma: number; modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
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
    let rascunho = 0;
    const ok = await executar(
      async () => {
        rascunho = (await api.adicionarVideos(turma, modulo.id, sub.id, videos)).rascunho_id;
      },
      () => (
        <>
          {plural(videos.length, "vídeo entrou", "vídeos entraram")} em rascunho.{" "}
          <Link className="font-semibold underline" href={`/admin/rascunhos/revisar/?id=${rascunho}`}>Revisar e publicar</Link>
        </>
      ),
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
          {quantos ? `Adicionar ${plural(quantos, "vídeo")} em rascunho` : "Escolha os vídeos"}
        </Botao>
        <Botao onClick={aoFechar}>Fechar</Botao>
      </div>
    </div>
  );
}

function Classificar({ turma, modulo, sub, assuntos, executar, aoFechar }: { turma: number; modulo: Modulo; sub: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
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
