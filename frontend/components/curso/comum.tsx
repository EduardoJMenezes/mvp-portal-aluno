"use client";

// O que as peças da tela de montar o curso dividem: os tipos, a regra de quem vê o quê e o
// arrastar-para-reordenar.

import { CircleHelp, FileText, GripVertical, Play, Radio, type LucideIcon } from "lucide-react";
import { useRef, useState, type DragEvent, type KeyboardEvent, type ReactNode } from "react";
import type { useConfirmar } from "@/components/ui";
import type { ItemCurso, Modulo } from "@/lib/api";

/** Roda a ação, relê a árvore e avisa: `true` se deu certo. Sem mensagem, o sucesso é silencioso. */
export type Executar = (acao: () => Promise<unknown>, mensagem?: ReactNode | (() => ReactNode)) => Promise<boolean>;
export type Confirmar = ReturnType<typeof useConfirmar>[1];

/** Nas rotas de edição, a turma "biblioteca" acha o módulo sem turma nenhuma (decisão 0011). */
export const BIBLIOTECA = "biblioteca";

/** O que a turma recebe: os módulos dela e as aulas visíveis — a mesma regra do backend. */
export function comoATurmaVe(modulos: Modulo[], turma: string): Modulo[] {
  return modulos
    .map((m) => {
      const recebe = (m.turmas ?? []).includes(turma);
      return {
        ...m,
        submodulos: m.submodulos.map((s) => ({
          ...s,
          itens: s.itens.filter((i) => (i.turmas?.length ? i.turmas.includes(turma) : recebe)),
        })),
        recebe,
      };
    })
    .filter((m) => m.recebe || m.submodulos.some((s) => s.itens.length > 0));
}

// --- o tipo da linha ---------------------------------------------------------

export type Tipo = "video" | "pdf" | "questao" | "aovivo";

/** A cor e o ícone dizem o tipo antes do texto: dá para ler a composição do capítulo de relance. */
export const TIPOS: Record<Tipo, { nome: string; Icone: LucideIcon; classe: string }> = {
  video: { nome: "Vídeo", Icone: Play, classe: "bg-gelo text-acento" },
  pdf: { nome: "PDF", Icone: FileText, classe: "bg-atencao-fundo text-atencao" },
  questao: { nome: "Questão", Icone: CircleHelp, classe: "bg-violeta/10 text-violeta" },
  aovivo: { nome: "Aula ao vivo", Icone: Radio, classe: "bg-erro-fundo text-erro" },
};

export const tipoDaLinha = (item: ItemCurso): Tipo => (item.questao ? "questao" : item.video_id ? "video" : "pdf");

export function Azulejo({ tipo, className = "" }: { tipo: Tipo; className?: string }) {
  const { Icone, classe } = TIPOS[tipo];
  return (
    <span aria-hidden="true" className={`flex size-9 shrink-0 items-center justify-center rounded-[10px] ${classe} ${className}`}>
      <Icone className="size-[18px]" strokeWidth={2} />
    </span>
  );
}

/** "3 vídeos, 1 PDF e 4 questões": o que há dentro, em palavras. */
export function composicao(itens: ItemCurso[]): string {
  const conta = { video: 0, pdf: 0, questao: 0 };
  for (const i of itens) conta[tipoDaLinha(i) as keyof typeof conta]++;
  const partes = [
    conta.video && `${conta.video} ${conta.video === 1 ? "vídeo" : "vídeos"}`,
    conta.pdf && `${conta.pdf} ${conta.pdf === 1 ? "PDF" : "PDFs"}`,
    conta.questao && `${conta.questao} ${conta.questao === 1 ? "questão" : "questões"}`,
  ].filter((p): p is string => !!p);
  if (partes.length === 0) return "Vazio";
  return partes.length === 1 ? partes[0] : `${partes.slice(0, -1).join(", ")} e ${partes[partes.length - 1]}`;
}

// --- a ordem -----------------------------------------------------------------

/** `atuais` com os citados em `ids` trocando de lugar entre si: a mesma conta que o backend faz. */
export function encaixar<T extends { id: number }>(atuais: T[], ids: number[]): T[] {
  const porId = new Map(atuais.map((a) => [a.id, a]));
  const citados = new Set(ids);
  let proximo = 0;
  return atuais.map((a) => (citados.has(a.id) ? porId.get(ids[proximo++])! : a));
}

/**
 * Reordenar uma lista arrastando pela alça — ou, com o foco na alça, pelas setas do teclado.
 * Quem usa espalha `linha(id)` no elemento da lista e põe uma `<Alca>` dentro dele.
 */
export function useArrastar(ids: number[], nomes: Record<number, string>, aoReordenar: (ids: number[]) => void) {
  const [arrastando, setArrastando] = useState<number | null>(null);
  const [alvo, setAlvo] = useState<{ id: number; depois: boolean } | null>(null);
  const [anuncio, setAnuncio] = useState("");
  const elementos = useRef(new Map<number, HTMLElement>());

  const mover = (id: number, para: number) => {
    const de = ids.indexOf(id);
    if (de < 0 || para < 0 || para >= ids.length || para === de) return;
    const nova = ids.filter((i) => i !== id);
    nova.splice(para, 0, id);
    setAnuncio(`${nomes[id] ?? "Item"}: posição ${para + 1} de ${ids.length}.`);
    aoReordenar(nova);
  };

  const soltar = () => {
    if (arrastando !== null && alvo && alvo.id !== arrastando) {
      const semEle = ids.filter((i) => i !== arrastando);
      mover(arrastando, semEle.indexOf(alvo.id) + (alvo.depois ? 1 : 0));
    }
    setArrastando(null);
    setAlvo(null);
  };

  return {
    anuncio,
    arrastando,
    /** Acima (false) ou abaixo (true) desta linha é onde o que se arrasta vai cair. */
    marca: (id: number) => (alvo?.id === id && arrastando !== id ? (alvo.depois ? "depois" : "antes") : null),
    linha: (id: number) => ({
      ref: (el: HTMLElement | null) => {
        if (el) elementos.current.set(id, el);
        else elementos.current.delete(id);
      },
      onDragOver: (e: DragEvent<HTMLElement>) => {
        if (arrastando === null) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
        const caixa = e.currentTarget.getBoundingClientRect();
        const depois = e.clientY > caixa.top + caixa.height / 2;
        if (alvo?.id !== id || alvo.depois !== depois) setAlvo({ id, depois });
      },
      onDrop: (e: DragEvent<HTMLElement>) => {
        if (arrastando === null) return;
        e.preventDefault();
        soltar();
      },
    }),
    alca: (id: number) => ({
      draggable: true,
      onDragStart: (e: DragEvent<HTMLElement>) => {
        e.dataTransfer.effectAllowed = "move";
        e.dataTransfer.setData("text/plain", String(id));
        const el = elementos.current.get(id);
        if (el) e.dataTransfer.setDragImage(el, 24, 24);
        setArrastando(id);
      },
      onDragEnd: () => {
        setArrastando(null);
        setAlvo(null);
      },
      onKeyDown: (e: KeyboardEvent<HTMLElement>) => {
        if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
        e.preventDefault();
        mover(id, ids.indexOf(id) + (e.key === "ArrowUp" ? -1 : 1));
      },
    }),
    /** Para os itens "Mover para cima/baixo" do menu: o mesmo caminho do teclado. */
    passo: (id: number, passo: -1 | 1) => mover(id, ids.indexOf(id) + passo),
  };
}

export type Arrastar = ReturnType<typeof useArrastar>;

/** A alça de arrastar. Fica discreta até o mouse chegar na linha ou o foco chegar nela. */
export function Alca({ arrastar, id, nome, className = "" }: { arrastar: Arrastar; id: number; nome: string; className?: string }) {
  return (
    // Não é <button>: o Firefox não deixa arrastar botão.
    <span
      role="button"
      tabIndex={0}
      {...arrastar.alca(id)}
      aria-label={`Reordenar ${nome}: arraste, ou use as setas para cima e para baixo`}
      title="Arraste para reordenar"
      // No toque não há arrastar: a alça some, e mover fica no menu ("Mover para cima/baixo").
      className={`flex h-9 w-6 shrink-0 cursor-grab items-center justify-center rounded-md text-apagado transition-colors hover:bg-canvas hover:text-tinta active:cursor-grabbing pointer-coarse:hidden ${className}`}
    >
      <GripVertical aria-hidden="true" className="size-4" />
    </span>
  );
}

/** O traço que mostra onde a linha arrastada vai cair. */
export function Marca({ onde }: { onde: "antes" | "depois" | null }) {
  if (!onde) return null;
  return <span aria-hidden="true" className={`pointer-events-none absolute inset-x-2 z-10 h-0.5 rounded-full bg-acento ${onde === "antes" ? "-top-px" : "-bottom-px"}`} />;
}
