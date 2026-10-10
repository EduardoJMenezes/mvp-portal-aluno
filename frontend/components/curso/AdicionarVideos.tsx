"use client";

// Vídeos do Vimeo entrando num sub-módulo. O professor passeia pelas pastas como elas estão no
// Vimeo, ou busca pelo nome, marca o que quer (um vídeo, vários, a pasta inteira, de pastas
// diferentes) e grava tudo de uma vez. Abre num off-canvas: o curso continua à vista atrás.

import { ChevronRight, Folder, LoaderCircle, Play, RefreshCw, Search, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { OffCanvas } from "@/components/Camadas";
import { PassarVideos, TelaDoVideo } from "@/components/PreviaDoVideo";
import { Aviso, Botao, Carregamento, Esqueleto } from "@/components/ui";
import { api, type Modulo, type PastaVimeo, type SubModulo, type VideoDaPasta } from "@/lib/api";
import { duracao, plural } from "@/lib/formato";
import { BIBLIOTECA, type Executar } from "./comum";

/** Um vídeo marcado para entrar, com a pasta de onde veio. A ordem da lista é a ordem de entrada. */
type Escolhido = { vimeo_id: string; titulo: string; embed_url?: string | null; url?: string | null; thumbnail_url?: string | null; duracao_segundos?: number | null; pasta?: string; origem: string; caminho: string };
/** Um vídeo na lista. `origem` e `caminho` só vêm na busca: dentro de uma pasta, valem os dela. */
type NaLista = Omit<Escolhido, "origem" | "caminho" | "pasta"> & { avisos: string[]; origem?: string; caminho?: string; pasta?: string };

/** O vídeo aberto na prévia: o da lista, já com a pasta de onde veio, para poder ser marcado dali. */
type NaPrevia = NaLista & { origem: string; caminho: string };

const SEM_PASTA = "sem-pasta";
/** Quanto a busca espera depois da última tecla antes de perguntar ao Vimeo. */
const ESPERA_DA_BUSCA_MS = 450;
const MAXIMO_NA_BUSCA = 50;
const ALFABETICA = new Intl.Collator("pt-BR", { numeric: true, sensitivity: "base" });
/** "QUESTÕES" e "questoes" são a mesma busca. */
const semAcento = (texto: string) => texto.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

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

/** A coluna da caixa de seleção: a mesma largura na pasta e no vídeo, para as caixas se alinharem. */
const COLUNA_DA_CAIXA = "flex w-12 shrink-0 items-center justify-center self-stretch";

/** As pastas chegando: a caixa, a pasta, o nome e o que há dentro. */
function EsqueletoDePastas() {
  return (
    <Carregamento rotulo="Lendo as pastas do Vimeo" className="divide-y divide-borda/70">
      {["w-24", "w-40", "w-32", "w-56", "w-28", "w-44", "w-36"].map((largura, i) => (
        <div key={i} className="flex items-center py-3 pr-4">
          <span className={COLUNA_DA_CAIXA}>{i > 1 && <Esqueleto className="size-4 rounded" />}</span>
          <Esqueleto className="mr-3 size-[18px] shrink-0 rounded" />
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <Esqueleto className={`h-4 ${largura}`} />
            <Esqueleto className="h-3 w-20" />
          </div>
        </div>
      ))}
    </Carregamento>
  );
}

/** Os vídeos de uma pasta chegando: o cabeçalho do "marcar todos" e uma linha por vídeo. */
function EsqueletoDeVideos({ quantos, comCaminho = false, rotulo = "Lendo os vídeos da pasta" }: { quantos: number; comCaminho?: boolean; rotulo?: string }) {
  const larguras = ["w-3/5", "w-2/5", "w-1/2", "w-2/3", "w-1/3"];
  return (
    <Carregamento rotulo={rotulo}>
      <div className="flex items-center border-y border-borda bg-canvas/70 py-2.5 pr-5">
        <span className={COLUNA_DA_CAIXA}>
          <Esqueleto className="size-4 rounded" />
        </span>
        <Esqueleto className="h-3 w-20" />
      </div>
      <div className="divide-y divide-borda/70">
        {Array.from({ length: Math.max(quantos, 1) }, (_, i) => (
          <div key={i} className="flex items-center py-3 pr-5">
            <span className={COLUNA_DA_CAIXA}>
              <Esqueleto className="size-4 rounded" />
            </span>
            <div className="flex min-w-0 flex-1 flex-col gap-2">
              <Esqueleto className={`h-4 ${larguras[i % larguras.length]}`} />
              {comCaminho && <Esqueleto className="h-3 w-2/5" />}
            </div>
            <Esqueleto className="ml-3 h-3 w-9 shrink-0" />
          </div>
        ))}
      </div>
    </Carregamento>
  );
}

export function AdicionarVideos({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  // Quem monta este painel só o faz para abrir; ao fechar, ele sai da tela e então avisa.
  const [aberto, setAberto] = useState(true);
  const jaAqui = useMemo(() => new Set(sub.itens.map((i) => i.vimeo_id).filter((id): id is string => !!id)), [sub.itens]);

  // --- o acervo ---
  const [pastas, setPastas] = useState<PastaVimeo[] | null>(null);
  const [erroDasPastas, setErroDasPastas] = useState("");
  const [lendo, setLendo] = useState(true);
  const [aberta, setAberta] = useState<string | null>(null);
  const [busca, setBusca] = useState("");
  // Os vídeos de cada pasta já aberta: voltar a ela não lê o Vimeo de novo.
  const [videosDe, setVideosDe] = useState<Record<string, VideoDaPasta[]>>({});
  const [erroDosVideos, setErroDosVideos] = useState<Record<string, string>>({});
  const pedidos = useRef(new Map<string, Promise<VideoDaPasta[]>>());
  const [achados, setAchados] = useState<{ termo: string; videos: NaLista[] } | null>(null);
  const [erroDaBusca, setErroDaBusca] = useState("");
  const vezDaBusca = useRef(0);

  // --- o que foi marcado ---
  const [escolhidos, setEscolhidos] = useState<Escolhido[]>([]);
  const [vendo, setVendo] = useState<"vimeo" | "escolhidos">("vimeo");
  const [marcandoPasta, setMarcandoPasta] = useState<string | null>(null);
  const [gravando, setGravando] = useState(false);
  const [erro, setErro] = useState("");
  const [saindo, setSaindo] = useState(false);
  // A prévia: o vídeo que o professor quis conferir, tocando no alto do painel.
  const [previa, setPrevia] = useState<NaPrevia | null>(null);

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

  // A busca procura os vídeos no Vimeo inteiro, um instante depois da última tecla; as pastas são
  // filtradas na hora, porque já estão todas aqui.
  const termoDigitado = busca.trim();
  useEffect(() => {
    const vez = ++vezDaBusca.current;
    setErroDaBusca("");
    if (termoDigitado.length < 2) return;
    const id = setTimeout(() => {
      api.videosVimeo({ busca: termoDigitado, limite: MAXIMO_NA_BUSCA }).then(
        (videos) => {
          if (vez !== vezDaBusca.current) return;
          setAchados({ termo: termoDigitado, videos: videos.map((v) => ({ vimeo_id: v.id, titulo: v.titulo, embed_url: v.embed_url, url: v.url, thumbnail_url: v.thumbnail_url, duracao_segundos: v.duracao_segundos, avisos: [], origem: v.pasta_id ?? SEM_PASTA, pasta: v.pasta ?? undefined })) });
        },
        (ex: Error) => {
          if (vez === vezDaBusca.current) setErroDaBusca(ex.message);
        },
      );
    }, ESPERA_DA_BUSCA_MS);
    return () => clearTimeout(id);
  }, [termoDigitado]);

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
      const novos = videos
        .filter((v) => podeEntrar(v) && !tem.has(v.vimeo_id))
        .map(({ avisos: _, ...v }) => ({ ...v, origem: v.origem ?? origem, caminho: v.caminho ?? caminho, pasta: v.pasta ?? pasta }));
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

  const abrirPasta = (id: string | null) => {
    setBusca("");
    setAberta(id);
  };

  // --- as linhas ---
  const selo = (texto: string) => <span className="shrink-0 rounded-full bg-lilas px-2.5 py-0.5 text-xs font-bold tabular-nums text-acento-forte">{texto}</span>;

  const linhaDaPasta = (p: PastaVimeo, comCaminho: boolean) => {
    const dentro = filhas.get(p.id)?.length ?? 0;
    const videos = p.videos ?? 0;
    const total = p.videos_com_subpastas ?? videos;
    const caminho = caminhoDe(p);
    const vazia = !dentro && !videos;
    const marcadosAqui = marcadosNaPasta.get(p.id) ?? 0;
    // A caixa da pasta vale "todos os vídeos dela": só existe onde a pasta é só de vídeos. Pasta
    // com outras pastas dentro se abre, e lá cada uma tem a sua.
    const comCaixa = videos > 0 && !dentro;
    const lidos = videosDe[p.id];
    const paraMarcar = lidos ? marcaveis(lidos) : null;
    const todos = !!paraMarcar && paraMarcar.length > 0 && paraMarcar.every((v) => marcados.has(v.vimeo_id));
    const nadaNovo = !!paraMarcar && paraMarcar.length === 0;
    const conteudo = vazia ? "vazia" : dentro ? `${plural(dentro, "pasta")}, ${plural(total, "vídeo")}` : nadaNovo ? `${plural(videos, "vídeo")}, nenhum novo para ${sub.nome}` : plural(videos, "vídeo");
    return (
      <li key={p.id} className={`flex items-stretch transition-colors ${todos ? "bg-lilas/40" : ""} ${vazia ? "" : todos ? "hover:bg-lilas/70" : "hover:bg-canvas"}`}>
        {comCaixa ? (
          <label className={`${COLUNA_DA_CAIXA} ${nadaNovo ? "" : "cursor-pointer"}`} title={nadaNovo ? undefined : todos ? "Desmarcar os vídeos desta pasta" : "Marcar todos os vídeos desta pasta"}>
            {marcandoPasta === p.id ? (
              <LoaderCircle aria-label={`Lendo a pasta ${p.nome}`} className="size-4 animate-spin text-acento" />
            ) : (
              <input
                type="checkbox"
                disabled={nadaNovo}
                checked={todos}
                ref={(el) => {
                  if (el) el.indeterminate = marcadosAqui > 0 && !todos;
                }}
                onChange={() => void alternarPasta(p)}
                aria-label={`Marcar todos os vídeos da pasta ${p.nome}`}
                className="size-4 accent-acento"
              />
            )}
          </label>
        ) : (
          <span aria-hidden="true" className={COLUNA_DA_CAIXA} />
        )}
        <button type="button" disabled={vazia} onClick={() => abrirPasta(p.id)} className="flex min-w-0 flex-1 items-center gap-3 py-2.5 pr-4 text-left disabled:cursor-default disabled:opacity-55">
          <Folder aria-hidden="true" className="size-[18px] shrink-0 text-suave" strokeWidth={1.8} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium text-tinta">{p.nome}</span>
            <span className="block truncate text-[13px] text-suave">
              {conteudo}
              {comCaminho && caminho ? ` · ${caminho}` : ""}
            </span>
          </span>
          {marcadosAqui > 0 && selo(comCaixa ? `${marcadosAqui} de ${videos}` : plural(marcadosAqui, "marcado"))}
          <ChevronRight aria-hidden="true" className={`size-4 shrink-0 text-suave ${vazia ? "invisible" : ""}`} />
        </button>
      </li>
    );
  };

  /** O botão que abre (ou fecha) a prévia do vídeo: o player só existe depois do clique. */
  const botaoDeVer = (v: NaPrevia) => {
    const tocando = previa?.vimeo_id === v.vimeo_id;
    return (
      <button
        type="button"
        onClick={() => setPrevia(tocando ? null : v)}
        aria-pressed={tocando}
        aria-label={tocando ? `Fechar a prévia de ${v.titulo}` : `Ver o vídeo ${v.titulo}`}
        title={tocando ? "Fechar a prévia" : "Ver o vídeo"}
        className={`my-auto mr-3 flex size-8 shrink-0 items-center justify-center rounded-full transition-colors ${tocando ? "bg-acento text-white" : "text-acento hover:bg-lilas hover:text-acento-forte"}`}
      >
        <Play aria-hidden="true" className="size-3.5 translate-x-px" fill="currentColor" />
      </button>
    );
  };

  const linhaDoVideo = (v: NaLista, origem: string, caminho: string, pasta?: string, comCaminho = false) => {
    const aqui = jaAqui.has(v.vimeo_id);
    const marcado = marcados.has(v.vimeo_id);
    const tocando = previa?.vimeo_id === v.vimeo_id;
    return (
      <li key={v.vimeo_id} className={`flex items-stretch transition-colors ${tocando ? "shadow-[inset_3px_0_0_var(--color-acento)]" : ""} ${marcado ? "bg-lilas/40 hover:bg-lilas/70" : "hover:bg-canvas"}`}>
        <label className={`flex min-w-0 flex-1 items-stretch pr-3 ${aqui ? "opacity-60" : "cursor-pointer"}`}>
          <span className={COLUNA_DA_CAIXA}>
            <input type="checkbox" disabled={aqui} checked={marcado} onChange={(e) => (e.target.checked ? escolher([v], origem, caminho, pasta) : tirar([v.vimeo_id]))} className="size-4 accent-acento" />
          </span>
          <span className="min-w-0 flex-1 py-2.5">
            <span className="block text-[15px] text-tinta">{v.titulo}</span>
            {comCaminho && <span className="block truncate text-[13px] text-suave">{v.caminho ?? caminho}</span>}
            {aqui && <span className="block text-[13px] text-suave">Já está em {sub.nome}</span>}
            {!aqui && v.avisos.length > 0 && <span className="block text-[13px] text-atencao">{v.avisos.join("; ")}</span>}
          </span>
          <span className="shrink-0 py-2.5 pl-3 pt-3 text-[13px] tabular-nums text-suave">{duracao(v.duracao_segundos)}</span>
        </label>
        {botaoDeVer({ ...v, origem: v.origem ?? origem, caminho: v.caminho ?? caminho, pasta: v.pasta ?? pasta })}
      </li>
    );
  };

  /** A lista de vídeos com o "marcar todos" no cabeçalho. */
  const listaDeVideos = (titulo: string, videos: NaLista[], origem: string, caminho: string, pasta?: string, comCaminho = false) => {
    const todos = marcaveis(videos);
    const quantos = todos.filter((v) => marcados.has(v.vimeo_id)).length;
    const completo = todos.length > 0 && quantos === todos.length;
    return (
      <section>
        <label className={`flex items-stretch border-y border-borda bg-canvas/70 pr-5 text-[13px] font-semibold text-tinta-2 ${todos.length ? "cursor-pointer" : ""}`}>
          <span className={COLUNA_DA_CAIXA}>
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
          </span>
          <span className="flex-1 py-2">{titulo}</span>
          {quantos > 0 && <span className="py-2 tabular-nums text-acento-forte">{quantos} de {todos.length}</span>}
        </label>
        <ol className="divide-y divide-borda/70">{videos.map((v) => linhaDoVideo(v, origem, caminho, pasta, comCaminho))}</ol>
      </section>
    );
  };

  const titulozinho = (texto: ReactNode) => <p className="border-b border-borda bg-canvas/70 px-5 py-2 text-[13px] font-semibold text-tinta-2">{texto}</p>;

  // --- o corpo ---
  const termo = semAcento(termoDigitado);
  const trilha = atual ? [...ancestrais(atual), atual] : [];
  const noNivel = filhas.get(atual?.id ?? null) ?? [];
  const pastasAchadas = termo && pastas ? pastas.filter((p) => semAcento(p.nome).includes(termo)).sort((a, b) => ALFABETICA.compare(a.nome, b.nome)) : [];

  const achadosAgora = termo && achados && achados.termo === termoDigitado ? achados.videos : null;
  const videosDaBusca = achadosAgora?.map((v) => {
    const mae = v.origem ? porId.get(v.origem) : undefined;
    return { ...v, caminho: mae ? caminhoCom(mae) : (v.pasta ?? "Fora de pasta") };
  });
  const videosDaPastaAberta = atual ? videosDe[atual.id] : undefined;

  // A fila em que a prévia anda: a lista de vídeos que está na tela agora.
  const fila: NaPrevia[] =
    vendo === "escolhidos"
      ? escolhidos.map((e) => ({ ...e, avisos: [] }))
      : termo
        ? (videosDaBusca ?? []).map((v) => ({ ...v, origem: v.origem ?? SEM_PASTA }))
        : atual && videosDaPastaAberta
          ? videosDaPastaAberta.map((v) => ({ ...v, origem: atual.id, caminho: caminhoCom(atual), pasta: atual.nome }))
          : [];
  const naFila = previa ? fila.findIndex((v) => v.vimeo_id === previa.vimeo_id) : -1;

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
            <li key={e.vimeo_id} className={`flex items-start gap-3 py-2.5 pl-5 pr-3 ${previa?.vimeo_id === e.vimeo_id ? "shadow-[inset_3px_0_0_var(--color-acento)]" : ""}`}>
              <span className="w-6 shrink-0 pt-0.5 text-right text-[13px] font-semibold tabular-nums text-suave">{i + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] text-tinta">{e.titulo}</span>
                <span className="block truncate text-[13px] text-suave">{e.caminho}</span>
              </span>
              <span className="shrink-0 pt-0.5 text-[13px] tabular-nums text-suave">{duracao(e.duracao_segundos)}</span>
              <span className="-my-1 -mr-3 flex">{botaoDeVer({ ...e, avisos: [] })}</span>
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
    corpo = <EsqueletoDePastas />;
  } else if (termo) {
    // A busca: pastas de qualquer nível pelo nome, e vídeos do Vimeo inteiro pelo título.
    const videos = videosDaBusca;
    corpo = (
      <div>
        {titulozinho(pastasAchadas.length ? plural(pastasAchadas.length, "pasta") : "Nenhuma pasta com esse nome")}
        {pastasAchadas.length > 0 && <ul className="divide-y divide-borda/70">{pastasAchadas.slice(0, 80).map((p) => linhaDaPasta(p, true))}</ul>}
        {erroDaBusca ? (
          <div className="border-t border-borda p-5">
            <Aviso tom="erro">{erroDaBusca}</Aviso>
          </div>
        ) : termoDigitado.length < 2 ? (
          <p className="border-t border-borda px-5 py-4 text-[15px] text-suave">Digite mais uma letra para buscar também os vídeos.</p>
        ) : !videos ? (
          <EsqueletoDeVideos quantos={4} comCaminho rotulo="Buscando vídeos" />
        ) : videos.length ? (
          <>
            {listaDeVideos(videos.length >= MAXIMO_NA_BUSCA ? `Os primeiros ${videos.length} vídeos` : plural(videos.length, "vídeo"), videos, SEM_PASTA, "Fora de pasta", undefined, true)}
            {videos.length >= MAXIMO_NA_BUSCA && <p className="border-t border-borda px-5 py-3 text-[13px] text-suave">Há mais vídeos com esse título. Escreva mais do nome para afinar a busca.</p>}
          </>
        ) : (
          <p className="border-t border-borda px-5 py-4 text-[15px] text-suave">Nenhum vídeo com esse título.</p>
        )}
      </div>
    );
  } else {
    const videosDaAtual = videosDaPastaAberta;
    corpo = (
      <div>
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
            {!videosDaAtual && !erroDosVideos[atual.id] && <EsqueletoDeVideos quantos={Math.min(atual.videos ?? 6, 8)} />}
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
          <button
            type="button"
            onClick={() => setVendo(vendo === "vimeo" ? "escolhidos" : "vimeo")}
            aria-pressed={vendo === "escolhidos"}
            className="inline-flex items-center gap-2.5 rounded-campo py-1.5 pl-1.5 pr-3 text-sm font-semibold text-tinta hover:bg-papel"
          >
            <span className="flex h-7 min-w-7 items-center justify-center rounded-full bg-acento px-2 text-[13px] font-bold tabular-nums text-white">{escolhidos.length}</span>
            <span className="text-left leading-tight">
              {escolhidos.length === 1 ? "vídeo marcado" : "vídeos marcados"}
              <span className="block text-[13px] font-semibold text-acento">{vendo === "vimeo" ? "Ver a lista" : "Voltar às pastas"}</span>
            </span>
          </button>
        ) : (
          <p className="text-sm text-suave">Marque vídeos ou pastas inteiras; pode ser de pastas diferentes.</p>
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
      <div className="sticky top-0 z-10 bg-papel">
      {previa && (
        <div className="border-b border-borda bg-canvas/70 px-5 py-3">
          {/* A largura acompanha a altura que sobra: o player nunca toma mais que um terço e pouco da tela. */}
          <div className="mx-auto w-full" style={{ maxWidth: "calc(38dvh * 16 / 9)" }}>
            <TelaDoVideo video={previa} />
          </div>
          <div className="mt-2.5 flex items-center gap-2">
            {/* A caixa fica na mesma coluna das caixas da lista, logo abaixo. */}
            <label className={`-ml-5 flex min-w-0 flex-1 items-center ${jaAqui.has(previa.vimeo_id) ? "" : "cursor-pointer"}`}>
              <span className={COLUNA_DA_CAIXA}>
                <input
                  type="checkbox"
                  disabled={jaAqui.has(previa.vimeo_id)}
                  checked={marcados.has(previa.vimeo_id)}
                  onChange={(e) => (e.target.checked ? escolher([previa], previa.origem, previa.caminho, previa.pasta) : tirar([previa.vimeo_id]))}
                  aria-label={`Marcar o vídeo ${previa.titulo}`}
                  className="size-4 accent-acento"
                />
              </span>
              <span className="min-w-0">
                <span className="block truncate text-[15px] font-semibold text-tinta">{previa.titulo}</span>
                <span className="block truncate text-[13px] text-suave">{jaAqui.has(previa.vimeo_id) ? `Já está em ${sub.nome}` : previa.caminho}</span>
              </span>
            </label>
            <PassarVideos posicao={naFila} total={fila.length} aoPassar={(passo) => setPrevia(fila[naFila + passo] ?? previa)} />
            <button type="button" onClick={() => setPrevia(null)} aria-label="Fechar a prévia" title="Fechar a prévia" className="flex size-8 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-papel hover:text-tinta">
              <X aria-hidden="true" className="size-[18px]" />
            </button>
          </div>
        </div>
      )}
      {vendo === "vimeo" && (
        <div className="flex flex-col gap-2.5 border-b border-borda px-5 py-3">
          <div className="flex items-center gap-2">
            <label htmlFor="busca-no-vimeo" className="sr-only">Buscar uma pasta ou um vídeo pelo nome</label>
            <div className="relative min-w-0 flex-1">
              <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-apagado" />
              <input id="busca-no-vimeo" type="search" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Buscar pasta ou vídeo pelo nome" className="campo pl-9" />
            </div>
            <Botao variante="texto" disabled={lendo} onClick={() => ler(true)} title="Ler as pastas do Vimeo de novo">
              <RefreshCw aria-hidden="true" className={`size-4 ${lendo ? "animate-spin" : ""}`} />
              <span className="max-sm:sr-only">Atualizar</span>
            </Botao>
          </div>
          {termo ? (
            <p className="text-[13px] text-suave">Em todas as pastas do Vimeo, de qualquer nível, e nos títulos dos vídeos.</p>
          ) : (
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
      </div>
      {/* A chave troca quando se muda de pasta ou de vista: o conteúdo novo surge, em vez de pular. */}
      <div key={`${vendo}:${termo ? "busca" : (aberta ?? "raiz")}`} className="surge">
        {corpo}
      </div>
    </OffCanvas>
  );
}
