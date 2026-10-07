import { ArrowRight, FileText, SquarePlay } from "lucide-react";
import Link from "next/link";
import type { AulaNoCurso, Modulo } from "@/lib/api";
import { contarAulas, emBrasilia } from "@/lib/formato";
import { partesDoNome } from "@/lib/icones";
import { CapaDoModulo } from "./CapaDoModulo";

// O cartão do capítulo em "Meu curso", como no guia: a capa (ícone ou foto), o código da apostila,
// o título e o que tem dentro. A barra de progresso do guia entra quando a plataforma souber
// o que o aluno já assistiu — até lá, um número aqui seria inventado.

export function CartaoDoModulo({ modulo }: { modulo: Modulo }) {
  const { codigo, titulo } = partesDoNome(modulo.nome);
  const itens = modulo.submodulos.flatMap((s) => s.itens);
  const soVideos = itens.length > 0 && itens.every((i) => i.video_id != null);

  return (
    <Link
      href={`/curso/aula/?modulo=${modulo.id}`}
      className="group flex h-full gap-4 rounded-cartao border border-borda/70 bg-papel p-5 shadow-suave transition-colors hover:border-acento/50"
    >
      <CapaDoModulo modulo={modulo} />
      <span className="flex min-w-0 flex-1 flex-col gap-2">
        {codigo && <span className="self-start rounded-md bg-lilas px-2 py-0.5 text-xs font-bold tracking-wide text-acento-forte">{codigo}</span>}
        <span className="font-titulo text-[17px] font-semibold leading-snug text-tinta">{titulo}</span>
        <span className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-suave">
          {modulo.submodulos.map((s) => {
            const DoSub = s.itens.some((i) => i.questao) || /quest/i.test(s.nome) ? FileText : SquarePlay;
            return (
              <span key={s.id} className="inline-flex items-center gap-1.5">
                <DoSub aria-hidden="true" className="size-4 shrink-0" strokeWidth={1.8} />
                {s.nome}: <span className="font-semibold tabular-nums text-tinta-2">{s.itens.length}</span>
              </span>
            );
          })}
        </span>
        <ProximaAula aulas={modulo.submodulos.flatMap((s) => s.aulas ?? [])} />
        <span className="mt-auto inline-flex items-center gap-1.5 pt-1 text-sm font-semibold text-acento group-hover:text-acento-forte">
          {itens.length === 0 ? "Ver capítulo" : `${soVideos ? "Assistir" : "Abrir"} • ${contarAulas(itens)}`}
          <ArrowRight aria-hidden="true" className="size-4 transition-transform group-hover:translate-x-0.5" />
        </span>
      </span>
    </Link>
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
