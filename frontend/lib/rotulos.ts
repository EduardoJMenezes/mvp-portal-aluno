import type { Tom } from "@/components/ui";
import type { Situacao } from "./api";

export const DIFICULDADE: Record<string, string> = { FACIL: "Fácil", MEDIA: "Média", DIFICIL: "Difícil" };

export const SITUACAO: Record<Situacao, [Tom, string]> = {
  RASCUNHO: ["atencao", "Rascunho"],
  AGENDADO: ["neutro", "Agendado"],
  ABERTO: ["info", "Aberto"],
  ENCERRADO: ["sucesso", "Encerrado"],
};

export const LETRAS = ["A", "B", "C", "D", "E"] as const;
