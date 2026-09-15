"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { MouseEvent, ReactNode } from "react";
import { ehOperador, useSessao, useUsuario } from "@/lib/sessao";

type Destino = { href: string; rotulo: string; exato?: boolean };

const DO_ALUNO: Destino[] = [
  { href: "/", rotulo: "Início", exato: true },
  { href: "/curso/", rotulo: "Curso" },
  { href: "/simulados/", rotulo: "Simulados" },
  { href: "/desempenho/", rotulo: "Desempenho" },
];

const DO_OPERADOR: Destino[] = [
  { href: "/admin/", rotulo: "Painel", exato: true },
  { href: "/admin/turmas/", rotulo: "Turmas" },
  { href: "/admin/rascunhos/", rotulo: "Rascunhos" },
  { href: "/admin/questoes/", rotulo: "Questões" },
  { href: "/admin/simulados/", rotulo: "Simulados" },
  { href: "/admin/importar/", rotulo: "Importar" },
  { href: "/admin/assuntos/", rotulo: "Assuntos" },
];

const semBarra = (caminho: string) => (caminho.length > 1 ? caminho.replace(/\/+$/, "") : caminho);

function ativo(caminho: string, destino: Destino) {
  const alvo = semBarra(destino.href);
  return destino.exato ? caminho === alvo : caminho === alvo || caminho.startsWith(`${alvo}/`);
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
  const destinos = ehOperador(usuario) ? DO_OPERADOR : DO_ALUNO;
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
              {destinos.map((d) => (
                <Link key={d.href} href={d.href} onClick={fecharMenu} aria-current={ativo(caminho, d) ? "page" : undefined}
                  className={`rounded-md px-3 py-2 text-[15px] font-medium ${ativo(caminho, d) ? "bg-lilas text-acento-forte" : "text-tinta hover:bg-canvas"}`}>
                  {d.rotulo}
                </Link>
              ))}
            </nav>
          </details>

          <Link href={ehOperador(usuario) ? "/admin/" : "/"} className="flex items-center gap-2 font-semibold text-tinta">
            <span className="flex size-8 items-center justify-center rounded-lg bg-acento text-sm font-bold text-white" aria-hidden="true">P</span>
            <span className="hidden text-[15px] sm:inline">Plataforma Educacional</span>
          </Link>

          <nav aria-label="Principal" className="ml-4 hidden items-center gap-0.5 md:flex">
            {destinos.map((d) => (
              <Link key={d.href} href={d.href} aria-current={ativo(caminho, d) ? "page" : undefined}
                className={`rounded-full px-3 py-1.5 text-[15px] font-medium transition-colors ${ativo(caminho, d) ? "bg-lilas text-acento-forte" : "text-suave hover:text-tinta"}`}>
                {d.rotulo}
              </Link>
            ))}
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
