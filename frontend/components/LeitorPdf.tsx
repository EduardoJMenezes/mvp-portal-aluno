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

const CORES = ["#111827", "#2563eb", "#dc2626", "#16a34a"];
const CORES_MARCATEXTO = ["#fde047", "#86efac", "#93c5fd", "#fda4af"];
const ESPESSURAS = [0.0025, 0.005, 0.01];
const ESPERA_PARA_SALVAR = 1500;
const PAGINAS_VIZINHAS = 1;

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

function Pagina({
  documento,
  numero,
  escala,
  desenhar,
  tracos,
  emAndamento,
  aoMedir,
  ...eventos
}: {
  documento: PDFDocumentProxy;
  numero: number;
  escala: number;
  desenhar: boolean;
  tracos: Traco[];
  emAndamento: Traco | null;
  aoMedir: (d: DimensoesDaPagina) => void;
  onPointerDown: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onPointerMove: (e: EventoDePonteiro<HTMLDivElement>) => void;
  onPointerUp: (e: EventoDePonteiro<HTMLDivElement>) => void;
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
  const [escrevendo, setEscrevendo] = useState<{ pagina: number; x: number; y: number } | null>(null);
  const [salvando, setSalvando] = useState(false);
  const [salvoEm, setSalvoEm] = useState<Date | null>(null);

  const rolagem = useRef<HTMLDivElement>(null);
  const atuais = useRef<Tracos>({});
  const sujas = useRef<Set<number>>(new Set());
  const relogio = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historico = useRef<{ pagina: number; antes: Traco[] }[]>([]);
  const refeitos = useRef<{ pagina: number; antes: Traco[] }[]>([]);
  const canetaVista = useRef(false);
  const desenhando = useRef<{ pagina: number; pontos: [number, number, number][] } | null>(null);

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

  const comecar = (numero: number) => (e: EventoDePonteiro<HTMLDivElement>) => {
    if (e.pointerType === "pen") canetaVista.current = true;
    // Com caneta em uso, o dedo volta a ser só rolagem e zoom.
    const soRola = ferramenta === "mao" || (e.pointerType === "touch" && canetaVista.current);
    if (soRola) return;

    if (ferramenta === "texto") {
      const [x, y] = pontoDaPagina(e);
      setEscrevendo({ pagina: numero, x, y });
      return;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    const ponto = pontoDaPagina(e);
    if (ferramenta === "borracha") {
      apagarEm(numero, ponto);
      desenhando.current = { pagina: numero, pontos: [] };
      return;
    }
    desenhando.current = { pagina: numero, pontos: [ponto] };
    setEmAndamento({ pagina: numero, traco: tracoEmAndamento([ponto]) });
  };

  const tracoEmAndamento = (pontos: [number, number, number][]): Traco =>
    ferramenta === "marcatexto"
      ? { t: "marcatexto", cor: corMarcatexto, larg: 0.02, p: pontos }
      : { t: "caneta", cor, larg: espessura, p: pontos };

  const mover = (numero: number) => (e: EventoDePonteiro<HTMLDivElement>) => {
    const atual = desenhando.current;
    if (!atual || atual.pagina !== numero) return;
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

  const terminar = (numero: number) => () => {
    const atual = desenhando.current;
    desenhando.current = null;
    setEmAndamento(null);
    if (!atual || atual.pagina !== numero || atual.pontos.length === 0) return;
    const arredondado = atual.pontos.map(
      ([x, y, p]) => [Number(x.toFixed(4)), Number(y.toFixed(4)), Number(p.toFixed(2))] as [number, number, number],
    );
    guardar(numero, [...(atuais.current[numero] ?? []), tracoEmAndamento(arredondado)]);
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
    <div className="flex h-[calc(100dvh-3.5rem)] flex-col">
      <Barra
        ferramenta={ferramenta}
        aoTrocarFerramenta={(f) => {
          ferramentaEscolhida(f);
          setEscrevendo(null); // trocar de ferramenta fecha a caixa de texto aberta
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
        salvando={salvando}
        salvoEm={salvoEm}
      />

      {erro && (
        <div className="px-4 pt-3">
          <Aviso tom="erro">{erro}</Aviso>
        </div>
      )}

      <div ref={rolagem} className="flex-1 overflow-auto overscroll-contain bg-canvas px-3 py-4">
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
                    aoMedir={setDimensoes}
                    onPointerDown={comecar(numero)}
                    onPointerMove={mover(numero)}
                    onPointerUp={terminar(numero)}
                  />
                </div>
              );
            })}
        </div>
      </div>

      {escrevendo && (
        <CaixaDeTexto
          aoConfirmar={(txt) => {
            if (txt.trim()) {
              guardar(escrevendo.pagina, [
                ...(atuais.current[escrevendo.pagina] ?? []),
                { t: "texto", cor, x: escrevendo.x, y: escrevendo.y, tam: 0.018, txt: txt.trim() },
              ]);
            }
            setEscrevendo(null);
          }}
          aoCancelar={() => setEscrevendo(null)}
        />
      )}
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
  salvando: boolean;
  salvoEm: Date | null;
}) {
  const risca = ferramenta === "caneta" || ferramenta === "marcatexto";
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

function CaixaDeTexto({ aoConfirmar, aoCancelar }: { aoConfirmar: (txt: string) => void; aoCancelar: () => void }) {
  const [texto, setTexto] = useState("");
  return (
    <div className="border-t border-borda bg-papel px-3 py-2">
      <form
        className="mx-auto flex max-w-2xl gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          aoConfirmar(texto);
        }}
      >
        <input
          autoFocus
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && aoCancelar()}
          placeholder="Escreva e tecle Enter"
          className="campo flex-1"
          aria-label="Texto da anotação"
        />
        <button type="submit" className="rounded-full bg-acento px-4 py-2 text-sm font-semibold text-white">
          Pôr na página
        </button>
      </form>
    </div>
  );
}
