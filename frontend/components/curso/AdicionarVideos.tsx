"use client";

// Vídeos do Vimeo entrando num sub-módulo. O professor passeia pelas pastas como elas estão no
// Vimeo, marca o que quer (um vídeo, vários, a pasta inteira, de pastas diferentes) e grava tudo
// de uma vez. Abre num off-canvas: o curso continua à vista atrás.

import { ChevronRight, Folder, ListChecks, RefreshCw, Search, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { OffCanvas } from "@/components/Camadas";
import { Aviso, Botao, Carregando } from "@/components/ui";
import { api, type Modulo, type PastaVimeo, type SubModulo, type VideoDaPasta } from "@/lib/api";
import { duracao, plural } from "@/lib/formato";
import { partesDoNome } from "@/lib/icones";
import { BIBLIOTECA, type Executar } from "./comum";

/** Um vídeo marcado para entrar, com a pasta de onde veio. A ordem da lista é a ordem de entrada. */
type Escolhido = { vimeo_id: string; titulo: string; embed_url?: string | null; url?: string | null; thumbnail_url?: string | null; duracao_segundos?: number | null; pasta?: string; origem: string; caminho: string };
/** Um vídeo na lista, venha de uma pasta ou da busca pelo título. */
type NaLista = Omit<Escolhido, "origem" | "caminho" | "pasta"> & { avisos: string[] };

const BUSCA = "busca";
const ALFABETICA = new Intl.Collator("pt-BR", { numeric: true, sensitivity: "base" });
/** "QUESTÕES" e "questoes" são a mesma busca. */
const semAcento = (texto: string) => texto.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/**
 * O capítulo costuma ter o mesmo nome no Vimeo: o "K01" do módulo acha as pastas K01 de cada ano.
 * Sem código, vale o título inteiro do módulo.
 */
function pistaDoModulo(nome: string): { texto: string; casa: (pasta: string) => boolean } {
  const { codigo, titulo } = partesDoNome(nome);
  if (codigo) {
    const inteiro = new RegExp(`(^|[^a-z0-9])${codigo}([^0-9]|$)`, "i");
    return { texto: codigo, casa: (pasta) => inteiro.test(pasta) };
  }
  const alvo = semAcento(titulo.trim());
  return { texto: titulo.trim(), casa: (pasta) => alvo.length >= 3 && semAcento(pasta).includes(alvo) };
}

/** O acervo tem centenas de pastas e muda pouco: uma leitura serve à tela inteira, até pedirem outra. */
let pastasLidas: Promise<PastaVimeo[]> | null = null;
const lerPastas = (deNovo = false) => {
  if (deNovo || !pastasLidas) {
    pastasLidas = api.pastasVimeo(undefined, 5000).then((r) => r.pastas);
    pastasLidas.catch(() => {
      pastasLidas = null;
    });
  }
  return pastasLidas;
};

export function AdicionarVideos({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  // Quem monta este painel só o faz para abrir; ao fechar, ele sai da tela e então avisa.
  const [aberto, setAberto] = useState(true);
  const jaAqui = useMemo(() => new Set(sub.itens.map((i) => i.vimeo_id).filter((id): id is string => !!id)), [sub.itens]);
  const pista = useMemo(() => pistaDoModulo(modulo.nome), [modulo.nome]);

  // --- o acervo ---
  const [pastas, setPastas] = useState<PastaVimeo[] | null>(null);
  const [erroDasPastas, setErroDasPastas] = useState("");
  const [lendo, setLendo] = useState(true);
  const [aberta, setAberta] = useState<string | null>(null);
  const [filtro, setFiltro] = useState("");
  // Os vídeos de cada pasta já aberta: voltar a ela não lê o Vimeo de novo.
  const [videosDe, setVideosDe] = useState<Record<string, VideoDaPasta[]>>({});
  const [erroDosVideos, setErroDosVideos] = useState<Record<string, string>>({});
  const pedidos = useRef(new Map<string, Promise<VideoDaPasta[]>>());
  const [porTitulo, setPorTitulo] = useState<{ termo: string; videos: NaLista[] } | null>(null);
  const [buscando, setBuscando] = useState(false);
  const [erroDaBusca, setErroDaBusca] = useState("");

  // --- o que foi marcado ---
  const [escolhidos, setEscolhidos] = useState<Escolhido[]>([]);
  const [vendo, setVendo] = useState<"vimeo" | "escolhidos">("vimeo");
  const [marcandoPasta, setMarcandoPasta] = useState<string | null>(null);
  const [gravando, setGravando] = useState(false);
  const [erro, setErro] = useState("");
  const [saindo, setSaindo] = useState(false);

  const ler = (deNovo: boolean) => {
    setLendo(true);
    setErroDasPastas("");
    lerPastas(deNovo)
      .then(setPastas, (ex: Error) => setErroDasPastas(ex.message))
      .finally(() => setLendo(false));
  };
  useEffect(() => ler(false), []);

  const { porId, filhas } = useMemo(() => {
    const porId = new Map<string, PastaVimeo>();
    const filhas = new Map<string | null, PastaVimeo[]>();
    for (const p of pastas ?? []) porId.set(p.id, p);
    for (const p of pastas ?? []) {
      // Pasta cuja mãe não veio na lista aparece na raiz, em vez de sumir.
      const mae = p.pai_id && porId.has(p.pai_id) ? p.pai_id : null;
      filhas.set(mae, [...(filhas.get(mae) ?? []), p]);
    }
    for (const lista of filhas.values()) lista.sort((a, b) => ALFABETICA.compare(a.nome, b.nome));
    return { porId, filhas };
  }, [pastas]);

  const ancestrais = (p: PastaVimeo) => {
    const trilha: PastaVimeo[] = [];
    for (let mae = p.pai_id ? porId.get(p.pai_id) : undefined; mae && trilha.length < 20; mae = mae.pai_id ? porId.get(mae.pai_id) : undefined) trilha.unshift(mae);
    return trilha;
  };
  const caminhoDe = (p: PastaVimeo) => ancestrais(p).map((a) => a.nome).join(" › ");
  const caminhoCom = (p: PastaVimeo) => [...ancestrais(p), p].map((a) => a.nome).join(" › ");

  const lerVideos = (pastaId: string) => {
    let pedido = pedidos.current.get(pastaId);
    if (!pedido) {
      pedido = api.videosDaPasta(pastaId).then((r) => r.videos);
      pedidos.current.set(pastaId, pedido);
      setErroDosVideos((atual) => ({ ...atual, [pastaId]: "" }));
      pedido.then(
        (videos) => setVideosDe((atual) => ({ ...atual, [pastaId]: videos })),
        (ex: Error) => {
          pedidos.current.delete(pastaId);
          setErroDosVideos((atual) => ({ ...atual, [pastaId]: ex.message }));
        },
      );
    }
    return pedido;
  };

  const atual = aberta ? porId.get(aberta) : undefined;
  useEffect(() => {
    if (atual && (atual.videos ?? 0) > 0) void lerVideos(atual.id).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id]);

  // --- marcar e desmarcar ---
  const marcados = useMemo(() => new Set(escolhidos.map((e) => e.vimeo_id)), [escolhidos]);
  // Quantos marcados há em cada pasta, contando as de dentro: é o que a linha da pasta mostra.
  const marcadosNaPasta = useMemo(() => {
    const conta = new Map<string, number>();
    for (const e of escolhidos) {
      for (let p = porId.get(e.origem), n = 0; p && n < 20; p = p.pai_id ? porId.get(p.pai_id) : undefined, n++) conta.set(p.id, (conta.get(p.id) ?? 0) + 1);
    }
    return conta;
  }, [escolhidos, porId]);

  const podeEntrar = (v: NaLista) => !jaAqui.has(v.vimeo_id);
  const escolher = (videos: NaLista[], origem: string, caminho: string, pasta?: string) =>
    setEscolhidos((lista) => {
      const tem = new Set(lista.map((e) => e.vimeo_id));
      const novos = videos.filter((v) => podeEntrar(v) && !tem.has(v.vimeo_id)).map(({ avisos: _, ...v }) => ({ ...v, origem, caminho, pasta }));
      return [...lista, ...novos];
    });
  const tirar = (ids: string[]) => setEscolhidos((lista) => lista.filter((e) => !ids.includes(e.vimeo_id)));

  /** "Todos" não leva o vídeo que o aluno não conseguiria assistir: esse o professor marca à mão. */
  const marcaveis = (videos: NaLista[]) => videos.filter((v) => podeEntrar(v) && v.avisos.length === 0);

  async function alternarPasta(p: PastaVimeo) {
    setErro("");
    setMarcandoPasta(p.id);
    try {
      const videos = await lerVideos(p.id);
      const todos = marcaveis(videos);
      if (todos.length > 0 && todos.every((v) => marcados.has(v.vimeo_id))) tirar(todos.map((v) => v.vimeo_id));
      else escolher(todos, p.id, caminhoCom(p), p.nome);
    } catch (ex) {
      setErro(`Não deu para ler a pasta ${p.nome}: ${(ex as Error).message}`);
    } finally {
      setMarcandoPasta(null);
    }
  }

  async function buscarPorTitulo(e: FormEvent) {
    e.preventDefault();
    const termo = filtro.trim();
    if (!termo) return;
    setBuscando(true);
    setErroDaBusca("");
    try {
      const achados = await api.videosVimeo({ busca: termo, limite: 50 });
      setPorTitulo({ termo, videos: achados.map((v) => ({ vimeo_id: v.id, titulo: v.titulo, embed_url: v.embed_url, url: v.url, thumbnail_url: v.thumbnail_url, duracao_segundos: v.duracao_segundos, avisos: [] })) });
    } catch (ex) {
      setErroDaBusca((ex as Error).message);
    } finally {
      setBuscando(false);
    }
  }

  // --- gravar e sair ---
  async function gravar() {
    setGravando(true);
    setErro("");
    try {
      const videos = escolhidos.map(({ origem: _, caminho: __, ...v }) => v);
      const recusados = (await api.adicionarVideos(BIBLIOTECA, modulo.id, sub.id, videos)).erros;
      const entraram = videos.length - recusados.length;
      // O recado mora na página, atrás desta camada: só aparece quando ela fecha.
      void executar(
        () => Promise.resolve(),
        `${plural(entraram, "vídeo adicionado", "vídeos adicionados")} em ${sub.nome}, já ${entraram === 1 ? "publicado" : "publicados"}.` + (recusados.length ? ` Ficaram de fora: ${recusados.join("; ")}.` : ""),
      );
      setAberto(false);
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setGravando(false);
    }
  }

  const pedirParaSair = () => {
    if (gravando) return;
    if (escolhidos.length > 0 && !saindo) setSaindo(true);
    else setAberto(false);
  };

  // --- as linhas ---
  const termo = semAcento(filtro.trim());
  const trilha = atual ? [...ancestrais(atual), atual] : [];
  const noNivel = filhas.get(atual?.id ?? null) ?? [];
  const achadas = termo && pastas ? pastas.filter((p) => semAcento(p.nome).includes(termo)).sort((a, b) => ALFABETICA.compare(a.nome, b.nome)) : [];
  const doModulo = !termo && !atual && pastas ? pastas.filter((p) => (p.videos ?? 0) > 0 && pista.casa(p.nome)).sort((a, b) => ALFABETICA.compare(caminhoDe(b), caminhoDe(a))) : [];

  const abrirPasta = (id: string | null) => {
    setFiltro("");
    setPorTitulo(null);
    setAberta(id);
  };

  const linhaDaPasta = (p: PastaVimeo, comCaminho: boolean) => {
    const dentro = filhas.get(p.id)?.length ?? 0;
    const videos = p.videos ?? 0;
    const total = p.videos_com_subpastas ?? videos;
    const caminho = caminhoDe(p);
    const vazia = !dentro && !videos;
    const marcadosAqui = marcadosNaPasta.get(p.id) ?? 0;
    const lidos = videosDe[p.id];
    const todosMarcados = !!lidos && marcaveis(lidos).length > 0 && marcaveis(lidos).every((v) => marcados.has(v.vimeo_id));
    return (
      <li key={p.id} className="flex items-stretch">
        <button type="button" disabled={vazia} onClick={() => abrirPasta(p.id)} className="flex min-w-0 flex-1 items-center gap-3 py-2.5 pl-5 pr-2 text-left hover:bg-canvas disabled:cursor-default disabled:opacity-55 disabled:hover:bg-transparent">
          <Folder aria-hidden="true" className="size-[18px] shrink-0 text-suave" strokeWidth={1.8} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium text-tinta">{p.nome}</span>
            <span className="block truncate text-[13px] text-suave">
              {vazia ? "vazia" : dentro ? `${plural(dentro, "pasta")}, ${plural(total, "vídeo")}` : plural(videos, "vídeo")}
              {comCaminho && caminho ? ` · ${caminho}` : ""}
            </span>
          </span>
          {marcadosAqui > 0 && <span className="shrink-0 rounded-full bg-acento px-2 py-0.5 text-xs font-bold tabular-nums text-white">{marcadosAqui}</span>}
          <ChevronRight aria-hidden="true" className={`size-4 shrink-0 text-suave ${vazia ? "invisible" : ""}`} />
        </button>
        {videos > 0 && (
          <button
            type="button"
            disabled={marcandoPasta === p.id}
            onClick={() => void alternarPasta(p)}
            aria-label={todosMarcados ? `Desmarcar os vídeos de ${p.nome}` : `Marcar todos os vídeos de ${p.nome}`}
            className="shrink-0 border-l border-borda/70 px-4 text-sm font-semibold text-acento hover:bg-lilas hover:text-acento-forte disabled:text-apagado"
          >
            {marcandoPasta === p.id ? "Lendo…" : todosMarcados ? "Desmarcar" : dentro ? `Marcar os ${videos}` : "Marcar todos"}
          </button>
        )}
      </li>
    );
  };

  const linhaDoVideo = (v: NaLista, origem: string, caminho: string, pasta?: string) => {
    const aqui = jaAqui.has(v.vimeo_id);
    return (
      <li key={v.vimeo_id}>
        <label className={`flex items-start gap-3 py-2.5 pl-5 pr-5 ${aqui ? "opacity-60" : "cursor-pointer hover:bg-canvas"}`}>
          <input
            type="checkbox"
            disabled={aqui}
            checked={marcados.has(v.vimeo_id)}
            onChange={(e) => (e.target.checked ? escolher([v], origem, caminho, pasta) : tirar([v.vimeo_id]))}
            className="mt-1 size-4 shrink-0 accent-acento"
          />
          <span className="min-w-0 flex-1">
            <span className="block text-[15px] text-tinta">{v.titulo}</span>
            {aqui && <span className="block text-[13px] text-suave">Já está em {sub.nome}</span>}
            {!aqui && v.avisos.length > 0 && <span className="block text-[13px] text-atencao">{v.avisos.join("; ")}</span>}
          </span>
          <span className="shrink-0 pt-0.5 text-[13px] tabular-nums text-suave">{duracao(v.duracao_segundos)}</span>
        </label>
      </li>
    );
  };

  /** A lista de vídeos com o "marcar todos" no cabeçalho. */
  const listaDeVideos = (titulo: string, videos: NaLista[], origem: string, caminho: string, pasta?: string) => {
    const todos = marcaveis(videos);
    const quantos = todos.filter((v) => marcados.has(v.vimeo_id)).length;
    const completo = todos.length > 0 && quantos === todos.length;
    return (
      <section>
        <label className={`flex items-center gap-3 border-y border-borda bg-canvas/70 py-2 pl-5 pr-5 text-[13px] font-semibold text-tinta-2 ${todos.length ? "cursor-pointer" : ""}`}>
          <input
            type="checkbox"
            disabled={!todos.length}
            checked={completo}
            ref={(el) => {
              if (el) el.indeterminate = quantos > 0 && !completo;
            }}
            onChange={(e) => (e.target.checked ? escolher(todos, origem, caminho, pasta) : tirar(todos.map((v) => v.vimeo_id)))}
            className="size-4 accent-acento"
          />
          <span className="flex-1">{titulo}</span>
          {quantos > 0 && <span className="tabular-nums text-acento-forte">{plural(quantos, "marcado")}</span>}
        </label>
        <ol className="divide-y divide-borda/70">{videos.map((v) => linhaDoVideo(v, origem, caminho, pasta))}</ol>
      </section>
    );
  };

  const titulozinho = (texto: ReactNode) => <p className="border-b border-borda bg-canvas/70 py-2 pl-5 pr-5 text-[13px] font-semibold text-tinta-2">{texto}</p>;

  // --- o corpo ---
  let corpo: ReactNode;
  if (vendo === "escolhidos") {
    corpo = (
      <div>
        <div className="flex items-center gap-3 border-b border-borda bg-canvas/70 py-2 pl-5 pr-3 text-[13px] font-semibold text-tinta-2">
          <span className="flex-1">Entram nesta ordem, no fim de {sub.nome}</span>
          <Botao variante="texto" onClick={() => { setEscolhidos([]); setVendo("vimeo"); }}>Desmarcar todos</Botao>
        </div>
        <ol className="divide-y divide-borda/70">
          {escolhidos.map((e, i) => (
            <li key={e.vimeo_id} className="flex items-start gap-3 py-2.5 pl-5 pr-3">
              <span className="w-6 shrink-0 pt-0.5 text-right text-[13px] font-semibold tabular-nums text-suave">{i + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] text-tinta">{e.titulo}</span>
                <span className="block truncate text-[13px] text-suave">{e.caminho}</span>
              </span>
              <span className="shrink-0 pt-0.5 text-[13px] tabular-nums text-suave">{duracao(e.duracao_segundos)}</span>
              <button type="button" onClick={() => tirar([e.vimeo_id])} aria-label={`Desmarcar ${e.titulo}`} className="-my-1 flex size-8 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-canvas hover:text-erro">
                <X aria-hidden="true" className="size-4" />
              </button>
            </li>
          ))}
        </ol>
      </div>
    );
  } else if (erroDasPastas) {
    corpo = (
      <div className="p-5">
        <Aviso tom="erro">
          {erroDasPastas} <button type="button" onClick={() => ler(true)} className="font-semibold underline">Tentar de novo</button>
        </Aviso>
      </div>
    );
  } else if (!pastas) {
    corpo = (
      <div className="p-5">
        <Carregando linhas={6} />
      </div>
    );
  } else if (termo) {
    corpo = (
      <div>
        {titulozinho(achadas.length ? `${plural(achadas.length, "pasta")} com "${filtro.trim()}" no nome` : `Nenhuma pasta com "${filtro.trim()}" no nome`)}
        {achadas.length > 0 && <ul className="divide-y divide-borda/70">{achadas.slice(0, 80).map((p) => linhaDaPasta(p, true))}</ul>}
        {erroDaBusca && (
          <div className="p-5">
            <Aviso tom="erro">{erroDaBusca}</Aviso>
          </div>
        )}
        {porTitulo && porTitulo.termo === filtro.trim() ? (
          porTitulo.videos.length ? (
            listaDeVideos(`${plural(porTitulo.videos.length, "vídeo")} com "${porTitulo.termo}" no título`, porTitulo.videos, BUSCA, `Busca por "${porTitulo.termo}"`)
          ) : (
            <p className="border-t border-borda px-5 py-4 text-[15px] text-suave">Nenhum vídeo com &quot;{porTitulo.termo}&quot; no título.</p>
          )
        ) : (
          <div className="border-t border-borda px-5 py-4">
            <Botao type="submit" form="busca-no-vimeo" variante="secundario" tamanho="pequeno" disabled={buscando}>
              <Search aria-hidden="true" className="size-4" />
              {buscando ? "Buscando…" : `Buscar vídeos com "${filtro.trim()}" no título`}
            </Botao>
          </div>
        )}
      </div>
    );
  } else {
    const videosDaAtual = atual ? videosDe[atual.id] : undefined;
    corpo = (
      <div>
        {doModulo.length > 0 && (
          <>
            {titulozinho(`Com "${pista.texto}" no nome`)}
            <ul className="divide-y divide-borda/70 border-b border-borda">{doModulo.slice(0, 12).map((p) => linhaDaPasta(p, true))}</ul>
            {titulozinho("Todas as pastas")}
          </>
        )}
        {noNivel.length > 0 && <ul className="divide-y divide-borda/70">{noNivel.map((p) => linhaDaPasta(p, false))}</ul>}
        {atual && (atual.videos ?? 0) > 0 && (
          <>
            {erroDosVideos[atual.id] && (
              <div className="p-5">
                <Aviso tom="erro">
                  {erroDosVideos[atual.id]} <button type="button" onClick={() => void lerVideos(atual.id).catch(() => {})} className="font-semibold underline">Tentar de novo</button>
                </Aviso>
              </div>
            )}
            {!videosDaAtual && !erroDosVideos[atual.id] && (
              <div className="p-5">
                <Carregando linhas={5} />
              </div>
            )}
            {videosDaAtual && listaDeVideos(noNivel.length ? `${plural(videosDaAtual.length, "vídeo solto", "vídeos soltos")} em ${atual.nome}` : plural(videosDaAtual.length, "vídeo"), videosDaAtual, atual.id, caminhoCom(atual), atual.nome)}
          </>
        )}
        {atual && !noNivel.length && !(atual.videos ?? 0) && <p className="px-5 py-6 text-[15px] text-suave">Esta pasta está vazia.</p>}
        {!atual && pastas.length === 0 && <p className="px-5 py-6 text-[15px] text-suave">O Vimeo não tem nenhuma pasta. Use a busca acima para achar um vídeo pelo título.</p>}
      </div>
    );
  }

  const rodape = saindo ? (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-[15px] font-medium text-tinta">Sair sem adicionar {escolhidos.length === 1 ? "o vídeo marcado" : `os ${escolhidos.length} vídeos marcados`}?</p>
      <div className="flex gap-2">
        <Botao onClick={() => setSaindo(false)}>Continuar escolhendo</Botao>
        <Botao variante="perigo" onClick={() => setAberto(false)}>Sair</Botao>
      </div>
    </div>
  ) : (
    <div className="flex flex-col gap-2.5">
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        {escolhidos.length > 0 ? (
          <Botao variante="texto" onClick={() => setVendo(vendo === "vimeo" ? "escolhidos" : "vimeo")} aria-pressed={vendo === "escolhidos"}>
            <ListChecks aria-hidden="true" className="size-4" />
            {vendo === "vimeo" ? `Ver ${escolhidos.length === 1 ? "o marcado" : `os ${escolhidos.length} marcados`}` : "Voltar ao Vimeo"}
          </Botao>
        ) : (
          <p className="text-sm text-suave">Marque os vídeos; pode ser de pastas diferentes.</p>
        )}
        <div className="flex gap-2">
          <Botao onClick={pedirParaSair} disabled={gravando}>Cancelar</Botao>
          <Botao variante="primario" disabled={!escolhidos.length || gravando} onClick={() => void gravar()}>
            {gravando ? "Adicionando…" : escolhidos.length ? `Adicionar ${plural(escolhidos.length, "vídeo")}` : "Adicionar"}
          </Botao>
        </div>
      </div>
    </div>
  );

  return (
    <OffCanvas
      aberto={aberto}
      aoFechar={pedirParaSair}
      aoSumir={aoFechar}
      lado="direita"
      tamanho="grande"
      titulo="Adicionar vídeos do Vimeo"
      legenda={`Entram no fim de ${sub.nome}, em ${modulo.nome}, já publicados.`}
      semMargem
      rodape={rodape}
    >
      {vendo === "vimeo" && (
        <div className="sticky top-0 z-10 flex flex-col gap-2.5 border-b border-borda bg-papel px-5 py-3">
          <form id="busca-no-vimeo" onSubmit={buscarPorTitulo} className="flex items-center gap-2">
            <label htmlFor="filtro-do-vimeo" className="sr-only">Filtrar as pastas ou buscar um vídeo pelo título</label>
            <div className="relative min-w-0 flex-1">
              <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-apagado" />
              <input id="filtro-do-vimeo" type="search" value={filtro} onChange={(e) => setFiltro(e.target.value)} placeholder="Nome da pasta ou título do vídeo" className="campo pl-9" />
            </div>
            <Botao variante="texto" disabled={lendo} onClick={() => ler(true)} title="Ler as pastas do Vimeo de novo">
              <RefreshCw aria-hidden="true" className={`size-4 ${lendo ? "animate-spin" : ""}`} />
              <span className="max-sm:sr-only">Atualizar</span>
            </Botao>
          </form>
          {!termo && (
            <nav aria-label="Onde você está no Vimeo" className="flex flex-wrap items-center gap-1 text-[13px]">
              <button type="button" onClick={() => abrirPasta(null)} disabled={!atual} className="font-semibold text-acento hover:underline disabled:text-tinta-2 disabled:no-underline">Vimeo</button>
              {trilha.map((p, i) => (
                <span key={p.id} className="flex items-center gap-1">
                  <ChevronRight aria-hidden="true" className="size-3.5 text-suave" />
                  <button type="button" onClick={() => abrirPasta(p.id)} disabled={i === trilha.length - 1} className="font-semibold text-acento hover:underline disabled:text-tinta-2 disabled:no-underline">{p.nome}</button>
                </span>
              ))}
            </nav>
          )}
        </div>
      )}
      {corpo}
    </OffCanvas>
  );
}
