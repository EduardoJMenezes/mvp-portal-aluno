"use client";

// Leitor de material com marcação do aluno.
//
// O pdf.js desenha a página e mais nada: a marcação é uma camada SVG nossa, em
// coordenadas relativas (0 a 1), porque o editor embutido dele grava dentro do
// PDF e ao reabrir vira desenho fixo (ver docs/MATERIAIS.md). Só as páginas
// perto da tela ficam desenhadas — uma apostila de 323 páginas não cabe na
// memória de um tablet.

import { getStroke } from "perfect-freehand";
import type { PDFDocumentProxy } from "pdfjs-dist";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as EventoDePonteiro,
} from "react";
import { Aviso } from "@/components/ui";
import { api, type PaginaAnotada, type Traco } from "@/lib/api";

type Ferramenta = "mao" | "caneta" | "marcatexto" | "texto" | "borracha";
type Tracos = Record<number, Traco[]>;
// O Safari do iPad só tem a versão com prefixo, e é justamente onde a tela cheia
// mais vale a pena.
type ElementoDeTelaCheia = HTMLDivElement & { webkitRequestFullscreen?: () => Promise<void> | void };
type DocumentoDeTelaCheia = Document & {
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => Promise<void> | void;
};

const CORES = ["#111827", "#2563eb", "#dc2626", "#16a34a"];
const CORES_MARCATEXTO = ["#fde047", "#86efac", "#93c5fd", "#fda4af"];
const ESPESSURAS = [0.0025, 0.005, 0.01];
const TAMANHO_DO_TEXTO = 0.018;
const ESPERA_PARA_SALVAR = 1500;
const PAGINAS_VIZINHAS = 1;
// Depois do último sinal da caneta o toque ainda é palma por este tempo. É o que
// impede a mão apoiada de arrastar a página debaixo do traço.
const ESPERA_DA_PALMA = 700;

// --- desenho -----------------------------------------------------------------

function caminhoDoTraco(traco: Traco, largura: number, altura: number): string {
  if (traco.t === "texto") return "";
  const pontos = traco.p.map(([x, y, pressao]) => [x * largura, y * altura, pressao]);
  const contorno = getStroke(pontos, {
    size: traco.larg * largura,
    thinning: traco.t === "caneta" ? 0.6 : 0,
    smoothing: 0.5,
    streamline: 0.5,
    simulatePressure: false,
    last: true,
  });
  if (!contorno.length) return "";
  return `M${contorno.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join("L")}Z`;
}

function TracosDaPagina({ tracos, largura, altura }: { tracos: Traco[]; largura: number; altura: number }) {
  return (
    <>
      {tracos.map((traco, i) =>
        traco.t === "texto" ? (
          <text
            key={i}
            x={traco.x * largura}
            y={traco.y * altura}
            fontSize={traco.tam * largura}
            fill={traco.cor}
            fontFamily="var(--fonte-sans)"
          >
            {traco.txt}
          </text>
        ) : (
          <path
            key={i}
            d={caminhoDoTraco(traco, largura, altura)}
            fill={traco.cor}
            opacity={traco.t === "marcatexto" ? 0.35 : 1}
            style={traco.t === "marcatexto" ? { mixBlendMode: "multiply" } : undefined}
          />
        ),
      )}
    </>
  );
}

// --- uma página --------------------------------------------------------------

type DimensoesDaPagina = { largura: number; altura: number };

type CaixaAberta = { chave: number; x: number; y: number; txt: string; cor: string };

function Pagina({
  documento,
  numero,
  escala,
  desenhar,
  tracos,
  emAndamento,
  escrevendo,
  aoEscrever,
  aoFecharTexto,
  aoDesistirDoTexto,
  aoMedir,
  ...eventos
}: {
  documento: PDFDocumentProxy;
  numero: number;
  escala: number;
  desenhar: boolean;
  tracos: Traco[];
  emAndamento: Traco | null;
  escrevendo: CaixaAberta | null;
  aoEscrever: (txt: string) => void;
  aoFecharTexto: () => void;
  aoDesistirDoTexto: () => void;
  aoMedir: (d: DimensoesDaPagina) => void;
  onPointerDown: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onPointerMove: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onPointerUp: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onPointerCancel: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onLostPointerCapture: (e: EventoDePonteiro<HTMLDivElement>) => void;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [tamanho, setTamanho] = useState<DimensoesDaPagina | null>(null);

  useEffect(() => {
    if (!desenhar) return;
    let cancelado = false;
    let tarefa: { cancel: () => void } | null = null;

    void (async () => {
      const pagina = await documento.getPage(numero);
      const vista = pagina.getViewport({ scale: escala });
      const alvo = canvas.current;
      if (cancelado || !alvo) return;

      const pixels = Math.min(window.devicePixelRatio || 1, 2);
      alvo.width = Math.floor(vista.width * pixels);
      alvo.height = Math.floor(vista.height * pixels);
      alvo.style.width = `${Math.floor(vista.width)}px`;
      alvo.style.height = `${Math.floor(vista.height)}px`;
      const contexto = alvo.getContext("2d");
      if (!contexto) return;
      contexto.scale(pixels, pixels);

      const medida = { largura: vista.width, altura: vista.height };
      setTamanho(medida);
      aoMedir(medida);
      tarefa = pagina.render({ canvas: alvo, canvasContext: contexto, viewport: vista });
      await (tarefa as unknown as { promise: Promise<void> }).promise.catch(() => undefined);
    })();

    return () => {
      cancelado = true;
      tarefa?.cancel();
    };
  }, [documento, numero, escala, desenhar, aoMedir]);

  const medida = tamanho;
  return (
    <div
      data-pagina={numero}
      className="relative mx-auto touch-pan-y select-none bg-papel shadow-suave"
      style={medida ? { width: medida.largura, height: medida.altura } : undefined}
      {...eventos}
    >
      {desenhar ? (
        <canvas ref={canvas} className="block" />
      ) : (
        <div className="flex h-full w-full items-center justify-center text-sm text-apagado">{numero}</div>
      )}
      {medida && (
        <svg
          viewBox={`0 0 ${medida.largura} ${medida.altura}`}
          className="pointer-events-none absolute inset-0 h-full w-full"
          aria-hidden="true"
        >
          <TracosDaPagina tracos={tracos} largura={medida.largura} altura={medida.altura} />
          {emAndamento && (
            <TracosDaPagina tracos={[emAndamento]} largura={medida.largura} altura={medida.altura} />
          )}
        </svg>
      )}
      {medida && escrevendo && (
        <CaixaDeTexto
          key={escrevendo.chave}
          x={escrevendo.x * medida.largura}
          y={escrevendo.y * medida.altura}
          tamanho={TAMANHO_DO_TEXTO * medida.largura}
          cor={escrevendo.cor}
          texto={escrevendo.txt}
          aoEscrever={aoEscrever}
          aoFechar={aoFecharTexto}
          aoDesistir={aoDesistirDoTexto}
        />
      )}
    </div>
  );
}

// --- o leitor ----------------------------------------------------------------

export function LeitorPdf({ materialId }: { materialId: number }) {
  const [documento, setDocumento] = useState<PDFDocumentProxy | null>(null);
  const [erro, setErro] = useState("");
  const [zoom, setZoom] = useState(1);
  const [base, setBase] = useState(1);
  const [visiveis, setVisiveis] = useState<Set<number>>(new Set([1]));
  const [pagina, setPagina] = useState(1);
  const [dimensoes, setDimensoes] = useState<DimensoesDaPagina | null>(null);

  const [ferramenta, ferramentaEscolhida] = useState<Ferramenta>("caneta");
  const [cor, setCor] = useState(CORES[0]);
  const [corMarcatexto, setCorMarcatexto] = useState(CORES_MARCATEXTO[0]);
  const [espessura, setEspessura] = useState(ESPESSURAS[1]);

  const [tracos, setTracos] = useState<Tracos>({});
  const [emAndamento, setEmAndamento] = useState<{ pagina: number; traco: Traco } | null>(null);
  const [escrevendo, setEscrevendo] = useState<{ id: number; pagina: number; x: number; y: number; txt: string } | null>(
    null,
  );
  const [canetaPerto, setCanetaPerto] = useState(false);
  const [cheia, setCheia] = useState(false);
  const [salvando, setSalvando] = useState(false);
  const [salvoEm, setSalvoEm] = useState<Date | null>(null);

  const caixa = useRef<HTMLDivElement>(null);
  const rolagem = useRef<HTMLDivElement>(null);
  const atuais = useRef<Tracos>({});
  const sujas = useRef<Set<number>>(new Set());
  const relogio = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historico = useRef<{ pagina: number; antes: Traco[] }[]>([]);
  const refeitos = useRef<{ pagina: number; antes: Traco[] }[]>([]);
  const textoFechado = useRef(0);
  const desenhando = useRef<{ pagina: number; ponteiro: number; pontos: [number, number, number][] } | null>(null);

  const marcando = ferramenta === "caneta" || ferramenta === "marcatexto" || ferramenta === "borracha";

  atuais.current = tracos;

  // --- carregar documento e anotações ---------------------------------------
  useEffect(() => {
    let vivo = true;
    let leitura: { destroy: () => Promise<void> } | null = null;

    void (async () => {
      try {
        const pdfjs = await import("pdfjs-dist");
        pdfjs.GlobalWorkerOptions.workerSrc = new URL(
          "pdfjs-dist/build/pdf.worker.min.mjs",
          import.meta.url,
        ).toString();
        const tarefa = pdfjs.getDocument({
          url: api.enderecoDoMaterial(materialId),
          // Pede o arquivo em faixas: abrir a página 180 não baixa as 179 antes.
          rangeChunkSize: 1 << 18,
          disableAutoFetch: true,
        });
        leitura = tarefa;
        const doc = await tarefa.promise;
        if (!vivo) {
          void tarefa.destroy();
          return;
        }
        setDocumento(doc);
      } catch (ex) {
        if (vivo) setErro(ex instanceof Error ? ex.message : "Não foi possível abrir o material.");
      }
    })();

    api
      .anotacoes(materialId)
      .then((r) => {
        if (!vivo) return;
        const guardados: Tracos = {};
        for (const [numero, dados] of Object.entries(r.paginas)) {
          guardados[Number(numero)] = (dados as PaginaAnotada).tracos;
        }
        setTracos(guardados);
      })
      .catch(() => undefined);

    return () => {
      vivo = false;
      void leitura?.destroy();
    };
  }, [materialId]);

  // --- salvar sozinho --------------------------------------------------------
  const salvar = useCallback(async () => {
    const pendentes = [...sujas.current];
    if (!pendentes.length) return;
    sujas.current.clear();
    setSalvando(true);
    try {
      for (const numero of pendentes) {
        await api.salvarAnotacao(materialId, numero, atuais.current[numero] ?? []);
      }
      setSalvoEm(new Date());
    } catch (ex) {
      pendentes.forEach((n) => sujas.current.add(n));
      setErro(ex instanceof Error ? ex.message : "Não foi possível salvar o que você riscou.");
    } finally {
      setSalvando(false);
    }
  }, [materialId]);

  const marcarSuja = useCallback(
    (numero: number) => {
      sujas.current.add(numero);
      if (relogio.current) clearTimeout(relogio.current);
      relogio.current = setTimeout(() => void salvar(), ESPERA_PARA_SALVAR);
    },
    [salvar],
  );

  useEffect(() => {
    // Trocar de aba, bloquear o tablet ou fechar a página não pode perder traço.
    const aoSair = () => {
      for (const numero of sujas.current) {
        fetch(`/api/aluno/materiais/${materialId}/anotacoes/${numero}`, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ tracos: atuais.current[numero] ?? [] }),
          credentials: "same-origin",
          keepalive: true,
        }).catch(() => undefined);
      }
      sujas.current.clear();
    };
    const aoEsconder = () => {
      if (document.visibilityState === "hidden") aoSair();
    };
    document.addEventListener("visibilitychange", aoEsconder);
    window.addEventListener("pagehide", aoSair);
    return () => {
      document.removeEventListener("visibilitychange", aoEsconder);
      window.removeEventListener("pagehide", aoSair);
      aoSair();
    };
  }, [materialId]);

  // --- impressão ---------------------------------------------------------------
  useEffect(() => {
    // O atalho nem chega a abrir a caixa de impressão; pelo menu do navegador,
    // a regra de @media print entrega só o recado. Print de tela continua
    // possível — e por isso a marca d'água é a defesa que fica faltando.
    const semImprimir = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "p") e.preventDefault();
    };
    window.addEventListener("keydown", semImprimir);
    return () => window.removeEventListener("keydown", semImprimir);
  }, []);

  // --- palma ------------------------------------------------------------------
  // Enquanto a caneta está na tela — encostada ou pairando —, o toque não vale
  // nada: nem marca (isso é regra em `comecar`) nem rola (isso é o touch-action
  // que este estado liga lá embaixo).
  useEffect(() => {
    const alvo = rolagem.current;
    if (!alvo) return;
    let sono: ReturnType<typeof setTimeout> | null = null;
    const viuCaneta = (e: PointerEvent) => {
      if (e.pointerType !== "pen") return;
      setCanetaPerto(true);
      if (sono) clearTimeout(sono);
      sono = setTimeout(() => setCanetaPerto(false), ESPERA_DA_PALMA);
    };
    alvo.addEventListener("pointerdown", viuCaneta, true);
    alvo.addEventListener("pointermove", viuCaneta, true);
    return () => {
      if (sono) clearTimeout(sono);
      alvo.removeEventListener("pointerdown", viuCaneta, true);
      alvo.removeEventListener("pointermove", viuCaneta, true);
    };
  }, []);

  // --- tela cheia --------------------------------------------------------------
  const alternarTelaCheia = () => {
    const doc = document as DocumentoDeTelaCheia;
    if (cheia) {
      setCheia(false);
      if (document.fullscreenElement || doc.webkitFullscreenElement) {
        void (document.exitFullscreen ?? doc.webkitExitFullscreen)?.call(document);
      }
      return;
    }
    setCheia(true);
    // Onde a API não existe (iPhone, navegador antigo), o modo continua valendo:
    // é o nosso CSS que cobre a tela, só a barra do navegador fica.
    const alvo = caixa.current as ElementoDeTelaCheia | null;
    void Promise.resolve((alvo?.requestFullscreen ?? alvo?.webkitRequestFullscreen)?.call(alvo)).catch(
      () => undefined,
    );
  };

  useEffect(() => {
    const doc = document as DocumentoDeTelaCheia;
    const aoTrocar = () => {
      if (!document.fullscreenElement && !doc.webkitFullscreenElement) setCheia(false);
    };
    document.addEventListener("fullscreenchange", aoTrocar);
    document.addEventListener("webkitfullscreenchange", aoTrocar);
    return () => {
      document.removeEventListener("fullscreenchange", aoTrocar);
      document.removeEventListener("webkitfullscreenchange", aoTrocar);
    };
  }, []);

  // --- que páginas desenhar --------------------------------------------------
  useEffect(() => {
    const alvo = rolagem.current;
    if (!alvo || !documento) return;
    const observador = new IntersectionObserver(
      (entradas) => {
        setVisiveis((atuais) => {
          const proximas = new Set(atuais);
          for (const entrada of entradas) {
            const numero = Number((entrada.target as HTMLElement).dataset.pagina);
            if (entrada.isIntersecting) proximas.add(numero);
            else proximas.delete(numero);
          }
          return proximas;
        });
        const visivel = entradas.filter((e) => e.isIntersecting).map((e) => Number((e.target as HTMLElement).dataset.pagina));
        if (visivel.length) setPagina(Math.min(...visivel));
      },
      { root: alvo, rootMargin: "100% 0px" },
    );
    alvo.querySelectorAll("[data-pagina]").forEach((no) => observador.observe(no));
    return () => observador.disconnect();
  }, [documento]);

  // --- largura que cabe na tela ----------------------------------------------
  useEffect(() => {
    if (!documento) return;
    const medir = async () => {
      const vista = (await documento.getPage(1)).getViewport({ scale: 1 });
      const largura = (rolagem.current?.clientWidth ?? 900) - 24;
      const cabe = Math.max(0.2, largura / vista.width);
      setBase(cabe);
      // O espaço reservado de cada página nasce com a medida da primeira: sem
      // isso as 323 ficariam com altura zero, todas dentro da tela ao mesmo tempo.
      setDimensoes({ largura: vista.width * cabe * zoom, altura: vista.height * cabe * zoom });
    };
    void medir();
    window.addEventListener("resize", medir);
    return () => window.removeEventListener("resize", medir);
  }, [documento, zoom]);

  // --- marcar ----------------------------------------------------------------
  const pontoDaPagina = (e: EventoDePonteiro<HTMLDivElement>): [number, number, number] => {
    const caixa = e.currentTarget.getBoundingClientRect();
    return [
      (e.clientX - caixa.left) / caixa.width,
      (e.clientY - caixa.top) / caixa.height,
      e.pressure > 0 ? e.pressure : 0.5,
    ];
  };

  const guardar = (numero: number, proximos: Traco[]) => {
    historico.current = [...historico.current.slice(-29), { pagina: numero, antes: atuais.current[numero] ?? [] }];
    refeitos.current = [];
    setTracos((tudo) => ({ ...tudo, [numero]: proximos }));
    marcarSuja(numero);
  };

  const apagarEm = (numero: number, [x, y]: [number, number, number]) => {
    const perto = (traco: Traco) => {
      // Alcance generoso: a borracha some com o traço inteiro, e errar por um
      // dedo de distância seria pior do que apagar de mais.
      if (traco.t === "texto") return Math.hypot(traco.x - x, traco.y - y) < 0.05;
      return traco.p.some(([px, py]) => Math.hypot(px - x, py - y) < 0.03);
    };
    const restantes = (atuais.current[numero] ?? []).filter((t) => !perto(t));
    if (restantes.length !== (atuais.current[numero] ?? []).length) guardar(numero, restantes);
  };

  // Fecha a caixa de texto aberta: `guardando` diz se o que está escrito vira
  // anotação. A chave evita gravar duas vezes — o blur chega depois do clique
  // que já fechou a caixa.
  const fecharTexto = (guardando: boolean) => {
    const aberta = escrevendo;
    setEscrevendo(null);
    if (!aberta || textoFechado.current === aberta.id) return;
    textoFechado.current = aberta.id;
    if (!guardando || !aberta.txt.trim()) return;
    guardar(aberta.pagina, [
      ...(atuais.current[aberta.pagina] ?? []),
      { t: "texto", cor, x: aberta.x, y: aberta.y, tam: TAMANHO_DO_TEXTO, txt: aberta.txt.trim() },
    ]);
  };

  const comecar = (numero: number) => (e: EventoDePonteiro<HTMLDivElement>) => {
    if (ferramenta === "mao") return;
    // Palm rejection: com caneta, marca-texto ou borracha na mão, o que é toque é
    // palma — só rola a página, nunca marca. Dedo desenhando fica para a Mão.
    if (marcando && e.pointerType === "touch") return;
    // Um ponteiro de cada vez: a palma que encosta no meio do traço não o rouba.
    if (desenhando.current) return;

    if (ferramenta === "texto") {
      // Sem isto o mousedown que vem atrás devolve o foco para a página e a
      // caixa recém-aberta fecha no mesmo instante, antes de dar para escrever.
      e.preventDefault();
      const [x, y] = pontoDaPagina(e);
      fecharTexto(true); // o que estava escrito em outro ponto vira anotação
      setEscrevendo({ id: Date.now(), pagina: numero, x, y, txt: "" });
      return;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    const ponto = pontoDaPagina(e);
    if (ferramenta === "borracha") {
      apagarEm(numero, ponto);
      desenhando.current = { pagina: numero, ponteiro: e.pointerId, pontos: [] };
      return;
    }
    desenhando.current = { pagina: numero, ponteiro: e.pointerId, pontos: [ponto] };
    setEmAndamento({ pagina: numero, traco: tracoEmAndamento([ponto]) });
  };

  const tracoEmAndamento = (pontos: [number, number, number][]): Traco =>
    ferramenta === "marcatexto"
      ? { t: "marcatexto", cor: corMarcatexto, larg: 0.02, p: pontos }
      : { t: "caneta", cor, larg: espessura, p: pontos };

  const mover = (numero: number) => (e: EventoDePonteiro<HTMLDivElement>) => {
    const atual = desenhando.current;
    if (!atual || atual.pagina !== numero || atual.ponteiro !== e.pointerId) return;
    const ponto = pontoDaPagina(e);
    if (ferramenta === "borracha") {
      apagarEm(numero, ponto);
      return;
    }
    const ultimo = atual.pontos[atual.pontos.length - 1];
    if (ultimo && Math.hypot(ultimo[0] - ponto[0], ultimo[1] - ponto[1]) < 0.001) return;
    atual.pontos.push(ponto);
    setEmAndamento({ pagina: numero, traco: tracoEmAndamento([...atual.pontos]) });
  };

  const terminar = (numero: number) => (e: EventoDePonteiro<HTMLDivElement>) => {
    const atual = desenhando.current;
    if (!atual || atual.pagina !== numero || atual.ponteiro !== e.pointerId) return;
    desenhando.current = null;
    setEmAndamento(null);
    if (atual.pontos.length === 0) return;
    const arredondado = atual.pontos.map(
      ([x, y, p]) => [Number(x.toFixed(4)), Number(y.toFixed(4)), Number(p.toFixed(2))] as [number, number, number],
    );
    guardar(numero, [...(atuais.current[numero] ?? []), tracoEmAndamento(arredondado)]);
  };

  const cancelar = (e: EventoDePonteiro<HTMLDivElement>) => {
    // O navegador tomou o gesto para si (rolagem, gesto do sistema). Sem isto o
    // traço fica preso e nenhum outro começa.
    if (desenhando.current?.ponteiro !== e.pointerId) return;
    desenhando.current = null;
    setEmAndamento(null);
  };

  const desfazer = () => {
    const passo = historico.current.pop();
    if (!passo) return;
    refeitos.current.push({ pagina: passo.pagina, antes: atuais.current[passo.pagina] ?? [] });
    setTracos((tudo) => ({ ...tudo, [passo.pagina]: passo.antes }));
    marcarSuja(passo.pagina);
  };

  const refazer = () => {
    const passo = refeitos.current.pop();
    if (!passo) return;
    historico.current.push({ pagina: passo.pagina, antes: atuais.current[passo.pagina] ?? [] });
    setTracos((tudo) => ({ ...tudo, [passo.pagina]: passo.antes }));
    marcarSuja(passo.pagina);
  };

  const foco = visiveis.size ? Math.min(...visiveis) : 1;
  const total = documento?.numPages ?? 0;
  const numeros = useMemo(() => Array.from({ length: total }, (_, i) => i + 1), [total]);
  const escala = base * zoom;

  return (
    <div
      ref={caixa}
      className={
        cheia ? "fixed inset-0 z-50 flex flex-col bg-canvas" : "flex h-[calc(100dvh-3.5rem)] flex-col"
      }
    >
      <Barra
        ferramenta={ferramenta}
        aoTrocarFerramenta={(f) => {
          ferramentaEscolhida(f);
          fecharTexto(true); // trocar de ferramenta guarda o que estava escrito
        }}
        cor={ferramenta === "marcatexto" ? corMarcatexto : cor}
        cores={ferramenta === "marcatexto" ? CORES_MARCATEXTO : CORES}
        aoTrocarCor={ferramenta === "marcatexto" ? setCorMarcatexto : setCor}
        espessura={espessura}
        aoTrocarEspessura={setEspessura}
        aoDesfazer={desfazer}
        aoRefazer={refazer}
        zoom={zoom}
        aoZoom={setZoom}
        pagina={pagina}
        total={total}
        cheia={cheia}
        aoTelaCheia={alternarTelaCheia}
        salvando={salvando}
        salvoEm={salvoEm}
      />

      {erro && (
        <div className="px-4 pt-3">
          <Aviso tom="erro">{erro}</Aviso>
        </div>
      )}

      <div
        ref={rolagem}
        className="flex-1 overflow-auto overscroll-contain bg-canvas px-3 py-4"
        // Caneta na tela: o toque para de rolar. É a outra metade do palm
        // rejection — sem isto a mão apoiada arrasta a página no meio da frase.
        style={marcando && canetaPerto ? { touchAction: "none" } : undefined}
      >
        <div className="flex w-fit min-w-full flex-col items-center gap-4">
          {!documento && !erro && <p className="py-12 text-[15px] text-suave">Abrindo o material…</p>}
          {documento &&
            numeros.map((numero) => {
              // Janela curta em volta da página em foco. Vale mais do que confiar
              // no observador: enquanto ele não mediu nada, tudo parece visível.
              const desenhar = numero >= foco - PAGINAS_VIZINHAS && numero <= foco + PAGINAS_VIZINHAS + 1;
              return (
                <div
                  key={numero}
                  data-pagina={numero}
                  style={
                    dimensoes && !desenhar
                      ? { width: dimensoes.largura, height: dimensoes.altura }
                      : undefined
                  }
                >
                  <Pagina
                    documento={documento}
                    numero={numero}
                    escala={escala}
                    desenhar={desenhar}
                    tracos={tracos[numero] ?? []}
                    emAndamento={emAndamento?.pagina === numero ? emAndamento.traco : null}
                    escrevendo={
                      escrevendo?.pagina === numero
                        ? { chave: escrevendo.id, x: escrevendo.x, y: escrevendo.y, txt: escrevendo.txt, cor }
                        : null
                    }
                    aoEscrever={(txt) => setEscrevendo((c) => (c ? { ...c, txt } : c))}
                    aoFecharTexto={() => fecharTexto(true)}
                    aoDesistirDoTexto={() => fecharTexto(false)}
                    aoMedir={setDimensoes}
                    onPointerDown={comecar(numero)}
                    onPointerMove={mover(numero)}
                    onPointerUp={terminar(numero)}
                    onPointerCancel={cancelar}
                    // Solta o traço se a página sumir debaixo dele: um traço
                    // preso aqui trava todos os próximos.
                    onLostPointerCapture={cancelar}
                  />
                </div>
              );
            })}
        </div>
      </div>
    </div>
  );
}

// --- barra de ferramentas ----------------------------------------------------

const FERRAMENTAS: { valor: Ferramenta; rotulo: string }[] = [
  { valor: "mao", rotulo: "Mão" },
  { valor: "caneta", rotulo: "Caneta" },
  { valor: "marcatexto", rotulo: "Marca-texto" },
  { valor: "texto", rotulo: "Texto" },
  { valor: "borracha", rotulo: "Borracha" },
];

function Barra({
  ferramenta,
  aoTrocarFerramenta,
  cor,
  cores,
  aoTrocarCor,
  espessura,
  aoTrocarEspessura,
  aoDesfazer,
  aoRefazer,
  zoom,
  aoZoom,
  pagina,
  total,
  cheia,
  aoTelaCheia,
  salvando,
  salvoEm,
}: {
  ferramenta: Ferramenta;
  aoTrocarFerramenta: (f: Ferramenta) => void;
  cor: string;
  cores: string[];
  aoTrocarCor: (c: string) => void;
  espessura: number;
  aoTrocarEspessura: (e: number) => void;
  aoDesfazer: () => void;
  aoRefazer: () => void;
  zoom: number;
  aoZoom: (z: number) => void;
  pagina: number;
  total: number;
  cheia: boolean;
  aoTelaCheia: () => void;
  salvando: boolean;
  salvoEm: Date | null;
}) {
  const risca = ferramenta === "caneta" || ferramenta === "marcatexto" || ferramenta === "texto";
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-borda bg-papel px-3 py-2">
      <div role="radiogroup" aria-label="Ferramenta" className="flex gap-1">
        {FERRAMENTAS.map((f) => (
          <button
            key={f.valor}
            type="button"
            role="radio"
            aria-checked={ferramenta === f.valor}
            onClick={() => aoTrocarFerramenta(f.valor)}
            className={`rounded-full px-3 py-1.5 text-sm font-semibold transition-colors ${
              ferramenta === f.valor ? "bg-acento text-white" : "text-suave hover:bg-canvas hover:text-tinta"
            }`}
          >
            {f.rotulo}
          </button>
        ))}
      </div>

      {risca && (
        <>
          <div role="radiogroup" aria-label="Cor" className="flex gap-1.5">
            {cores.map((c) => (
              <button
                key={c}
                type="button"
                role="radio"
                aria-checked={cor === c}
                aria-label={`Cor ${c}`}
                onClick={() => aoTrocarCor(c)}
                style={{ background: c }}
                className={`size-6 rounded-full border-2 ${cor === c ? "border-tinta" : "border-transparent"}`}
              />
            ))}
          </div>
          {ferramenta === "caneta" && (
            <div role="radiogroup" aria-label="Espessura" className="flex items-center gap-1.5">
              {ESPESSURAS.map((e, i) => (
                <button
                  key={e}
                  type="button"
                  role="radio"
                  aria-checked={espessura === e}
                  aria-label={["Fina", "Média", "Grossa"][i]}
                  onClick={() => aoTrocarEspessura(e)}
                  className={`flex size-7 items-center justify-center rounded-full ${espessura === e ? "bg-lilas" : "hover:bg-canvas"}`}
                >
                  <span className="rounded-full bg-tinta" style={{ width: 4 + i * 4, height: 4 + i * 4 }} />
                </button>
              ))}
            </div>
          )}
        </>
      )}

      <div className="flex gap-1">
        <button type="button" onClick={aoDesfazer} className="rounded-full px-3 py-1.5 text-sm font-semibold text-suave hover:bg-canvas hover:text-tinta">
          Desfazer
        </button>
        <button type="button" onClick={aoRefazer} className="rounded-full px-3 py-1.5 text-sm font-semibold text-suave hover:bg-canvas hover:text-tinta">
          Refazer
        </button>
      </div>

      <div className="flex items-center gap-1">
        <button type="button" aria-label="Diminuir" onClick={() => aoZoom(Math.max(0.5, Number((zoom - 0.25).toFixed(2))))} className="size-7 rounded-full text-lg text-suave hover:bg-canvas">
          −
        </button>
        <span className="w-12 text-center text-sm tabular-nums text-suave">{Math.round(zoom * 100)}%</span>
        <button type="button" aria-label="Aumentar" onClick={() => aoZoom(Math.min(3, Number((zoom + 0.25).toFixed(2))))} className="size-7 rounded-full text-lg text-suave hover:bg-canvas">
          +
        </button>
      </div>

      <button
        type="button"
        aria-pressed={cheia}
        onClick={aoTelaCheia}
        className="rounded-full px-3 py-1.5 text-sm font-semibold text-suave hover:bg-canvas hover:text-tinta"
      >
        {cheia ? "Sair da tela cheia" : "Tela cheia"}
      </button>

      <p className="ml-auto flex items-center gap-3 text-[13px] text-suave">
        <span className="tabular-nums">
          Página {pagina}
          {total ? ` de ${total}` : ""}
        </span>
        <span aria-live="polite">
          {salvando ? "Salvando…" : salvoEm ? `Salvo ${salvoEm.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}` : "Tudo salvo"}
        </span>
      </p>
    </div>
  );
}

// A caixa nasce onde a pessoa tocou, do tamanho e da cor que o texto vai ter, e
// some deixando a anotação no mesmo lugar — nada de campo no rodapé.
function CaixaDeTexto({
  x,
  y,
  tamanho,
  cor,
  texto,
  aoEscrever,
  aoFechar,
  aoDesistir,
}: {
  x: number;
  y: number;
  tamanho: number;
  cor: string;
  texto: string;
  aoEscrever: (txt: string) => void;
  aoFechar: () => void;
  aoDesistir: () => void;
}) {
  return (
    <form
      className="absolute z-10"
      // O SVG desenha o texto sobre a linha de base; a caixa sobe para a letra
      // cair onde o dedo encostou.
      style={{ left: x, top: y, transform: "translateY(-0.85em)" }}
      onPointerDown={(e) => e.stopPropagation()}
      onSubmit={(e) => {
        e.preventDefault();
        aoFechar();
      }}
    >
      <input
        autoFocus
        value={texto}
        onChange={(e) => aoEscrever(e.target.value)}
        onBlur={aoFechar}
        onKeyDown={(e) => {
          // Enter fecha aqui mesmo: em formulário de um campo só, o envio
          // implícito do navegador é promessa que nem todo teclado cumpre.
          if (e.key === "Enter") {
            e.preventDefault();
            aoFechar();
          }
          if (e.key === "Escape") aoDesistir();
        }}
        placeholder="Escreva aqui"
        aria-label="Texto da anotação"
        style={{ fontSize: tamanho, color: cor, width: `${Math.max(10, texto.length + 6)}ch` }}
        className="rounded border border-acento bg-papel/95 px-1 py-0.5 leading-tight shadow-suave outline-none"
      />
    </form>
  );
}
