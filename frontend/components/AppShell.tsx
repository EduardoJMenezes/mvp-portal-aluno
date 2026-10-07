"use client";

import { BookOpen, CalendarDays, ChartNoAxesColumn, ChevronDown, FileText, Folder, House, Menu, Radio, type LucideIcon } from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { Suspense, useEffect, type MouseEvent, type ReactNode } from "react";
import { api, useDados, type BotaoDoAluno, type Funcionalidade } from "@/lib/api";
import { ehOperador, useSessao, useUsuario } from "@/lib/sessao";
import logo from "@/marca/logo.png";
import marca from "@/marca/q.png";

/** `categoria` só nos botões montados pelo professor: é o que separa dois botões da mesma tela. */
type Destino = { href: string; rotulo: string; icone?: LucideIcon; exato?: boolean; categoria?: string | null; aoVivo?: BotaoDoAluno["ao_vivo"] };

const ROTA: Record<Funcionalidade, string> = { CURSO: "/curso/", AULAS: "/aulas/", SIMULADOS: "/simulados/", MATERIAIS: "/materiais/", AGENDA: "/agenda/" };

// O ícone é da funcionalidade, não do rótulo: dois botões de curso, com nomes que o professor
// escolheu, levam o mesmo livro.
const ICONE: Record<Funcionalidade, LucideIcon> = { CURSO: BookOpen, AULAS: Radio, SIMULADOS: FileText, MATERIAIS: Folder, AGENDA: CalendarDays };

/** Início e Desempenho são fixos; o meio é o menu que o professor montou para a turma (decisão 0009). */
function doAluno(menu: BotaoDoAluno[]): Destino[] {
  return [
    { href: "/", rotulo: "Início", icone: House, exato: true },
    ...menu.map((b) => ({
      href: ROTA[b.funcionalidade] + (b.categoria ? `?categoria=${encodeURIComponent(b.categoria)}` : ""),
      rotulo: b.rotulo,
      icone: ICONE[b.funcionalidade],
      categoria: b.categoria ?? null,
      aoVivo: b.ao_vivo,
    })),
    { href: "/desempenho/", rotulo: "Desempenho", icone: ChartNoAxesColumn },
  ];
}

/** Um botão do menu do professor que abre uma lista: as telas que andam juntas no trabalho dele. */
type Grupo = { rotulo: string; itens: Destino[] };

// Eram doze links numa fila. Agora são cinco, pelo que o professor veio fazer: montar o curso,
// preparar prova, cuidar das turmas — e os rascunhos, que são a caixa de entrada do que o Claude propõe.
const DO_OPERADOR: (Destino | Grupo)[] = [
  { href: "/admin/", rotulo: "Painel", exato: true },
  {
    rotulo: "Curso",
    itens: [
      { href: "/admin/biblioteca/", rotulo: "Montar o curso" },
      { href: "/admin/aulas/", rotulo: "Aulas ao vivo" },
      { href: "/admin/agenda/", rotulo: "Agenda" },
      { href: "/admin/materiais/", rotulo: "Materiais" },
    ],
  },
  {
    rotulo: "Simulados e questões",
    itens: [
      { href: "/admin/simulados/", rotulo: "Simulados" },
      { href: "/admin/questoes/", rotulo: "Banco de questões" },
      { href: "/admin/assuntos/", rotulo: "Assuntos" },
      { href: "/admin/importar/", rotulo: "Importar" },
    ],
  },
  {
    rotulo: "Turmas",
    itens: [
      { href: "/admin/turmas/", rotulo: "Turmas e alunos" },
      { href: "/admin/vendas/", rotulo: "Vendas" },
    ],
  },
  { href: "/admin/rascunhos/", rotulo: "Rascunhos" },
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
        <span aria-hidden="true" className="absolute inline-flex size-full rounded-full bg-erro-vivo opacity-75 motion-safe:animate-ping" />
        <span aria-hidden="true" className="relative inline-flex size-2 rounded-full bg-erro-vivo" />
        <span className="sr-only">(ao vivo agora)</span>
      </span>
    );
  if (estado === "EM_BREVE")
    return (
      <span className="size-2 rounded-full bg-atencao-vivo">
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
    const Icone = d.icone;
    return (
      <Link key={d.href + d.rotulo} href={d.href} onClick={fecharMenu} aria-current={aceso ? "page" : undefined} className={classe(aceso)}>
        {Icone && <Icone aria-hidden="true" className="size-[18px] shrink-0" strokeWidth={1.9} />}
        {d.rotulo}
        <AoVivo estado={d.aoVivo} />
      </Link>
    );
  });
}

/** O menu do professor: links soltos e grupos que abrem uma lista. `gaveta` é a versão do celular, tudo aberto. */
function NavDoOperador({ caminho, gaveta, classe }: { caminho: string; gaveta?: boolean; classe: (ativo: boolean) => string }) {
  return DO_OPERADOR.map((entrada) => {
    if ("href" in entrada) {
      const aceso = ativo(caminho, null, entrada);
      return (
        <Link key={entrada.href} href={entrada.href} onClick={fecharMenu} aria-current={aceso ? "page" : undefined} className={classe(aceso)}>
          {entrada.rotulo}
        </Link>
      );
    }
    const links = entrada.itens.map((d) => {
      const aceso = ativo(caminho, null, d);
      return (
        <Link
          key={d.href}
          href={d.href}
          onClick={fecharMenu}
          aria-current={aceso ? "page" : undefined}
          className={`block rounded-lg px-3 py-2 text-[15px] font-medium ${aceso ? "bg-lilas text-acento-forte" : "text-tinta hover:bg-canvas"}`}
        >
          {d.rotulo}
        </Link>
      );
    });
    if (gaveta) {
      return (
        <div key={entrada.rotulo} role="group" aria-label={entrada.rotulo} className="mt-1.5 border-t border-borda pt-1.5">
          <p className="px-3 pb-0.5 pt-1 text-[13px] font-semibold text-suave">{entrada.rotulo}</p>
          {links}
        </div>
      );
    }
    const aceso = entrada.itens.some((d) => ativo(caminho, null, d));
    return (
      // `name` igual em todos: abrir um grupo fecha o outro.
      <details key={entrada.rotulo} name="menu-do-professor" className="group/nav relative shrink-0">
        <summary className={`${classe(aceso)} cursor-pointer list-none [&::-webkit-details-marker]:hidden`}>
          {entrada.rotulo}
          <ChevronDown aria-hidden="true" className="size-4 transition-transform group-open/nav:rotate-180" />
        </summary>
        <div className="absolute left-0 top-[calc(100%+0.75rem)] flex w-56 flex-col gap-0.5 rounded-cartao border border-borda bg-papel p-1.5 shadow-suave">{links}</div>
      </details>
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
  const destinos = doAluno(menu.dados ?? []);

  // Os menus do cabeçalho são <details>: fecham ao clicar fora e com Esc, como se espera de um menu.
  useEffect(() => {
    const abertos = () => document.querySelectorAll<HTMLDetailsElement>("header details[open]");
    const fora = (e: PointerEvent) => abertos().forEach((d) => !d.contains(e.target as Node) && (d.open = false));
    const tecla = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      abertos().forEach((d) => {
        d.open = false;
        d.querySelector("summary")?.focus();
      });
    };
    document.addEventListener("pointerdown", fora);
    document.addEventListener("keydown", tecla);
    return () => {
      document.removeEventListener("pointerdown", fora);
      document.removeEventListener("keydown", tecla);
    };
  }, []);
  const iniciais = usuario.nome
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join("");

  // O item aceso leva o fundo claro e o traço na base do cabeçalho, como no guia.
  const naBarra = (aceso: boolean) =>
    `relative flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-[15px] font-medium transition-colors ${
      aceso
        ? "bg-lilas text-acento-forte after:absolute after:inset-x-3 after:-bottom-3 after:h-0.5 after:rounded-full after:bg-acento"
        : "text-suave hover:bg-canvas hover:text-tinta"
    }`;

  return (
    <>
      <a href="#conteudo" className="sr-only z-50 rounded-campo bg-acento px-4 py-2 text-white focus:not-sr-only focus:fixed focus:left-4 focus:top-3">
        Pular para o conteúdo
      </a>
      <header className="sticky top-0 z-30 border-b border-borda/70 bg-papel">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-2 px-4 sm:px-6">
          <details className="relative md:hidden">
            <summary className="flex size-10 cursor-pointer list-none items-center justify-center rounded-campo border border-borda text-tinta [&::-webkit-details-marker]:hidden" aria-label="Menu">
              <Menu aria-hidden="true" className="size-5" />
            </summary>
            <nav aria-label="Principal" className="absolute left-0 top-12 flex w-60 flex-col gap-0.5 rounded-cartao border border-borda bg-papel p-1.5 shadow-suave">
              {operador ? (
                <NavDoOperador caminho={caminho} gaveta classe={(aceso) => `block rounded-lg px-3 py-2 text-[15px] font-medium ${aceso ? "bg-lilas text-acento-forte" : "text-tinta hover:bg-canvas"}`} />
              ) : (
                <Suspense>
                  <Links destinos={destinos} caminho={caminho}
                    classe={(aceso) => `flex items-center gap-2.5 rounded-lg px-3 py-2.5 text-[15px] font-medium ${aceso ? "bg-lilas text-acento-forte" : "text-tinta hover:bg-canvas"}`} />
                </Suspense>
              )}
            </nav>
          </details>

          <Link href={operador ? "/admin/" : "/"} className="flex shrink-0 items-center rounded-lg" aria-label="Rodrigo Melo Química: página inicial">
            <Image src={marca} alt="" priority className="h-10 w-auto lg:hidden" />
            <Image src={logo} alt="" priority className="hidden h-12 w-auto lg:block" />
          </Link>

          {/* A fila do aluno rola de lado se o professor montou muitos botões; a do professor não pode
              rolar, ou cortaria as listas que os grupos abrem. */}
          <nav aria-label="Principal" className={`ml-3 hidden h-full min-w-0 items-center gap-0.5 md:flex ${operador ? "" : "overflow-x-auto"}`}>
            {operador ? (
              <NavDoOperador caminho={caminho} classe={naBarra} />
            ) : (
              <Suspense>
                <Links destinos={destinos} caminho={caminho} classe={naBarra} />
              </Suspense>
            )}
          </nav>

          <details className="relative ml-auto shrink-0">
            <summary className="flex cursor-pointer list-none items-center gap-2 rounded-full py-1 pl-1 pr-2 hover:bg-canvas [&::-webkit-details-marker]:hidden">
              <span className="flex size-9 items-center justify-center rounded-full bg-lilas text-xs font-bold text-acento-forte" aria-hidden="true">{iniciais}</span>
              <span className="hidden max-w-40 truncate text-sm font-medium text-tinta sm:inline">{usuario.nome}</span>
              <ChevronDown aria-hidden="true" className="hidden size-4 text-suave sm:block" />
            </summary>
            <div className="absolute right-0 top-12 w-64 rounded-cartao border border-borda bg-papel p-1.5 shadow-suave">
              <div className="border-b border-borda px-3 pb-2.5 pt-1.5">
                <p className="truncate font-semibold text-tinta">{usuario.nome}</p>
                <p className="truncate text-[13px] text-suave">{usuario.email}</p>
                <p className="mt-1 text-[13px] text-suave">
                  {usuario.papel === "ALUNO" ? "Aluno" : usuario.papel === "ADMIN" ? "Administrador" : "Gerenciador"}
                  {usuario.turmas.length > 0 && `, ${usuario.turmas.join(", ")}`}
                </p>
              </div>
              <Link href="/conta/" onClick={fecharMenu} className="mt-1 block rounded-lg px-3 py-2 text-[15px] text-tinta hover:bg-canvas">
                Minha conta
              </Link>
              {operador && (
                <Link href="/admin/claude/" onClick={fecharMenu} className="block rounded-lg px-3 py-2 text-[15px] text-tinta hover:bg-canvas">
                  Conectar ao Claude
                </Link>
              )}
              <button type="button" onClick={() => void sair()} className="block w-full rounded-lg px-3 py-2 text-left text-[15px] text-erro hover:bg-erro-fundo">
                Sair
              </button>
            </div>
          </details>
        </div>
      </header>
      <div id="conteudo" className="arcos">{children}</div>
    </>
  );
}
