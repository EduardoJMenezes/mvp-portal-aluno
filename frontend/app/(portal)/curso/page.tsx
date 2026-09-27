"use client";

import Link from "next/link";
import { Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type AulaNoCurso } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";

export default function MeuCurso() {
  const conteudo = useDados(() => api.conteudo());

  return (
    <Pagina titulo="Meu curso" legenda="Aulas e resoluções das turmas em que você está matriculado.">
      <Estado {...conteudo} linhas={5}>
        {(turmas) =>
          turmas.length === 0 ? (
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
                          {modulo.submodulos.some((s) => s.itens.length) ? `Assistir · ${plural(modulo.submodulos.reduce((n, s) => n + s.itens.length, 0), "vídeo")}` : "Ver capítulo"}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ol>
              </section>
            ))
          )
        }
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
