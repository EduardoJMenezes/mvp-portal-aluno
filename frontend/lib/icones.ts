import { Atom, Beaker, Box, Calculator, FlaskConical, Flame, Funnel, Gauge, Grid3x3, Hexagon, Scale, Waypoints, Zap, type LucideIcon } from "lucide-react";

// O ícone do cartão do módulo. Enquanto o professor não escolhe um (ou uma foto) ao criar o
// módulo, ele sai do nome do capítulo: é um palpite pelo assunto, e o que não casa com nada
// leva o béquer.
const POR_ASSUNTO: [RegExp, LucideIcon][] = [
  [/atom|alquimia/, Atom],
  [/calculo|grandeza|\bmol\b/, Calculator],
  [/tabela|periodic/, Grid3x3],
  [/ligac|geometria molecular/, Waypoints],
  [/separac|mistura/, Funnel],
  [/estrutura|materia|estados fisicos/, Box],
  [/organic|carbono|hidrocarboneto|isomeria/, Hexagon],
  [/cinetic|velocidade/, Gauge],
  [/termo|entalpia|calor/, Flame],
  [/eletro|pilha|oxirreduc/, Zap],
  [/equilibrio|\bph\b|acido|base/, Scale],
  [/estequiometr|reac|soluc|titulac/, FlaskConical],
];

const semAcento = (texto: string) => texto.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

export function iconeDoModulo(nome: string): LucideIcon {
  const plano = semAcento(nome);
  return POR_ASSUNTO.find(([padrao]) => padrao.test(plano))?.[1] ?? Beaker;
}

/** "K01 - Da alquimia ao modelo atômico" vira o código da apostila e o título, cada um no seu lugar. */
export function partesDoNome(nome: string): { codigo: string | null; titulo: string } {
  const achado = /^\s*([A-Za-z]{1,2}\d{1,3})\s*[-–—:.]\s*(.+)$/.exec(nome);
  return achado ? { codigo: achado[1].toUpperCase(), titulo: achado[2].trim() } : { codigo: null, titulo: nome };
}
