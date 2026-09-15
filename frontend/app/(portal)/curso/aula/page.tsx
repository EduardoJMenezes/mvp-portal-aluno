"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo } from "react";
import { Player } from "@/components/Player";
import { Aviso, Botao, Estado, Pagina } from "@/components/ui";
import { api, useDados, type ConteudoDaTurma } from "@/lib/api";
import { duracao } from "@/lib/formato";

export default function PaginaDaAula() {
  return (
    <Suspense>
      <Aula />
    </Suspense>
  );
}

function Aula() {
  const conteudo = useDados(() => api.conteudo());
  return (
    <Estado {...conteudo} linhas={4}>
      {(turmas) => <Modulo turmas={turmas} />}
    </Estado>
  );
}

function Modulo({ turmas }: { turmas: ConteudoDaTurma[] }) {
  const router = useRouter();
  const parametros = useSearchParams();
  const moduloId = Number(parametros.get("modulo"));
  const itemId = Number(parametros.get("item"));

  const modulo = useMemo(() => turmas.flatMap((t) => t.modulos).find((m) => m.id === moduloId), [turmas, moduloId]);
  const itens = useMemo(() => modulo?.submodulos.flatMap((s) => s.itens) ?? [], [modulo]);

  if (!modulo) {
    return (
      <Pagina titulo="Módulo não encontrado" voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
        <Aviso tom="atencao">Este módulo não está publicado para a sua turma.</Aviso>
      </Pagina>
    );
  }

  const indice = Math.max(0, itens.findIndex((i) => i.id === itemId));
  const atual = itens[indice];
  const ir = (novo: number) => router.push(`/curso/aula/?modulo=${modulo.id}&item=${itens[novo].id}`, { scroll: false });

  return (
    <Pagina titulo={modulo.nome} legenda={modulo.turma} voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-3">
          {atual?.video ? <Player video={atual.video} /> : <Aviso tom="atencao">Este item está sem vídeo.</Aviso>}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h2 className="text-xl font-semibold text-tinta">{atual?.nome}</h2>
              {atual?.video && !atual.video.bloqueado && atual.video.duracao_segundos ? (
                <p className="text-sm text-suave">{duracao(atual.video.duracao_segundos)}</p>
              ) : null}
            </div>
            <div className="flex gap-2">
              <Botao disabled={indice === 0} onClick={() => ir(indice - 1)}>Anterior</Botao>
              <Botao variante="primario" disabled={indice >= itens.length - 1} onClick={() => ir(indice + 1)}>Próximo</Botao>
            </div>
          </div>
        </div>

        <aside aria-label="Vídeos do módulo" className="flex flex-col gap-3 lg:sticky lg:top-20 lg:max-h-[calc(100dvh-6rem)] lg:overflow-y-auto">
          {modulo.submodulos.map((sub) => (
            <details key={sub.id} open className="rounded-cartao border border-borda bg-papel">
              <summary className="flex cursor-pointer items-center justify-between px-4 py-3 font-semibold text-tinta">
                {sub.nome}
                <span className="text-sm font-normal tabular-nums text-suave">{sub.itens.length}</span>
              </summary>
              <ol className="border-t border-borda py-1">
                {sub.itens.map((item) => {
                  const selecionado = item.id === atual?.id;
                  const bloqueado = !item.video || item.video.bloqueado;
                  return (
                    <li key={item.id}>
                      <Link
                        href={`/curso/aula/?modulo=${modulo.id}&item=${item.id}`}
                        scroll={false}
                        aria-current={selecionado ? "true" : undefined}
                        className={`flex items-center gap-2 px-4 py-2 text-[15px] ${selecionado ? "bg-lilas font-semibold text-acento-forte" : "text-tinta hover:bg-canvas"}`}
                      >
                        <span aria-hidden="true" className="flex w-4 shrink-0 justify-center text-xs text-suave">
                          {bloqueado ? (
                            <svg width="12" height="12" viewBox="0 0 24 24"><path d="M7 10V7a5 5 0 0 1 10 0v3" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" /><rect x="4.5" y="10" width="15" height="11" rx="2.5" fill="currentColor" /></svg>
                          ) : selecionado ? (
                            "▶"
                          ) : null}
                        </span>
                        <span className="truncate">{item.nome}</span>
                        {bloqueado && <span className="sr-only">(bloqueado)</span>}
                      </Link>
                    </li>
                  );
                })}
              </ol>
            </details>
          ))}
        </aside>
      </div>
    </Pagina>
  );
}
