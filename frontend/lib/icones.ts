import {
  Atom, Battery, Beaker, BookOpen, Box, Calculator, ChartNoAxesColumn, ClipboardList, Dna, Droplets, FlaskConical, Flame, Funnel, Gauge,
  GraduationCap, Grid3x3, Hexagon, Layers, Leaf, Lightbulb, Microscope, Orbit, Pipette, Radiation, Recycle, Scale, Star, TestTube,
  Thermometer, Trophy, Waypoints, Zap, type LucideIcon,
} from "lucide-react";

// A capa do cartão do módulo. O professor escolhe um ícone deste catálogo (ou sobe uma foto) ao
// criar o módulo; sem escolha, o ícone sai do nome do capítulo.
//
// Os nomes são os mesmos de IconeDoModulo.java, que é quem valida o que se grava: ícone novo
// entra nos dois lugares.
export const CATALOGO: { nome: string; rotulo: string; Icone: LucideIcon }[] = [
  { nome: "atomo", rotulo: "Átomo", Icone: Atom },
  { nome: "frasco", rotulo: "Erlenmeyer", Icone: FlaskConical },
  { nome: "bequer", rotulo: "Béquer", Icone: Beaker },
  { nome: "tubo-de-ensaio", rotulo: "Tubo de ensaio", Icone: TestTube },
  { nome: "pipeta", rotulo: "Pipeta", Icone: Pipette },
  { nome: "microscopio", rotulo: "Microscópio", Icone: Microscope },
  { nome: "calculadora", rotulo: "Calculadora", Icone: Calculator },
  { nome: "tabela", rotulo: "Tabela periódica", Icone: Grid3x3 },
  { nome: "ligacoes", rotulo: "Ligações", Icone: Waypoints },
  { nome: "hexagono", rotulo: "Anel da orgânica", Icone: Hexagon },
  { nome: "cubo", rotulo: "Cubo", Icone: Box },
  { nome: "funil", rotulo: "Funil", Icone: Funnel },
  { nome: "gota", rotulo: "Gotas", Icone: Droplets },
  { nome: "chama", rotulo: "Chama", Icone: Flame },
  { nome: "termometro", rotulo: "Termômetro", Icone: Thermometer },
  { nome: "raio", rotulo: "Raio", Icone: Zap },
  { nome: "bateria", rotulo: "Pilha", Icone: Battery },
  { nome: "balanca", rotulo: "Balança", Icone: Scale },
  { nome: "velocimetro", rotulo: "Velocímetro", Icone: Gauge },
  { nome: "orbita", rotulo: "Órbita", Icone: Orbit },
  { nome: "radiacao", rotulo: "Radiação", Icone: Radiation },
  { nome: "dna", rotulo: "DNA", Icone: Dna },
  { nome: "folha", rotulo: "Folha", Icone: Leaf },
  { nome: "reciclagem", rotulo: "Reciclagem", Icone: Recycle },
  { nome: "camadas", rotulo: "Camadas", Icone: Layers },
  { nome: "grafico", rotulo: "Gráfico", Icone: ChartNoAxesColumn },
  { nome: "livro", rotulo: "Livro", Icone: BookOpen },
  { nome: "prancheta", rotulo: "Prancheta", Icone: ClipboardList },
  { nome: "capelo", rotulo: "Capelo", Icone: GraduationCap },
  { nome: "trofeu", rotulo: "Troféu", Icone: Trophy },
  { nome: "estrela", rotulo: "Estrela", Icone: Star },
  { nome: "lampada", rotulo: "Lâmpada", Icone: Lightbulb },
];

// O palpite pelo assunto, para o módulo sem capa escolhida. O que não casa com nada leva o béquer.
const POR_ASSUNTO: [RegExp, string][] = [
  [/atom|alquimia/, "atomo"],
  [/calculo|grandeza|\bmol\b/, "calculadora"],
  [/tabela|periodic/, "tabela"],
  [/ligac|geometria molecular/, "ligacoes"],
  [/separac|mistura/, "funil"],
  [/estrutura|materia|estados fisicos/, "cubo"],
  [/organic|carbono|hidrocarboneto|isomeria/, "hexagono"],
  [/cinetic|velocidade/, "velocimetro"],
  [/termo|entalpia|calor/, "chama"],
  [/eletro|pilha|oxirreduc/, "raio"],
  [/equilibrio|\bph\b|acido|base/, "balanca"],
  [/radioativ|nuclear/, "radiacao"],
  [/estequiometr|reac|soluc|titulac/, "frasco"],
];

const semAcento = (texto: string) => texto.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

const doCatalogo = (nome: string | null | undefined) => CATALOGO.find((c) => c.nome === nome);

/** O ícone que o portal escolheria sozinho para um módulo com este nome. */
export function palpiteDeIcone(nomeDoModulo: string): string {
  const plano = semAcento(nomeDoModulo);
  return POR_ASSUNTO.find(([padrao]) => padrao.test(plano))?.[1] ?? "bequer";
}

/** O ícone do módulo: o que o professor escolheu, ou o palpite pelo nome. */
export function iconeDoModulo(modulo: { nome: string; icone?: string | null }): LucideIcon {
  return (doCatalogo(modulo.icone) ?? doCatalogo(palpiteDeIcone(modulo.nome)))!.Icone;
}

/** "K01 - Da alquimia ao modelo atômico" vira o código da apostila e o título, cada um no seu lugar. */
export function partesDoNome(nome: string): { codigo: string | null; titulo: string } {
  const achado = /^\s*([A-Za-z]{1,2}\d{1,3})\s*[-–—:.]\s*(.+)$/.exec(nome);
  return achado ? { codigo: achado[1].toUpperCase(), titulo: achado[2].trim() } : { codigo: null, titulo: nome };
}
