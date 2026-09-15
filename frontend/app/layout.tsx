import type { Metadata, Viewport } from "next";
import { DM_Sans, Source_Serif_4 } from "next/font/google";
import { Sessao } from "@/lib/sessao";
import "./globals.css";

const sans = DM_Sans({ subsets: ["latin", "latin-ext"], variable: "--fonte-sans", display: "swap" });
const serifa = Source_Serif_4({ subsets: ["latin", "latin-ext"], variable: "--fonte-serifa", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Plataforma Educacional", template: "%s · Plataforma Educacional" },
  description: "Curso em vídeo e simulados da turma.",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = { themeColor: "#ffffff", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR" className={`${sans.variable} ${serifa.variable}`}>
      <body>
        <Sessao>{children}</Sessao>
      </body>
    </html>
  );
}
