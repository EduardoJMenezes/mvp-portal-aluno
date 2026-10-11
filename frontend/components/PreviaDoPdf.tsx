"use client";

// O PDF só para conferir, por cima da página: as folhas desenhadas pelo pdf.js, uma embaixo da
// outra, na largura do modal. Não é o leitor do aluno (LeitorPdf): aqui não há caneta, marcação
// nem "onde parei", e nada é gravado. Serve a quem monta o curso e quer ver qual é o arquivo.

import type { PDFDocumentProxy } from "pdfjs-dist";
import { useEffect, useRef, useState } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Aviso, Botao, BotaoLink, Carregamento, Esqueleto } from "@/components/ui";
import { api } from "@/lib/api";
import { plural } from "@/lib/formato";

/** A folha A4 em pé, até a primeira página dizer a proporção de verdade. */
const PROPORCAO_PADRAO = 1.414;
/** Quanto antes de entrar na tela a página começa a ser desenhada. */
const FOLGA = "600px 0px";

/** O ancestral que rola (o corpo do modal): é contra ele que se mede o que está à vista. */
function quemRola(el: HTMLElement | null): HTMLElement | null {
  for (let pai = el?.parentElement ?? null; pai; pai = pai.parentElement) {
    if (/(auto|scroll)/.test(getComputedStyle(pai).overflowY)) return pai;
  }
  return null;
}

/** Uma página. Só é desenhada perto da tela, e é apagada ao se afastar: apostila grande não cabe na memória. */
function Folha({ documento, numero, largura, proporcao, aoMedir }: { documento: PDFDocumentProxy; numero: number; largura: number; proporcao: number; aoMedir: (proporcao: number) => void }) {
  const moldura = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const [perto, setPerto] = useState(numero === 1);

  useEffect(() => {
    const el = moldura.current;
    if (!el) return;
    const observador = new IntersectionObserver(([entrada]) => setPerto(entrada.isIntersecting), { root: quemRola(el), rootMargin: FOLGA });
    observador.observe(el);
    return () => observador.disconnect();
  }, []);

  useEffect(() => {
    const alvo = canvas.current;
    if (!alvo || !largura) return;
    if (!perto) {
      // Longe da tela, a folha devolve a memória e fica só o espaço dela.
      alvo.width = 0;
      alvo.height = 0;
      return;
    }
    let cancelado = false;
    let tarefa: { cancel: () => void } | null = null;
    void (async () => {
      try {
        const pagina = await documento.getPage(numero);
        if (cancelado) return;
        const natural = pagina.getViewport({ scale: 1 });
        aoMedir(natural.height / natural.width);
        const vista = pagina.getViewport({ scale: largura / natural.width });
        const pixels = Math.min(window.devicePixelRatio || 1, 2);
        alvo.width = Math.floor(vista.width * pixels);
        alvo.height = Math.floor(vista.height * pixels);
        const contexto = alvo.getContext("2d");
        if (!contexto) return;
        const desenho = pagina.render({ canvas: alvo, canvasContext: contexto, viewport: vista, transform: pixels === 1 ? undefined : [pixels, 0, 0, pixels, 0, 0] });
        tarefa = desenho;
        await desenho.promise;
      } catch {
        // Cancelada ao rolar, ou página ilegível: fica o espaço em branco dela.
      }
    })();
    return () => {
      cancelado = true;
      tarefa?.cancel();
    };
    // `aoMedir` é estável o bastante: só a primeira medida muda alguma coisa.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documento, numero, largura, perto]);

  return (
    <div ref={moldura} className="relative overflow-hidden rounded-md bg-white shadow-[0_1px_4px_rgb(10_37_80/0.14)]" style={{ width: largura, height: Math.round(largura * proporcao) }}>
      <canvas ref={canvas} aria-label={`Página ${numero}`} className="size-full" />
      <span className="absolute bottom-1.5 right-2 rounded bg-tinta/55 px-1.5 py-0.5 text-[11px] tabular-nums text-white">{numero}</span>
    </div>
  );
}

/** As páginas do material, na largura que houver. */
export function PreviaDoPdf({ materialId, aoContar }: { materialId: number; aoContar?: (paginas: number) => void }) {
  const [documento, setDocumento] = useState<PDFDocumentProxy | null>(null);
  const [erro, setErro] = useState("");
  const [largura, setLargura] = useState(0);
  const [proporcao, setProporcao] = useState(PROPORCAO_PADRAO);
  const caixa = useRef<HTMLDivElement>(null);
  const contar = useRef(aoContar);
  useEffect(() => {
    contar.current = aoContar;
  });

  useEffect(() => {
    let vivo = true;
    let leitura: { destroy: () => Promise<void> } | null = null;
    void (async () => {
      try {
        const pdfjs = await import("pdfjs-dist");
        pdfjs.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).toString();
        // Em faixas, como no leitor: ver a primeira página não baixa o arquivo inteiro.
        const tarefa = pdfjs.getDocument({ url: api.enderecoDoMaterial(materialId), rangeChunkSize: 1 << 18, disableStream: true, disableAutoFetch: true });
        leitura = tarefa;
        const doc = await tarefa.promise;
        if (!vivo) return void tarefa.destroy();
        setDocumento(doc);
        contar.current?.(doc.numPages);
      } catch (ex) {
        if (vivo) setErro(ex instanceof Error ? ex.message : "Não foi possível abrir o PDF.");
      }
    })();
    return () => {
      vivo = false;
      void leitura?.destroy();
    };
  }, [materialId]);

  useEffect(() => {
    const el = caixa.current;
    if (!el) return;
    const medir = () => setLargura(Math.floor(el.clientWidth));
    medir();
    const observador = new ResizeObserver(medir);
    observador.observe(el);
    return () => observador.disconnect();
  }, []);

  return (
    <div ref={caixa} className="flex flex-col items-center gap-3">
      {erro ? (
        <Aviso tom="erro" className="w-full">{erro}</Aviso>
      ) : !documento ? (
        <Carregamento rotulo="Abrindo o PDF" className="w-full">
          <Esqueleto className="aspect-[1/1.414] w-full rounded-md" />
        </Carregamento>
      ) : (
        largura > 0 &&
        Array.from({ length: documento.numPages }, (_, i) => (
          <Folha key={i + 1} documento={documento} numero={i + 1} largura={largura} proporcao={proporcao} aoMedir={(p) => setProporcao((atual) => (Math.abs(atual - p) > 0.001 && i === 0 ? p : atual))} />
        ))
      )}
    </div>
  );
}

/** O PDF num modal, por cima de onde a pessoa está. Quem usa monta só quando abre. */
export function VerPdf({ material, aoFechar }: { material: { material_id: number; titulo: string }; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const [paginas, setPaginas] = useState<number | null>(null);
  return (
    <Modal
      {...saida}
      aoFechar={saida.fechar}
      tamanho="grande"
      titulo={material.titulo}
      legenda={paginas ? plural(paginas, "página") : undefined}
      semMargem
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <BotaoLink outraAba href={`/materiais/ler/?id=${material.material_id}`}>Abrir no leitor</BotaoLink>
          <Botao onClick={saida.fechar}>Fechar</Botao>
        </div>
      }
    >
      <div className="bg-canvas px-4 py-4 sm:px-6">
        <PreviaDoPdf materialId={material.material_id} aoContar={setPaginas} />
      </div>
    </Modal>
  );
}
