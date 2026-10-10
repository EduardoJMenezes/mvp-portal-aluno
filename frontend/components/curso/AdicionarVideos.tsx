"use client";

// Vídeos do Vimeo entrando num sub-módulo: a pasta inteira, que é como o acervo está guardado, ou
// alguns vídeos achados pelo título.

import { ArrowLeft, ChevronRight, Folder, FolderOpen, RefreshCw } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Abas, Aviso, Botao, Campo, Carregando, Etiqueta } from "@/components/ui";
import { api, useDados, type Modulo, type PastaVimeo, type SubModulo, type VideoDaPasta, type VideoVimeo } from "@/lib/api";
import { duracao, plural } from "@/lib/formato";
import { partesDoNome } from "@/lib/icones";
import { BIBLIOTECA, type Executar } from "./comum";
import { Painel } from "./Paineis";

type ParaEntrar = { vimeo_id: string; titulo: string; embed_url?: string | null; url?: string | null; thumbnail_url?: string | null; duracao_segundos?: number | null; pasta?: string };

export function AdicionarVideos({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const [modo, setModo] = useState<"pasta" | "titulo">("pasta");
  const jaAqui = useMemo(() => new Set(sub.itens.map((i) => i.vimeo_id).filter((id): id is string => !!id)), [sub.itens]);

  async function adicionar(videos: ParaEntrar[]) {
    let recusados: string[] = [];
    const ok = await executar(
      async () => {
        recusados = (await api.adicionarVideos(BIBLIOTECA, modulo.id, sub.id, videos)).erros;
      },
      () => {
        const entraram = videos.length - recusados.length;
        return (
          `${plural(entraram, "vídeo adicionado", "vídeos adicionados")} em ${sub.nome}, já ${entraram === 1 ? "publicado" : "publicados"}.` +
          (recusados.length ? ` Ficaram de fora: ${recusados.join("; ")}.` : "")
        );
      },
    );
    if (ok) aoFechar();
  }

  return (
    <Painel tipo="video" titulo="Adicionar vídeos" legenda={`Do Vimeo para o fim de ${sub.nome}, já publicados.`} aoFechar={aoFechar}>
      <div className="flex flex-col gap-4">
        <Abas
          abas={[
            { valor: "pasta", rotulo: "Pasta inteira" },
            { valor: "titulo", rotulo: "Pelo título" },
          ]}
          atual={modo}
          aoTrocar={setModo}
        />
        {modo === "pasta" ? (
          <DaPasta modulo={modulo.nome} sub={sub} jaAqui={jaAqui} aoAdicionar={adicionar} />
        ) : (
          <PeloTitulo jaAqui={jaAqui} aoAdicionar={adicionar} />
        )}
      </div>
    </Painel>
  );
}

// --- a pasta inteira -----------------------------------------------------------

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

function DaPasta({ modulo, sub, jaAqui, aoAdicionar }: { modulo: string; sub: SubModulo; jaAqui: Set<string>; aoAdicionar: (videos: ParaEntrar[]) => Promise<void> }) {
  const [pasta, setPasta] = useState<{ pasta: PastaVimeo; caminho: string } | null>(null);
  // A navegação fica aqui em cima: quem volta de uma pasta cai na mesma prateleira de onde saiu.
  const [aberta, setAberta] = useState<string | null>(null);
  const [filtro, setFiltro] = useState("");

  if (pasta) return <VideosDaPastaEscolhida {...pasta} sub={sub} jaAqui={jaAqui} aoTrocar={() => setPasta(null)} aoAdicionar={aoAdicionar} />;
  return <EscolherPasta modulo={modulo} aberta={aberta} aoAbrir={setAberta} filtro={filtro} aoFiltrar={setFiltro} aoEscolher={(p, caminho) => setPasta({ pasta: p, caminho })} />;
}

function EscolherPasta({
  modulo,
  aberta,
  aoAbrir,
  filtro,
  aoFiltrar,
  aoEscolher,
}: {
  modulo: string;
  aberta: string | null;
  aoAbrir: (id: string | null) => void;
  filtro: string;
  aoFiltrar: (texto: string) => void;
  aoEscolher: (pasta: PastaVimeo, caminho: string) => void;
}) {
  const [pastas, setPastas] = useState<PastaVimeo[] | null>(null);
  const [erro, setErro] = useState("");
  const [lendo, setLendo] = useState(true);

  const ler = (deNovo: boolean) => {
    setLendo(true);
    setErro("");
    lerPastas(deNovo)
      .then(setPastas, (ex: Error) => setErro(ex.message))
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

  if (erro) {
    return (
      <Aviso tom="erro">
        {erro} <button type="button" onClick={() => ler(true)} className="font-semibold underline">Tentar de novo</button>
      </Aviso>
    );
  }
  if (!pastas) return <Carregando linhas={4} />;
  if (pastas.length === 0) return <p className="text-[15px] text-suave">O Vimeo não tem nenhuma pasta. Os vídeos soltos entram por &quot;Pelo título&quot;.</p>;

  const termo = semAcento(filtro.trim());
  const pista = pistaDoModulo(modulo);
  const atual = aberta ? porId.get(aberta) : undefined;
  const trilha = atual ? [...ancestrais(atual), atual] : [];
  const noNivel = filhas.get(atual?.id ?? null) ?? [];
  const achadas = termo ? pastas.filter((p) => semAcento(p.nome).includes(termo)).sort((a, b) => ALFABETICA.compare(a.nome, b.nome)) : [];
  const doModulo = !termo && !atual ? pastas.filter((p) => (p.videos ?? 0) > 0 && pista.casa(p.nome)).sort((a, b) => ALFABETICA.compare(caminhoDe(b), caminhoDe(a))) : [];

  const linha = (p: PastaVimeo, comCaminho: boolean) => {
    const dentro = filhas.get(p.id)?.length ?? 0;
    const videos = p.videos ?? 0;
    const total = p.videos_com_subpastas ?? videos;
    const caminho = caminhoDe(p);
    const vazia = !dentro && !videos;
    return (
      <li key={p.id}>
        <button
          type="button"
          disabled={vazia}
          onClick={() => (dentro ? (aoFiltrar(""), aoAbrir(p.id)) : aoEscolher(p, caminho))}
          className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-canvas disabled:cursor-default disabled:opacity-55 disabled:hover:bg-transparent"
        >
          <Folder aria-hidden="true" className="size-[18px] shrink-0 text-suave" strokeWidth={1.8} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium text-tinta">{p.nome}</span>
            {comCaminho && caminho && <span className="block truncate text-[13px] text-suave">{caminho}</span>}
          </span>
          <span className="shrink-0 text-[13px] tabular-nums text-suave">
            {vazia ? "vazia" : dentro ? `${plural(dentro, "pasta")}, ${plural(total, "vídeo")}` : plural(videos, "vídeo")}
          </span>
          {dentro > 0 && <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-suave" />}
        </button>
      </li>
    );
  };

  const lista = "max-h-72 divide-y divide-borda overflow-y-auto rounded-xl border border-borda bg-papel";

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Pasta no Vimeo" className="min-w-60 flex-1">
          {(id) => <input id={id} type="search" value={filtro} onChange={(e) => aoFiltrar(e.target.value)} placeholder="Filtrar pelo nome, ex.: K01" className="campo" />}
        </Campo>
        <Botao variante="texto" tamanho="pequeno" disabled={lendo} onClick={() => ler(true)} title="Ler as pastas do Vimeo de novo">
          <RefreshCw aria-hidden="true" className={`size-4 ${lendo ? "animate-spin" : ""}`} />
          Atualizar
        </Botao>
      </div>

      {termo ? (
        achadas.length ? (
          <>
            <p className="text-[13px] text-suave">{plural(achadas.length, "pasta")} com &quot;{filtro.trim()}&quot; no nome.</p>
            <ul className={lista}>{achadas.slice(0, 80).map((p) => linha(p, true))}</ul>
          </>
        ) : (
          <p className="text-[15px] text-suave">Nenhuma pasta com &quot;{filtro.trim()}&quot; no nome.</p>
        )
      ) : (
        <>
          {doModulo.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <p className="text-[13px] font-semibold text-tinta-2">Com &quot;{pista.texto}&quot; no nome</p>
              <ul className={lista}>{doModulo.slice(0, 12).map((p) => linha(p, true))}</ul>
            </div>
          )}
          <div className="flex flex-col gap-1.5">
            <nav aria-label="Onde você está no Vimeo" className="flex flex-wrap items-center gap-1 text-[13px]">
              <button type="button" onClick={() => aoAbrir(null)} disabled={!atual} className="font-semibold text-acento hover:underline disabled:text-tinta-2 disabled:no-underline">
                {doModulo.length > 0 && !atual ? "Todas as pastas" : "Vimeo"}
              </button>
              {trilha.map((p, i) => (
                <span key={p.id} className="flex items-center gap-1">
                  <ChevronRight aria-hidden="true" className="size-3.5 text-suave" />
                  <button type="button" onClick={() => aoAbrir(p.id)} disabled={i === trilha.length - 1} className="font-semibold text-acento hover:underline disabled:text-tinta-2 disabled:no-underline">
                    {p.nome}
                  </button>
                </span>
              ))}
            </nav>
            <ul className={lista}>
              {atual && (atual.videos ?? 0) > 0 && (
                <li>
                  <button type="button" onClick={() => aoEscolher(atual, caminhoDe(atual))} className="flex w-full items-center gap-3 bg-lilas/50 px-3 py-2.5 text-left hover:bg-lilas">
                    <FolderOpen aria-hidden="true" className="size-[18px] shrink-0 text-acento" strokeWidth={1.8} />
                    <span className="min-w-0 flex-1 text-[15px] font-semibold text-acento-forte">Os vídeos soltos em {atual.nome}</span>
                    <span className="shrink-0 text-[13px] tabular-nums text-acento-forte">{plural(atual.videos ?? 0, "vídeo")}</span>
                  </button>
                </li>
              )}
              {noNivel.map((p) => linha(p, false))}
            </ul>
          </div>
        </>
      )}
    </div>
  );
}

function VideosDaPastaEscolhida({
  pasta,
  caminho,
  sub,
  jaAqui,
  aoTrocar,
  aoAdicionar,
}: {
  pasta: PastaVimeo;
  caminho: string;
  sub: SubModulo;
  jaAqui: Set<string>;
  aoTrocar: () => void;
  aoAdicionar: (videos: ParaEntrar[]) => Promise<void>;
}) {
  const lidos = useDados(() => api.videosDaPasta(pasta.id), [pasta.id]);
  // Só o que o professor mudou à mão; o resto segue a regra de `marcado`.
  const [escolha, setEscolha] = useState<Record<string, boolean>>({});
  const [enviando, setEnviando] = useState(false);

  const videos = lidos.dados?.videos ?? [];
  const novos = videos.filter((v) => !jaAqui.has(v.vimeo_id));
  // Vídeo com aviso é o que o aluno não conseguiria assistir: fica de fora até o professor decidir.
  const marcado = (v: VideoDaPasta) => !jaAqui.has(v.vimeo_id) && (escolha[v.vimeo_id] ?? v.avisos.length === 0);
  const marcados = videos.filter(marcado);
  const todos = novos.length > 0 && marcados.length === novos.length;

  async function adicionar() {
    setEnviando(true);
    await aoAdicionar(marcados.map((v) => ({ vimeo_id: v.vimeo_id, titulo: v.titulo, embed_url: v.embed_url, url: v.url, thumbnail_url: v.thumbnail_url, duracao_segundos: v.duracao_segundos, pasta: pasta.nome })));
    setEnviando(false);
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Botao tamanho="pequeno" onClick={aoTrocar}>
          <ArrowLeft aria-hidden="true" className="size-4" />
          Trocar de pasta
        </Botao>
        <p className="min-w-0 flex-1">
          <span className="block truncate font-semibold text-tinta">{pasta.nome}</span>
          {caminho && <span className="block truncate text-[13px] text-suave">{caminho}</span>}
        </p>
      </div>

      {lidos.carregando && <Carregando linhas={4} />}
      {lidos.erro && <Aviso tom="erro">{lidos.erro}</Aviso>}
      {lidos.dados && videos.length === 0 && <p className="text-[15px] text-suave">Esta pasta não tem vídeos soltos. Se eles estão numa pasta de dentro, volte e abra essa pasta.</p>}
      {lidos.dados && videos.length > 0 && novos.length === 0 && <Aviso tom="info">{videos.length === 1 ? "O vídeo desta pasta já está" : `Os ${videos.length} vídeos desta pasta já estão`} em {sub.nome}.</Aviso>}

      {videos.length > 0 && (
        <>
          {novos.length > 0 && (
            <label className="flex cursor-pointer items-center gap-3 px-3 text-[13px] font-semibold text-tinta-2">
              <input
                type="checkbox"
                checked={todos}
                ref={(el) => {
                  if (el) el.indeterminate = marcados.length > 0 && !todos;
                }}
                onChange={(e) => setEscolha(Object.fromEntries(novos.map((v) => [v.vimeo_id, e.target.checked])))}
                className="size-4 accent-acento"
              />
              <span className="flex-1">
                {marcados.length} de {plural(novos.length, "vídeo novo", "vídeos novos")}
                {novos.length < videos.length && `; ${videos.length - novos.length === 1 ? "1 já está" : `${videos.length - novos.length} já estão`} em ${sub.nome}`}
              </span>
            </label>
          )}
          <ol className="max-h-80 divide-y divide-borda overflow-y-auto rounded-xl border border-borda bg-papel">
            {videos.map((v) => {
              const aqui = jaAqui.has(v.vimeo_id);
              return (
                <li key={v.vimeo_id}>
                  <label className={`flex items-start gap-3 px-3 py-2.5 ${aqui ? "opacity-60" : "cursor-pointer hover:bg-canvas"}`}>
                    <input
                      type="checkbox"
                      disabled={aqui}
                      checked={marcado(v)}
                      onChange={(e) => setEscolha((atual) => ({ ...atual, [v.vimeo_id]: e.target.checked }))}
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
            })}
          </ol>
          {novos.length > 0 && (
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
              <Botao variante="primario" disabled={!marcados.length || enviando} onClick={() => void adicionar()}>
                {enviando ? "Adicionando…" : marcados.length ? `Adicionar ${plural(marcados.length, "vídeo")}` : "Marque os vídeos"}
              </Botao>
              <p className="text-[13px] text-suave">Entram nesta ordem. Depois dá para arrastar, renomear e tirar.</p>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// --- pelo título ---------------------------------------------------------------

function PeloTitulo({ jaAqui, aoAdicionar }: { jaAqui: Set<string>; aoAdicionar: (videos: ParaEntrar[]) => Promise<void> }) {
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

  const quantos = Object.keys(escolhidos).length;

  return (
    <div className="flex flex-col gap-3">
      <form onSubmit={buscar} className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Título no Vimeo" className="min-w-60 flex-1">
          {(id) => <input id={id} value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Ex.: Aula 3, ou Q04" className="campo" />}
        </Campo>
        <Botao type="submit" variante="secundario" disabled={buscando}>{buscando ? "Buscando…" : "Buscar"}</Botao>
      </form>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {resultados && resultados.length === 0 && <p className="text-[15px] text-suave">Nenhum vídeo com esse título. Tente uma parte menor do nome.</p>}
      {resultados && resultados.length > 0 && (
        <ul className="max-h-72 divide-y divide-borda overflow-y-auto rounded-xl border border-borda bg-papel">
          {resultados.map((v) => {
            const aqui = jaAqui.has(v.id);
            return (
              <li key={v.id}>
                <label className={`flex items-center gap-3 px-3 py-2.5 ${aqui ? "opacity-60" : "cursor-pointer hover:bg-canvas"}`}>
                  <input
                    type="checkbox"
                    disabled={aqui}
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
                  {aqui && <Etiqueta>Já está aqui</Etiqueta>}
                  <span className="shrink-0 text-[13px] tabular-nums text-suave">{duracao(v.duracao_segundos)}</span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
      {resultados && resultados.length > 0 && (
        <div>
          <Botao variante="primario" disabled={!quantos} onClick={() => void aoAdicionar(Object.values(escolhidos).map((v) => ({ vimeo_id: v.id, titulo: v.titulo, embed_url: v.embed_url, url: v.url, thumbnail_url: v.thumbnail_url, duracao_segundos: v.duracao_segundos })))}>
            {quantos ? `Adicionar ${plural(quantos, "vídeo")}` : "Marque os vídeos"}
          </Botao>
        </div>
      )}
    </div>
  );
}
