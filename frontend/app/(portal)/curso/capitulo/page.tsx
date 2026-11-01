"use client";

import { ArrowRight, CircleCheck } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo } from "react";
import { CapaDoModulo } from "@/components/CapaDoModulo";
import { iconeDoSubmodulo } from "@/components/CartaoDoModulo";
import { Aviso, BotaoLink, Estado, Pagina, Progresso } from "@/components/ui";
import { api, useDados, type ConteudoDaTurma, type Modulo, type SubModulo } from "@/lib/api";
import { enderecoDaAnalise, enderecoDaSecao } from "@/lib/curso";
import { emBrasilia, plural } from "@/lib/formato";
import { partesDoNome } from "@/lib/icones";
import { useUsuario } from "@/lib/sessao";

// O capítulo: um cartão por seção (Aulas, Questões da apostila, Materiais…), e cada cartão abre só
// aquela seção. A seção é o sub-módulo que o professor montou, então seção nova vira cartão novo
// sem mexer aqui. Capítulo de uma seção só vai direto para ela: não há o que escolher.

export default function PaginaDoCapitulo() {
  return (
    <Suspense>
      <Capitulo />
    </Suspense>
  );
}

function Capitulo() {
  const conteudo = useDados(() => api.conteudo(), [], 60);
  return (
    <Estado {...conteudo} linhas={4} forma="cartoes">
      {(turmas) => <Secoes turmas={turmas} />}
    </Estado>
  );
}

function Secoes({ turmas }: { turmas: ConteudoDaTurma[] }) {
  const router = useRouter();
  const moduloId = Number(useSearchParams().get("modulo"));
  const modulo = useMemo(() => turmas.flatMap((t) => t.modulos).find((m) => m.id === moduloId), [turmas, moduloId]);
  const acompanha = useUsuario().papel === "ALUNO";
  const unica = modulo?.submodulos.length === 1 ? modulo.submodulos[0] : undefined;

  useEffect(() => {
    if (modulo && unica) router.replace(enderecoDaSecao(modulo.id, unica.id));
  }, [modulo, unica, router]);

  if (!modulo) {
    return (
      <Pagina titulo="Capítulo não encontrado" voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
        <Aviso tom="atencao">Este capítulo não está publicado para a sua turma.</Aviso>
      </Pagina>
    );
  }
  if (unica) return null;

  const { codigo, titulo } = partesDoNome(modulo.nome);
  return (
    <Pagina titulo={titulo} legenda={[codigo, modulo.turma].filter(Boolean).join(" · ")} voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
      <div className="flex items-center gap-4">
        <CapaDoModulo modulo={modulo} />
        <p className="text-[15px] text-tinta-2">Escolha por onde seguir.</p>
      </div>
      {modulo.submodulos.length === 0 ? (
        <Aviso tom="info" titulo="Ainda sem conteúdo neste capítulo">Quando o professor publicar, ele aparece aqui.</Aviso>
      ) : (
        <ol className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {modulo.submodulos.map((secao) => (
            <li key={secao.id}>
              <CartaoDaSecao modulo={modulo} secao={secao} acompanha={acompanha} />
            </li>
          ))}
        </ol>
      )}
      {acompanha &&
        modulo.submodulos.filter(corrigida).map((s) => (
          <div key={s.id} className="flex flex-wrap items-center justify-between gap-3 rounded-cartao border border-sucesso-borda bg-sucesso-fundo px-4 py-3">
            <p className="text-[15px] font-medium text-sucesso">Você terminou {s.nome}.</p>
            <BotaoLink tamanho="pequeno" variante="primario" href={enderecoDaAnalise(modulo.id, s.id)}>Ver minha análise</BotaoLink>
          </div>
        ))}
    </Pagina>
  );
}

/** As questões da seção, todas respondidas e já corrigidas: a análise delas está pronta. */
const corrigida = (secao: SubModulo) => {
  const deQuestao = secao.itens.filter((i) => i.questao);
  return deQuestao.length > 0 && deQuestao.every((i) => i.questao?.respondida && i.questao.correta != null);
};

/** O que a seção guarda, contado do jeito que o aluno fala: "5 aulas", "20 questões", "3 PDFs". */
function descrever(itens: SubModulo["itens"]): string {
  const questoes = itens.filter((i) => i.questao).length;
  const videos = itens.filter((i) => !i.questao && i.video_id != null).length;
  const pdfs = itens.length - questoes - videos;
  if (questoes === itens.length) return plural(questoes, "questão", "questões");
  if (videos === itens.length) return plural(videos, "vídeo");
  if (pdfs === itens.length) return plural(pdfs, "PDF");
  return plural(itens.length, "item", "itens");
}

function CartaoDaSecao({ modulo, secao, acompanha }: { modulo: Modulo; secao: SubModulo; acompanha: boolean }) {
  const Icone = iconeDoSubmodulo(secao.itens);
  const total = secao.itens.length;
  const feitos = secao.itens.filter((i) => i.concluido).length;
  const completo = total > 0 && feitos === total;
  const deQuestao = secao.itens.filter((i) => i.questao);
  const acertos = deQuestao.filter((i) => i.questao?.correta === true).length;
  // Com gabarito no fim, enquanto falta questão a correção está guardada: só dá para contar respondidas.
  const corrigidas = corrigida(secao);
  const proxima = acompanha && feitos > 0 ? secao.itens.find((i) => !i.concluido) : undefined;
  const aoVivo = (secao.aulas ?? []).find((a) => a.estado === "ABERTA" || a.estado === "AGUARDANDO") ?? (secao.aulas ?? []).find((a) => a.estado === "AGENDADA");

  let situacao: string;
  if (!acompanha || total === 0) situacao = "";
  else if (deQuestao.length === total && corrigidas) situacao = `${acertos} de ${total} acertos`;
  else if (deQuestao.length === total && secao.gabarito_no_fim) situacao = `${feitos} de ${total} respondidas · a correção sai no fim`;
  else if (completo) situacao = "Concluído";
  else situacao = feitos === 0 ? "Ainda não começou" : `${feitos} de ${total} concluídos`;

  return (
    <Link
      href={enderecoDaSecao(modulo.id, secao.id, proxima?.id)}
      className={`group flex h-full flex-col gap-3 rounded-cartao border bg-papel p-5 shadow-suave transition-colors ${acompanha && completo ? "border-sucesso-borda hover:border-sucesso-vivo" : "border-borda/70 hover:border-acento/50"}`}
    >
      <span className="flex items-center gap-3">
        <span aria-hidden="true" className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-lilas text-acento">
          <Icone className="size-6" strokeWidth={1.8} />
        </span>
        <span className="min-w-0">
          <span className="block font-titulo text-[17px] font-semibold leading-snug text-tinta">{secao.nome}</span>
          <span className="block text-sm text-suave">{total > 0 ? descrever(secao.itens) : "Ainda vazio"}</span>
        </span>
      </span>
      {aoVivo && (
        <span className={`text-sm ${aoVivo.estado === "AGENDADA" ? "text-suave" : "font-semibold text-erro"}`}>
          {aoVivo.estado === "AGENDADA" ? `Ao vivo em ${emBrasilia(aoVivo.inicio_em)}` : aoVivo.estado === "ABERTA" ? "Ao vivo agora" : "Sala aberta"}: {aoVivo.titulo}
        </span>
      )}
      {acompanha && total > 0 && (
        <span className="mt-auto flex flex-col gap-1.5 pt-1">
          <Progresso feitos={feitos} total={total} rotulo={`Progresso em ${secao.nome}`} />
          <span className={`inline-flex items-center gap-1.5 text-[13px] tabular-nums ${completo ? "font-semibold text-sucesso" : "text-suave"}`}>
            {completo && <CircleCheck aria-hidden="true" className="size-4 text-sucesso-vivo" strokeWidth={2.4} />}
            {situacao}
          </span>
        </span>
      )}
      <span className={`${acompanha && total > 0 ? "" : "mt-auto"} inline-flex items-center gap-1.5 text-sm font-semibold text-acento group-hover:text-acento-forte`}>
        {completo ? "Rever" : proxima ? "Continuar" : "Abrir"}
        <ArrowRight aria-hidden="true" className="size-4 transition-transform group-hover:translate-x-0.5" />
      </span>
    </Link>
  );
}
