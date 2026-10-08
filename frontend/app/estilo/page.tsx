"use client";

import { Atom, Bell, BookOpen, Box, Calculator, CalendarDays, ChartNoAxesColumn, ChartPie, ClipboardList, Ellipsis, FileText, FlaskConical, Folder, Funnel, GraduationCap, Heart, House, Info, MessageSquare, Radio, Search, Settings, SquarePlay, Star, Trophy, UserRound } from "lucide-react";
import Image from "next/image";
import type { ReactNode } from "react";
import { CartaoDoModulo } from "@/components/CartaoDoModulo";
import { Aviso, Botao, Campo, Cartao, Etiqueta } from "@/components/ui";
import type { ItemCurso, Modulo } from "@/lib/api";
import logo from "@/marca/logo.png";

// O guia de estilo da marca, montado com os componentes de verdade do portal. É aqui que o
// design confere o que saiu diferente do combinado: o que esta página mostra é o que o aluno vê.

const CORES: { nome: string; hex: string; classe: string; uso: string }[] = [
  { nome: "Deep Indigo", hex: "#0A2550", classe: "bg-tinta", uso: "Título e texto" },
  { nome: "Blueprint Blue", hex: "#1240A0", classe: "bg-acento-forte", uso: "Hover e texto sobre o claro" },
  { nome: "Bright Blueprint", hex: "#2060D0", classe: "bg-acento", uso: "Ação e link" },
  { nome: "Sky Blue", hex: "#4090E0", classe: "bg-ceu", uso: "Só preenchimento" },
  { nome: "Lavender Blue", hex: "#E8EFFF", classe: "bg-lilas", uso: "Etiqueta e item aceso" },
  { nome: "Drafting White", hex: "#DDEFFA", classe: "bg-gelo", uso: "Fundo do ícone" },
  { nome: "Success Green", hex: "#2EAE3A", classe: "bg-sucesso-vivo", uso: "Ícone e preenchimento" },
  { nome: "Warning Yellow", hex: "#F6B400", classe: "bg-atencao-vivo", uso: "Ícone e preenchimento" },
  { nome: "Alert Red", hex: "#EF4444", classe: "bg-erro-vivo", uso: "Ícone e preenchimento" },
  { nome: "Neutral Gray", hex: "#94A3B8", classe: "bg-apagado", uso: "Placeholder e desabilitado" },
];

const ICONES = [House, BookOpen, SquarePlay, FileText, Radio, Folder, CalendarDays, ChartNoAxesColumn, UserRound, Settings, Search, Bell, Info, Heart, FlaskConical, Atom, Calculator, GraduationCap, Funnel, Star, ClipboardList, ChartPie, Trophy, MessageSquare, Box, Ellipsis];

const linha = (id: number, nome: string, questao = false): ItemCurso => ({
  id,
  nome,
  ordem: id,
  status: "PUBLICADO",
  video_id: questao ? null : id,
  questao: questao ? { questao_id: id } : null,
});

const EXEMPLO: Modulo = {
  id: 0,
  nome: "K01 - Da alquimia ao modelo atômico",
  ordem: 1,
  turma: "Extensivo Q1",
  submodulos: [
    { id: 1, nome: "Aulas", tipo: "VIDEO", ordem: 1, itens: [linha(1, "Aula 1")] },
    { id: 2, nome: "Questões", tipo: "VIDEO", ordem: 2, itens: Array.from({ length: 15 }, (_, i) => linha(10 + i, `Q${String(i + 1).padStart(2, "0")}`, true)) },
  ],
};

function Bloco({ titulo, nota, children }: { titulo: string; nota?: string; children: ReactNode }) {
  return (
    <Cartao className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="text-lg font-bold text-tinta">{titulo}</h2>
        {nota && <p className="mt-1 max-w-2xl text-sm text-suave">{nota}</p>}
      </div>
      {children}
    </Cartao>
  );
}

export default function PaginaDeEstilo() {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-4 pb-16 pt-8 sm:px-6">
      <header className="mb-2 flex flex-wrap items-end justify-between gap-4">
        <div>
          <Image src={logo} alt="Rodrigo Melo" className="h-14 w-auto" priority />
          <h1 className="mt-5 text-[34px] font-bold leading-tight tracking-[-0.02em] text-tinta">Guia de estilo</h1>
          <p className="mt-1.5 max-w-2xl text-[15px] text-suave">
            Os componentes do portal, como o aluno os vê. O que estiver diferente do guia do design se aponta aqui, olhando a peça de verdade.
          </p>
        </div>
      </header>

      <Bloco titulo="Cores" nota="As cores do guia. Sky Blue, verde, amarelo e vermelho são claros demais para texto pequeno no branco: ficam para preenchimento, e o texto usa o tom escuro de cada um.">
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {CORES.map((c) => (
            <li key={c.hex} className="flex flex-col gap-1.5">
              <span className={`h-16 rounded-xl border border-borda/60 ${c.classe}`} />
              <span className="text-sm font-semibold text-tinta">{c.nome}</span>
              <span className="font-mono text-[13px] text-suave">{c.hex}</span>
              <span className="text-[13px] text-suave">{c.uso}</span>
            </li>
          ))}
        </ul>
        <p className="flex flex-wrap gap-x-6 gap-y-1 text-[15px]">
          <span className="font-semibold text-acento">Link e ação</span>
          <span className="font-semibold text-sucesso">Texto de acerto</span>
          <span className="font-semibold text-atencao">Texto de atenção</span>
          <span className="font-semibold text-erro">Texto de erro</span>
          <span className="text-suave">Texto de apoio</span>
        </p>
      </Bloco>

      <Bloco titulo="Tipografia" nota="Montserrat nos títulos, Inter no texto. O enunciado das questões fica em serifa, que casa com as fórmulas.">
        <div className="grid gap-6 md:grid-cols-2">
          <div className="flex flex-col gap-2">
            <p className="font-titulo text-[34px] font-bold leading-tight tracking-[-0.02em] text-tinta">Meu curso</p>
            <p className="font-titulo text-xl font-bold text-tinta">Da alquimia ao modelo atômico</p>
            <p className="font-titulo text-[17px] font-semibold text-tinta">Cálculos químicos</p>
            <p className="text-sm text-suave">Montserrat: 700 no título, 600 no cartão</p>
          </div>
          <div className="flex flex-col gap-2">
            <p className="text-[15px] text-tinta">Aulas e resoluções das turmas em que você está matriculado. O resultado de cada simulado sai quando ele fecha.</p>
            <p className="text-sm text-suave">Inter: 15 px no texto, 13 a 14 px no apoio</p>
            <div className="texto mt-2">Qual é a massa de 2 mol de água (H₂O)? Dados: H = 1 g/mol; O = 16 g/mol.</div>
            <p className="text-sm text-suave">Source Serif: só no texto das questões</p>
          </div>
        </div>
      </Bloco>

      <Bloco titulo="Botões" nota="Passe o mouse e clique: o primário escurece no hover e vai ao índigo ao pressionar.">
        <div className="flex flex-wrap items-center gap-3">
          <Botao variante="primario">Assistir aula</Botao>
          <Botao variante="secundario">Ver detalhes</Botao>
          <Botao>Cancelar</Botao>
          <Botao variante="perigo">Remover</Botao>
          <Botao variante="texto">Saiba mais</Botao>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Botao variante="primario" disabled>Desabilitado</Botao>
          <Botao variante="secundario" disabled>Desabilitado</Botao>
          <Botao variante="primario" tamanho="pequeno">Pequeno</Botao>
          <Botao tamanho="pequeno">Pequeno</Botao>
        </div>
      </Bloco>

      <div className="grid gap-5 lg:grid-cols-2">
        <Bloco titulo="Campos" nota="A borda é mais escura que a do guia para o campo se destacar do fundo (3:1 no branco).">
          <Campo rotulo="Seu nome">{(id) => <input id={id} className="campo" placeholder="Ex.: Pedro Lima…" />}</Campo>
          <Campo rotulo="Turma" dica="A turma define o que você vê no curso.">
            {(id) => (
              <select id={id} className="campo" defaultValue="">
                <option value="" disabled>Selecionar turma</option>
                <option>Extensivo Q1</option>
                <option>Extensivo Q2</option>
                <option>Medicina</option>
              </select>
            )}
          </Campo>
          <Campo rotulo="Desabilitado">{(id) => <input id={id} className="campo" disabled value="Não dá para editar" readOnly />}</Campo>
        </Bloco>

        <Bloco titulo="Etiquetas e avisos">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-md bg-lilas px-2 py-0.5 text-xs font-bold tracking-wide text-acento-forte">K01</span>
            <Etiqueta tom="sucesso">Concluído</Etiqueta>
            <Etiqueta tom="info">Em andamento</Etiqueta>
            <Etiqueta>Não iniciado</Etiqueta>
            <Etiqueta tom="atencao">Rascunho</Etiqueta>
            <Etiqueta tom="erro">Errou</Etiqueta>
          </div>
          <Aviso tom="sucesso" titulo="Prova entregue">O resultado sai quando o simulado fechar.</Aviso>
          <Aviso tom="atencao">Faltam menos de 5 minutos de prova.</Aviso>
          <Aviso tom="erro">Não deu para carregar. Tente de novo.</Aviso>
          <Aviso tom="info">Só vale a primeira resposta: depois de confirmar, não dá para trocar.</Aviso>
        </Bloco>
      </div>

      <Bloco titulo="Cartão de módulo" nota="A capa é o ícone ou a foto que o professor escolhe ao criar o módulo; sem escolha, o ícone sai do nome do capítulo. O código da apostila (K01) vira a etiqueta. A barra de progresso do guia entra quando a plataforma registrar as aulas assistidas.">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <CartaoDoModulo modulo={EXEMPLO} />
          {/* Com o progresso do aluno: os dez primeiros itens já feitos. */}
          <CartaoDoModulo
            comProgresso
            modulo={{ ...EXEMPLO, id: -1, nome: "K03 - Estequiometria", submodulos: EXEMPLO.submodulos.map((s) => ({ ...s, itens: s.itens.map((i, n) => ({ ...i, concluido: n < 9 })) })) }}
          />
          <CartaoDoModulo modulo={{ ...EXEMPLO, id: -2, nome: "K05 - Separação de misturas", icone: "gota" }} />
        </div>
      </Bloco>

      <Bloco titulo="Ícones" nota="Lucide, traço fino. É a família linear que mais se aproxima do guia.">
        <ul aria-hidden="true" className="grid grid-cols-6 gap-2 sm:grid-cols-9 lg:grid-cols-13">
          {ICONES.map((Icone, i) => (
            <li key={i} className="flex aspect-square items-center justify-center rounded-xl bg-gelo/50 text-tinta">
              <Icone aria-hidden="true" className="size-6" strokeWidth={1.7} />
            </li>
          ))}
        </ul>
      </Bloco>
    </main>
  );
}
