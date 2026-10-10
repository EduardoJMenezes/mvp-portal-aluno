"use client";

import { CircleHelp, FileText, RotateCcw, SquarePlay, type LucideIcon } from "lucide-react";
import Link from "next/link";
import type { LinhaParaRevisar, Recomendacao } from "@/lib/api";
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

/** O que há no curso do aluno sobre o ponto fraco, na ordem em que vale a pena: estudar, praticar, rever. */
const GRUPOS: { tipo: LinhaParaRevisar["tipo"]; titulo: string; Icone: LucideIcon }[] = [
  { tipo: "VIDEO", titulo: "Assista", Icone: SquarePlay },
  { tipo: "PDF", titulo: "Leia", Icone: FileText },
  { tipo: "QUESTAO", titulo: "Pratique: questões que você ainda não fez", Icone: CircleHelp },
  { tipo: "ERRO", titulo: "Reveja a resolução das que errou", Icone: RotateCcw },
];

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
            {GRUPOS.map((grupo) => {
              const linhas = (t.no_curso ?? []).filter((l) => l.tipo === grupo.tipo);
              if (!linhas.length) return null;
              const Icone = grupo.Icone;
              return (
                <div key={grupo.tipo} className="flex flex-col gap-1">
                  <p className="text-[13px] font-semibold text-tinta-2">{grupo.titulo}</p>
                  <ul className="flex flex-col">
                    {linhas.map((l) => (
                      <li key={l.item_id}>
                        <Link href={`/curso/aula/?modulo=${l.modulo_id}&item=${l.item_id}`} className="group flex items-center gap-2.5 rounded-campo px-2 py-1.5 text-[15px] hover:bg-canvas">
                          <Icone aria-hidden="true" className="size-4 shrink-0 text-acento" strokeWidth={2} />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate font-medium text-tinta group-hover:text-acento-forte">{l.nome}</span>
                            <span className="block truncate text-[13px] text-suave">{l.modulo}</span>
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
            {t.videos.length > 0 && (
              <div className="flex flex-col gap-2">
                {(t.no_curso?.length ?? 0) > 0 && <p className="text-[13px] font-semibold text-tinta-2">Outros vídeos sobre isso</p>}
                {t.videos.map((v) => (
                  <VideoSobDemanda key={v.id} video={v} />
                ))}
              </div>
            )}
            {t.videos.length === 0 && !t.no_curso?.length && <p className="text-sm text-suave">Ainda sem material sobre este assunto.</p>}
          </Cartao>
        ))}
      </ul>
    </section>
  );
}
