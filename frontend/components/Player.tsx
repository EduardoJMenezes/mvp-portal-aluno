"use client";

import { useEffect, useRef, useState } from "react";
import type { Video } from "@/lib/api";
import { duracao } from "@/lib/formato";
import { Botao } from "./ui";

// O embed_url vem como o Vimeo devolveu: sem o hash de privacidade, o player
// recusa tocar vídeo unlisted (docs/VIMEO.md). Vídeo bloqueado chega só com o
// nome e o aviso — não há o que o devtools libere.

const VIMEO = "https://player.vimeo.com";
/** De quanto em quanto tempo o player conta onde o aluno está. */
const INTERVALO_MS = 15_000;
/** O mesmo limiar do backend: passou daqui, avisa na hora, para o "assistida" aparecer sem esperar. */
const PERTO_DO_FIM = 0.9;

/**
 * `inicio`: de onde o vídeo começa, em segundos (o aluno continua de onde parou).
 * `aoAssistir`: chamado de tempos em tempos, ao pausar, ao terminar e ao sair, com a posição e a
 * duração em segundos. Quem usa os dois dá uma `key` por aula: trocar de aula tem que desmontar o
 * player, para o último aviso sair com a aula certa.
 */
export function Player({ video, inicio = 0, aoAssistir }: { video: Video; inicio?: number; aoAssistir?: (posicao: number, duracao: number) => void }) {
  const quadro = useRef<HTMLIFrameElement>(null);
  const avisar = useRef(aoAssistir);
  useEffect(() => {
    avisar.current = aoAssistir;
  });
  // O ponto de partida é o do primeiro desenho: se ele mudasse depois, o `src` mudaria junto e o
  // vídeo recarregaria no meio da aula. Outro vídeo no mesmo player começa do início.
  const [partida] = useState(() => ({ video: video.id, inicio: Math.floor(inicio) }));
  const comeco = partida.video === video.id ? partida.inicio : 0;
  const src = video.embed_url && comeco > 0 ? `${video.embed_url}#t=${comeco}s` : video.embed_url;
  const ouve = !!aoAssistir && !!src?.startsWith(VIMEO);

  // O player do Vimeo conversa por postMessage: pedimos os eventos e ele manda { seconds, duration }.
  useEffect(() => {
    if (!ouve) return;
    const janela = () => quadro.current?.contentWindow;
    let ultima: { posicao: number; duracao: number } | null = null;
    let pendente = false;
    let enviadoEm = 0;
    let pertoDoFim = false;

    const enviar = () => {
      if (!ultima || !pendente) return;
      pendente = false;
      enviadoEm = Date.now();
      avisar.current?.(Math.floor(ultima.posicao), Math.round(ultima.duracao));
    };
    const pedirEventos = () => {
      for (const evento of ["timeupdate", "pause", "ended"]) janela()?.postMessage(JSON.stringify({ method: "addEventListener", value: evento }), VIMEO);
    };
    const ouvir = (e: MessageEvent) => {
      if (e.origin !== VIMEO || e.source !== janela()) return;
      let recado: { event?: string; data?: { seconds?: number; duration?: number } };
      try {
        recado = typeof e.data === "string" ? JSON.parse(e.data) : e.data;
      } catch {
        return;
      }
      if (recado?.event === "ready") return pedirEventos();
      const { seconds, duration } = recado?.data ?? {};
      if (typeof seconds !== "number" || typeof duration !== "number" || duration <= 0) return;
      if (recado.event === "ended") {
        ultima = { posicao: duration, duracao: duration };
        pendente = true;
        enviar();
      } else if (recado.event === "pause") {
        ultima = { posicao: seconds, duracao: duration };
        pendente = true;
        enviar();
      } else if (recado.event === "timeupdate") {
        ultima = { posicao: seconds, duracao: duration };
        pendente = true;
        const agoraPerto = seconds >= duration * PERTO_DO_FIM;
        if (Date.now() - enviadoEm >= INTERVALO_MS || (agoraPerto && !pertoDoFim)) enviar();
        pertoDoFim = agoraPerto;
      }
    };
    const aoEsconder = () => {
      if (document.visibilityState === "hidden") enviar();
    };

    const elemento = quadro.current;
    window.addEventListener("message", ouvir);
    document.addEventListener("visibilitychange", aoEsconder);
    // O "ready" pode passar antes de começarmos a ouvir: ao carregar, pedimos de novo (não faz mal).
    elemento?.addEventListener("load", pedirEventos);
    return () => {
      enviar();
      window.removeEventListener("message", ouvir);
      document.removeEventListener("visibilitychange", aoEsconder);
      elemento?.removeEventListener("load", pedirEventos);
    };
  }, [ouve]);

  if (video.bloqueado || !src) return <VideoBloqueado titulo={video.titulo} motivo={video.motivo} />;
  return (
    <div className="aspect-video w-full overflow-hidden rounded-cartao bg-tinta">
      <iframe
        ref={quadro}
        src={src}
        title={video.titulo}
        allow="fullscreen; picture-in-picture"
        allowFullScreen
        className="size-full border-0"
      />
    </div>
  );
}

export function VideoBloqueado({ titulo, motivo }: { titulo: string; motivo?: string }) {
  return (
    <div className="relative flex aspect-video w-full items-center justify-center overflow-hidden rounded-cartao border border-borda bg-lilas">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_30%,#c9cfff,transparent_55%),radial-gradient(circle_at_75%_70%,#dfe3f5,transparent_50%)] blur-2xl" aria-hidden="true" />
      <div className="relative px-6 text-center">
        <svg className="mx-auto mb-2 text-suave" width="28" height="28" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M7 10V7a5 5 0 0 1 10 0v3" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <rect x="4.5" y="10" width="15" height="11" rx="2.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
        </svg>
        <p className="font-semibold text-tinta">{titulo}</p>
        <p className="mt-0.5 text-sm text-suave">{motivo ?? "Não incluído no seu plano"}</p>
      </div>
    </div>
  );
}

/** Vídeo que só carrega o player quando pedem: lista com dez vídeos não abre dez iframes. */
export function VideoSobDemanda({ video, rotulo = "Assistir" }: { video: Video; rotulo?: string }) {
  const [aberto, setAberto] = useState(false);
  if (video.bloqueado) {
    return (
      <div className="flex items-center gap-3 rounded-cartao border border-borda bg-canvas px-3 py-2.5">
        <svg className="shrink-0 text-suave" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M7 10V7a5 5 0 0 1 10 0v3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <rect x="4.5" y="10" width="15" height="11" rx="2.5" fill="none" stroke="currentColor" strokeWidth="2" />
        </svg>
        <div className="min-w-0">
          <p className="truncate text-[15px] font-medium text-tinta">{video.titulo}</p>
          <p className="text-[13px] text-suave">{video.motivo ?? "Não incluído no seu plano"}</p>
        </div>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-cartao border border-borda bg-papel px-3 py-2.5">
        <div className="min-w-0">
          <p className="truncate text-[15px] font-medium text-tinta">{video.titulo}</p>
          {video.duracao_segundos ? <p className="text-[13px] text-suave">{duracao(video.duracao_segundos)}</p> : null}
        </div>
        <Botao tamanho="pequeno" variante={aberto ? "neutro" : "secundario"} onClick={() => setAberto(!aberto)} aria-expanded={aberto}>
          {aberto ? "Fechar" : `▶ ${rotulo}`}
        </Botao>
      </div>
      {aberto && <Player video={video} />}
    </div>
  );
}
