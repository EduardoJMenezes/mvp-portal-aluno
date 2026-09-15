"use client";

import Link from "next/link";
import { Cartao, Pagina } from "@/components/ui";

const CAMINHOS = [
  {
    href: "/admin/importar/vimeo/",
    titulo: "Pasta do Vimeo para o curso",
    texto: "Os vídeos de resolução da apostila entram nos sub-módulos pela faixa de números (1-14 no K01, 15-30 no K02).",
    resultado: "Um rascunho por sub-módulo",
  },
  {
    href: "/admin/importar/docx/",
    titulo: "Simulado em .docx",
    texto: "O arquivo da equipe é lido por regras: questões, alternativas, gabarito e figuras. O que não fechou, você completa pelos blocos.",
    resultado: "Um rascunho de simulado",
  },
  {
    href: "/admin/importar/prints/",
    titulo: "Prints de questões",
    texto: "Prints de prova, PDF ou site. As figuras saem recortadas do print original para dentro das questões em rascunho.",
    resultado: "Figuras nas questões",
  },
];

export default function Importar() {
  return (
    <Pagina titulo="Importar" legenda="Tudo o que chega por aqui vira rascunho. Nada aparece para os alunos antes da sua aprovação.">
      <ul className="grid gap-4 md:grid-cols-3">
        {CAMINHOS.map((c) => (
          <Cartao key={c.href} como="li" className="flex flex-col transition-shadow hover:shadow-suave">
            <Link href={c.href} className="flex flex-1 flex-col gap-2 p-5">
              <span className="text-lg font-semibold text-tinta">{c.titulo}</span>
              <span className="flex-1 text-[15px] text-suave">{c.texto}</span>
              <span className="mt-2 text-[13px] font-semibold uppercase tracking-wide text-acento">{c.resultado} →</span>
            </Link>
          </Cartao>
        ))}
      </ul>
      <p className="text-[15px] text-suave">
        Pelo Claude dá para fazer o mesmo conversando — ele gera o link de envio e revisa com você. Veja <Link href="/admin/claude/" className="font-semibold text-acento hover:underline">Conectar ao Claude</Link>.
      </p>
    </Pagina>
  );
}
