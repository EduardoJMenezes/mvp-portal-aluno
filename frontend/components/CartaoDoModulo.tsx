"use client";

import { ArrowRight, Check, CircleCheck, CircleHelp, FileText, LayoutList, SquarePlay, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import type { AulaNoCurso, Modulo } from "@/lib/api";
import { contarAulas, emBrasilia } from "@/lib/formato";
import { partesDoNome } from "@/lib/icones";
import { CapaDoModulo } from "./CapaDoModulo";
import { CHAVE_DO_RECEM_CONCLUIDO } from "./Comemoracao";
import { Progresso } from "./ui";

// O cartão do capítulo em "Meu curso", como no guia: a capa (ícone ou foto), o código da apostila,
// o título, o que tem dentro e, para o aluno, a barra do que ele já fez.

/**
 * O ícone do sub-módulo sai do que há dentro dele, nunca do nome: "Questões da apostila" pode
 * guardar os vídeos de resolução, e aí é uma lista de vídeos. Misturado ou vazio, uma lista.
 */
function iconeDoSubmodulo(itens: Modulo["submodulos"][number]["itens"]): LucideIcon {
  if (itens.length === 0) return LayoutList;
  if (itens.every((i) => i.questao)) return CircleHelp;
  if (itens.every((i) => !i.questao && i.video_id != null)) return SquarePlay;
  if (itens.every((i) => !i.questao && i.video_id == null)) return FileText;
  return LayoutList;
}

/** `comProgresso`: só para o aluno — o professor que abre "Meu curso" não tem o que mostrar na barra. */
export function CartaoDoModulo({ modulo, comProgresso = false }: { modulo: Modulo; comProgresso?: boolean }) {
  const { codigo, titulo } = partesDoNome(modulo.nome);
  const itens = modulo.submodulos.flatMap((s) => s.itens);
  const soVideos = itens.length > 0 && itens.every((i) => i.video_id != null);
  const feitos = itens.filter((i) => i.concluido).length;
  const completo = itens.length > 0 && feitos === itens.length;
  // Quem já começou cai direto na primeira aula que falta.
  const proxima = comProgresso && feitos > 0 ? itens.find((i) => !i.concluido) : undefined;
  const concluido = comProgresso && completo;

  // Quem acabou de terminar este capítulo e voltou para cá vê o selo chegar, uma vez.
  const [recem, setRecem] = useState(false);
  useEffect(() => {
    if (!concluido) return;
    try {
      if (sessionStorage.getItem(CHAVE_DO_RECEM_CONCLUIDO) !== String(modulo.id)) return;
      sessionStorage.removeItem(CHAVE_DO_RECEM_CONCLUIDO);
      setRecem(true);
    } catch {
      // Sem sessionStorage, o selo só aparece parado.
    }
  }, [concluido, modulo.id]);

  return (
    <Link
      href={`/curso/aula/?modulo=${modulo.id}${proxima ? `&item=${proxima.id}` : ""}`}
      className={`group flex h-full gap-4 rounded-cartao border bg-papel p-5 shadow-suave transition-colors ${concluido ? "border-sucesso-borda hover:border-sucesso-vivo" : "border-borda/70 hover:border-acento/50"}`}
    >
      <span className="relative shrink-0 self-start">
        <CapaDoModulo modulo={modulo} />
        {concluido && (
          <span aria-hidden="true" className={`absolute -bottom-1.5 -right-1.5 flex size-6 items-center justify-center rounded-full bg-sucesso-vivo text-white ring-2 ring-papel ${recem ? "selo-pulsa" : ""}`}>
            <Check className="size-3.5" strokeWidth={3.5} />
          </span>
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-2">
        {codigo && <span className="self-start rounded-md bg-lilas px-2 py-0.5 text-xs font-bold tracking-wide text-acento-forte">{codigo}</span>}
        <span className="font-titulo text-[17px] font-semibold leading-snug text-tinta">{titulo}</span>
        <span className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-suave">
          {modulo.submodulos.map((s) => {
            const DoSub = iconeDoSubmodulo(s.itens);
            return (
              <span key={s.id} className="inline-flex items-center gap-1.5">
                <DoSub aria-hidden="true" className="size-4 shrink-0" strokeWidth={1.8} />
                {s.nome}: <span className="font-semibold tabular-nums text-tinta-2">{s.itens.length}</span>
              </span>
            );
          })}
        </span>
        <ProximaAula aulas={modulo.submodulos.flatMap((s) => s.aulas ?? [])} />
        {comProgresso && itens.length > 0 && (
          <span className="mt-auto flex flex-col gap-1.5 pt-1">
            <Progresso feitos={feitos} total={itens.length} rotulo={`Progresso em ${modulo.nome}`} />
            <span className={`inline-flex items-center gap-1.5 text-[13px] tabular-nums ${completo ? "font-semibold text-sucesso" : "text-suave"}`}>
              {completo && <CircleCheck aria-hidden="true" className="size-4 text-sucesso-vivo" strokeWidth={2.4} />}
              {completo ? "Capítulo concluído" : feitos === 0 ? "Ainda não começou" : `${feitos} de ${itens.length} concluídos`}
            </span>
          </span>
        )}
        <span className={`${comProgresso && itens.length > 0 ? "" : "mt-auto pt-1"} inline-flex items-center gap-1.5 text-sm font-semibold text-acento group-hover:text-acento-forte`}>
          {itens.length === 0 ? "Ver capítulo" : concluido ? "Rever o capítulo" : comProgresso && feitos > 0 ? "Continuar" : `${soVideos ? "Assistir" : "Abrir"} • ${contarAulas(itens)}`}
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
