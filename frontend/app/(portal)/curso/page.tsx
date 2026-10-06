"use client";

import Link from "next/link";
import { Suspense } from "react";
import { useCategoria } from "@/components/Categoria";
import { Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, casaCategoria, useDados, type AulaNoCurso } from "@/lib/api";
import { contarAulas, emBrasilia } from "@/lib/formato";

export default function PaginaDoCurso() {
  return (
    <Suspense>
      <MeuCurso />
    </Suspense>
  );
}

function MeuCurso() {
  const categoria = useCategoria();
  const conteudo = useDados(() => api.conteudo(), [], 60);

  return (
    <Pagina titulo={categoria ?? "Meu curso"} legenda="Aulas e resoluções das turmas em que você está matriculado.">
      <Estado {...conteudo} linhas={5}>
        {(todas) => {
          // O botão do menu mostra só os capítulos daquela categoria; turma sem nenhum some.
          const turmas = todas
            .map((t) => ({ ...t, modulos: t.modulos.filter((m) => casaCategoria(categoria, m.categoria)) }))
            .filter((t) => t.modulos.length > 0);
          return turmas.length === 0 ? (
            <Vazio titulo="Nada publicado para você ainda">Quando o professor publicar as aulas da sua turma, elas aparecem aqui.</Vazio>
          ) : (
            turmas.map((turma) => (
              <section key={turma.turma_id} className="flex flex-col gap-3" aria-label={turma.turma}>
                <TituloDeSecao>{turma.turma}</TituloDeSecao>
                <ol className="grid gap-3 md:grid-cols-2">
                  {turma.modulos.map((modulo) => (
                    <li key={modulo.id}>
                      <Link href={`/curso/aula/?modulo=${modulo.id}`} className="flex h-full flex-col gap-3 rounded-cartao border border-borda bg-papel p-5 transition-shadow hover:shadow-suave">
                        <span className="text-lg font-semibold text-tinta">{modulo.nome}</span>
                        <span className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-suave">
                          {modulo.submodulos.map((s) => (
                            <span key={s.id}>
                              {s.nome}: <span className="font-semibold tabular-nums text-tinta-2">{s.itens.length}</span>
                            </span>
                          ))}
                        </span>
                        <ProximaAula aulas={modulo.submodulos.flatMap((s) => s.aulas ?? [])} />
                        <span className="text-sm font-semibold text-acento">
                          {modulo.submodulos.some((s) => s.itens.length) ? `Abrir · ${contarAulas(modulo.submodulos.flatMap((s) => s.itens))}` : "Ver capítulo"}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ol>
              </section>
            ))
          );
        }}
      </Estado>
    </Pagina>
  );
}

/** O aviso da aula ao vivo no cartão do capítulo: a que está acontecendo ou a próxima. */
function ProximaAula({ aulas }: { aulas: AulaNoCurso[] }) {
  const agora = aulas.find((a) => a.estado === "ABERTA" || a.estado === "AGUARDANDO");
  const proxima = aulas.find((a) => a.estado === "AGENDADA");
  if (agora) return <span className="text-sm font-semibold text-erro">{agora.estado === "ABERTA" ? "Ao vivo agora" : "Sala aberta"}: {agora.titulo}</span>;
  if (proxima) return <span className="text-sm text-suave">Ao vivo em {emBrasilia(proxima.inicio_em)}: {proxima.titulo}</span>;
  return null;
}
