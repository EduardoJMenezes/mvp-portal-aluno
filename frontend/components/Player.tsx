"use client";

import { useState } from "react";
import type { Video } from "@/lib/api";
import { duracao } from "@/lib/formato";
import { Botao } from "./ui";

// O embed_url vem como o Vimeo devolveu: sem o hash de privacidade, o player
// recusa tocar vídeo unlisted (docs/VIMEO.md). Vídeo bloqueado chega só com o
// nome e o aviso — não há o que o devtools libere.

export function Player({ video }: { video: Video }) {
  if (video.bloqueado || !video.embed_url) return <VideoBloqueado titulo={video.titulo} motivo={video.motivo} />;
  return (
    <div className="aspect-video w-full overflow-hidden rounded-cartao bg-tinta">
      <iframe
        src={video.embed_url}
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
