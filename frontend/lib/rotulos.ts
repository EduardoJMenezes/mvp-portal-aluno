import type { Tom } from "@/components/ui";
import type { Situacao } from "./api";

export const TIPO_DE_RASCUNHO: Record<string, string> = { ITENS: "Vídeos do curso", QUESTOES: "Questões", SIMULADO: "Simulado" };

export const ORIGEM: Record<string, string> = { MCP: "Claude (MCP)", PORTAL: "portal", DOCX: ".docx" };

export const DIFICULDADE: Record<string, string> = { FACIL: "Fácil", MEDIA: "Média", DIFICIL: "Difícil" };

export const SITUACAO: Record<Situacao, [Tom, string]> = {
  RASCUNHO: ["atencao", "Rascunho"],
  AGENDADO: ["neutro", "Agendado"],
  ABERTO: ["info", "Aberto"],
  ENCERRADO: ["sucesso", "Encerrado"],
};

export const LETRAS = ["A", "B", "C", "D", "E"] as const;
