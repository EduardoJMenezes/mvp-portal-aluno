"use client";

// A prévia do vídeo cru, para o professor conferir o que está pondo no curso: só o player do
// Vimeo, sem progresso nem nada da tela do aluno. O player só é carregado quando a prévia abre.

import { ChevronLeft, ChevronRight } from "lucide-react";
import { duracao } from "@/lib/formato";

export type VideoParaVer = { vimeo_id?: string | null; titulo: string; embed_url?: string | null; duracao_segundos?: number | null };

/** O endereço do player como o Vimeo o deu (vídeo não listado só toca com o código que vem nele). */
const enderecoDoPlayer = (v: VideoParaVer) => v.embed_url || (v.vimeo_id ? `https://player.vimeo.com/video/${v.vimeo_id}` : null);

/** A tela do vídeo, em 16:9. Trocar de vídeo troca o player inteiro. */
export function TelaDoVideo({ video, className = "" }: { video: VideoParaVer; className?: string }) {
  const src = enderecoDoPlayer(video);
  return (
    <div className={`aspect-video w-full overflow-hidden rounded-xl bg-tinta ${className}`}>
      {src ? (
        <iframe key={src} src={src} title={video.titulo} allow="autoplay; fullscreen; picture-in-picture" allowFullScreen className="size-full border-0" />
      ) : (
        <p className="flex size-full items-center justify-center px-6 text-center text-[15px] text-white/80">Este vídeo não tem endereço de player guardado.</p>
      )}
    </div>
  );
}

/**
 * "Anterior" e "Próximo", com a posição no meio: para conferir uma lista inteira sem fechar a prévia.
 * `rotulos` troca o nome dos botões quando a lista não é só de vídeos.
 */
export function PassarVideos({ posicao, total, aoPassar, className = "", rotulos = ["Vídeo anterior", "Próximo vídeo"] }: { posicao: number; total: number; aoPassar: (passo: -1 | 1) => void; className?: string; rotulos?: [string, string] }) {
  const seta = "flex size-8 items-center justify-center rounded-lg text-tinta-2 hover:bg-lilas hover:text-acento-forte disabled:text-apagado disabled:hover:bg-transparent";
  return (
    <div className={`flex shrink-0 items-center gap-0.5 ${className}`}>
      <button type="button" onClick={() => aoPassar(-1)} disabled={posicao <= 0} aria-label={rotulos[0]} title={rotulos[0]} className={seta}>
        <ChevronLeft aria-hidden="true" className="size-5" />
      </button>
      <span className="min-w-12 text-center text-[13px] tabular-nums text-suave">{posicao < 0 ? "" : `${posicao + 1} de ${total}`}</span>
      <button type="button" onClick={() => aoPassar(1)} disabled={posicao < 0 || posicao >= total - 1} aria-label={rotulos[1]} title={rotulos[1]} className={seta}>
        <ChevronRight aria-hidden="true" className="size-5" />
      </button>
    </div>
  );
}

/** "5:37" ou nada: a duração que acompanha o título. */
export const duracaoDoVideo = (v: VideoParaVer) => duracao(v.duracao_segundos);
