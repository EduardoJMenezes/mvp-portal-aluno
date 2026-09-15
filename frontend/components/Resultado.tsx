"use client";

import type { Recomendacao } from "@/lib/api";
import { plural, porcento } from "@/lib/formato";
import { VideoSobDemanda } from "./Player";
import { Cartao, Etiqueta, TituloDeSecao } from "./ui";

export function Anel({ percentual, tamanho = 112 }: { percentual: number; tamanho?: number }) {
  return (
    <div
      role="img"
      aria-label={`${porcento(percentual)} de acerto`}
      className="relative shrink-0 rounded-full"
      style={{ width: tamanho, height: tamanho, background: `conic-gradient(var(--color-acento) ${percentual * 3.6}deg, var(--color-lilas) 0)` }}
    >
      <div className="absolute inset-[10px] flex items-center justify-center rounded-full bg-papel">
        <span className="text-2xl font-semibold tabular-nums text-tinta">{porcento(Math.round(percentual))}</span>
      </div>
    </div>
  );
}

export function OndeRevisar({ analise, legenda = "Os assuntos em que você mais errou e os vídeos que explicam cada um." }: { analise: Recomendacao[]; legenda?: string }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-revisar">
      <TituloDeSecao>
        <span id="titulo-revisar">Onde revisar</span>
      </TituloDeSecao>
      <p className="-mt-1 text-[15px] text-suave">{legenda}</p>
      <ul className="grid gap-3 md:grid-cols-2">
        {analise.map((t) => (
          <Cartao key={t.topico} como="li" className="flex flex-col gap-3 p-5">
            <div className="flex items-center justify-between gap-2">
              <h3 className="font-semibold text-tinta">{t.topico}</h3>
              <Etiqueta tom="erro">{plural(t.erros, "erro")}</Etiqueta>
            </div>
            {t.videos.length === 0 ? (
              <p className="text-sm text-suave">Ainda sem vídeo sobre este assunto.</p>
            ) : (
              t.videos.map((v) => <VideoSobDemanda key={v.id} video={v} />)
            )}
          </Cartao>
        ))}
      </ul>
    </section>
  );
}
