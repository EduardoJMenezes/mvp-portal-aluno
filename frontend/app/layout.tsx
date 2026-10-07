import type { Metadata, Viewport } from "next";
import { Inter, Montserrat, Source_Serif_4 } from "next/font/google";
import { Sessao } from "@/lib/sessao";
import "./globals.css";

// O guia de estilo: Montserrat nos títulos, Inter no texto. A serifa é só do texto das questões.
const sans = Inter({ subsets: ["latin", "latin-ext"], variable: "--fonte-sans", display: "swap" });
const titulo = Montserrat({ subsets: ["latin", "latin-ext"], variable: "--fonte-titulo", display: "swap" });
const serifa = Source_Serif_4({ subsets: ["latin", "latin-ext"], variable: "--fonte-serifa", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Rodrigo Melo · Química", template: "%s · Rodrigo Melo" },
  description: "Química para grandes conquistas: aulas, materiais, simulados e questões num só lugar.",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = { themeColor: "#ffffff", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR" className={`${sans.variable} ${titulo.variable} ${serifa.variable}`}>
      <body>
        <Sessao>{children}</Sessao>
      </body>
    </html>
  );
}
