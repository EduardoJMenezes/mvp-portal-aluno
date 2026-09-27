"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { Suspense, type MouseEvent, type ReactNode } from "react";
import { api, useDados, type BotaoDoAluno, type Funcionalidade } from "@/lib/api";
import { ehOperador, useSessao, useUsuario } from "@/lib/sessao";

/** `categoria` só nos botões montados pelo professor: é o que separa dois botões da mesma tela. */
type Destino = { href: string; rotulo: string; exato?: boolean; categoria?: string | null; aoVivo?: BotaoDoAluno["ao_vivo"] };

const ROTA: Record<Funcionalidade, string> = { CURSO: "/curso/", AULAS: "/aulas/", SIMULADOS: "/simulados/", MATERIAIS: "/materiais/" };

/** Início e Desempenho são fixos; o meio é o menu que o professor montou para a turma (decisão 0009). */
function doAluno(menu: BotaoDoAluno[]): Destino[] {
  return [
    { href: "/", rotulo: "Início", exato: true },
    ...menu.map((b) => ({
      href: ROTA[b.funcionalidade] + (b.categoria ? `?categoria=${encodeURIComponent(b.categoria)}` : ""),
      rotulo: b.rotulo,
      categoria: b.categoria ?? null,
      aoVivo: b.ao_vivo,
    })),
    { href: "/desempenho/", rotulo: "Desempenho" },
  ];
}

const DO_OPERADOR: Destino[] = [
  { href: "/admin/", rotulo: "Painel", exato: true },
  { href: "/admin/turmas/", rotulo: "Turmas" },
  { href: "/admin/biblioteca/", rotulo: "Aulas" },
  { href: "/admin/aulas/", rotulo: "Aulas ao vivo" },
  { href: "/admin/rascunhos/", rotulo: "Rascunhos" },
  { href: "/admin/questoes/", rotulo: "Questões" },
  { href: "/admin/simulados/", rotulo: "Simulados" },
  { href: "/admin/vendas/", rotulo: "Vendas" },
  { href: "/admin/materiais/", rotulo: "Materiais" },
  { href: "/admin/importar/", rotulo: "Importar" },
  { href: "/admin/assuntos/", rotulo: "Assuntos" },
];

const semBarra = (caminho: string) => (caminho.length > 1 ? caminho.replace(/\/+$/, "") : caminho);

function ativo(caminho: string, categoria: string | null, destino: Destino) {
  const alvo = semBarra(destino.href.split("?")[0]);
  const naRota = destino.exato ? caminho === alvo : caminho === alvo || caminho.startsWith(`${alvo}/`);
  return naRota && (destino.categoria === undefined || (destino.categoria ?? null) === categoria);
}

/** Bolinha vermelha piscando: ao vivo agora. Âmbar parada: a sala já abriu. */
function AoVivo({ estado }: { estado: Destino["aoVivo"] }) {
  if (estado === "AGORA")
    return (
      <span className="relative flex size-2">
        <span aria-hidden="true" className="absolute inline-flex size-full rounded-full bg-erro opacity-75 motion-safe:animate-ping" />
        <span aria-hidden="true" className="relative inline-flex size-2 rounded-full bg-erro" />
        <span className="sr-only">(ao vivo agora)</span>
      </span>
    );
  if (estado === "EM_BREVE")
    return (
      <span className="size-2 rounded-full bg-atencao">
        <span className="sr-only">(começa em breve)</span>
      </span>
    );
  return null;
}

/** A lista de links, lida junto com a categoria da URL para acender o botão certo. */
function Links({ destinos, caminho, classe }: { destinos: Destino[]; caminho: string; classe: (ativo: boolean) => string }) {
  const categoria = useSearchParams().get("categoria")?.trim() || null;
  return destinos.map((d) => {
    const aceso = ativo(caminho, categoria, d);
    return (
      <Link key={d.href + d.rotulo} href={d.href} onClick={fecharMenu} aria-current={aceso ? "page" : undefined} className={classe(aceso)}>
        {d.rotulo}
        <AoVivo estado={d.aoVivo} />
      </Link>
    );
  });
}

// Link dentro de <details> fecha o menu ao navegar.
const fecharMenu = (e: MouseEvent<HTMLElement>) => {
  const menu = e.currentTarget.closest("details");
  if (menu) menu.open = false;
};

export function AppShell({ children }: { children: ReactNode }) {
  const usuario = useUsuario();
  const { sair } = useSessao();
  const caminho = semBarra(usePathname() ?? "/");
  const operador = ehOperador(usuario);
  // O aluno relê o menu a cada minuto: é assim que a bolinha de ao vivo acende sozinha.
  const menu = useDados(() => (operador ? Promise.resolve([]) : api.menu()), [operador], operador ? undefined : 60);
  const destinos = operador ? DO_OPERADOR : doAluno(menu.dados ?? []);
  const iniciais = usuario.nome
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join("");

  return (
    <>
      <a href="#conteudo" className="sr-only z-50 rounded-full bg-acento px-4 py-2 text-white focus:not-sr-only focus:fixed focus:left-4 focus:top-3">
        Pular para o conteúdo
      </a>
      <header className="sticky top-0 z-30 bg-papel shadow-suave">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-3 px-4 sm:px-6">
          <details className="relative md:hidden">
            <summary className="flex size-10 cursor-pointer list-none items-center justify-center rounded-full border border-borda text-tinta [&::-webkit-details-marker]:hidden" aria-label="Menu">
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true"><path d="M2 4h14M2 9h14M2 14h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
            </summary>
            <nav aria-label="Principal" className="absolute left-0 top-12 flex w-56 flex-col rounded-cartao border border-borda bg-papel p-1.5 shadow-suave">
              <Suspense>
                <Links destinos={destinos} caminho={caminho}
                  classe={(aceso) => `flex items-center gap-2 rounded-md px-3 py-2 text-[15px] font-medium ${aceso ? "bg-lilas text-acento-forte" : "text-tinta hover:bg-canvas"}`} />
              </Suspense>
            </nav>
          </details>

          <Link href={ehOperador(usuario) ? "/admin/" : "/"} className="flex items-center gap-2 font-semibold text-tinta">
            <span className="flex size-8 items-center justify-center rounded-lg bg-acento text-sm font-bold text-white" aria-hidden="true">P</span>
            <span className="hidden text-[15px] sm:inline">Plataforma Educacional</span>
          </Link>

          <nav aria-label="Principal" className="ml-4 hidden items-center gap-0.5 md:flex">
            <Suspense>
              <Links destinos={destinos} caminho={caminho}
                classe={(aceso) => `flex items-center gap-1.5 whitespace-nowrap rounded-full px-3 py-1.5 text-[15px] font-medium transition-colors ${aceso ? "bg-lilas text-acento-forte" : "text-suave hover:text-tinta"}`} />
            </Suspense>
          </nav>

          <details className="relative ml-auto">
            <summary className="flex cursor-pointer list-none items-center gap-2 rounded-full py-1 pl-1 pr-3 hover:bg-canvas [&::-webkit-details-marker]:hidden">
              <span className="flex size-8 items-center justify-center rounded-full bg-lilas text-xs font-bold text-acento-forte" aria-hidden="true">{iniciais}</span>
              <span className="hidden max-w-40 truncate text-sm font-medium text-tinta sm:inline">{usuario.nome}</span>
            </summary>
            <div className="absolute right-0 top-12 w-64 rounded-cartao border border-borda bg-papel p-1.5 shadow-suave">
              <div className="border-b border-borda px-3 pb-2.5 pt-1.5">
                <p className="truncate font-semibold text-tinta">{usuario.nome}</p>
                <p className="truncate text-[13px] text-suave">{usuario.email}</p>
                <p className="mt-1 text-[13px] text-suave">
                  {usuario.papel === "ALUNO" ? "Aluno" : usuario.papel === "ADMIN" ? "Administrador" : "Gerenciador"}
                  {usuario.turmas.length > 0 && ` · ${usuario.turmas.join(", ")}`}
                </p>
              </div>
              <Link href="/conta/" onClick={fecharMenu} className="mt-1 block rounded-md px-3 py-2 text-[15px] text-tinta hover:bg-canvas">
                Minha conta
              </Link>
              {ehOperador(usuario) && (
                <Link href="/admin/claude/" onClick={fecharMenu} className="block rounded-md px-3 py-2 text-[15px] text-tinta hover:bg-canvas">
                  Conectar ao Claude
                </Link>
              )}
              <button type="button" onClick={() => void sair()} className="block w-full rounded-md px-3 py-2 text-left text-[15px] text-erro hover:bg-erro-fundo">
                Sair
              </button>
            </div>
          </details>
        </div>
      </header>
      <div id="conteudo">{children}</div>
    </>
  );
}
