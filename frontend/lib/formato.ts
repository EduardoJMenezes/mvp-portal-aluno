// Agenda e prazo saem do backend em ISO; na tela, sempre no horário de Brasília.
const DATA_HORA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  dateStyle: "short",
  timeStyle: "short",
});
const DATA_LONGA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  weekday: "short",
  day: "2-digit",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
});

export function emBrasilia(iso?: string | null): string {
  if (!iso) return "—";
  const data = new Date(iso);
  return Number.isNaN(data.getTime()) ? iso : DATA_HORA.format(data).replace(", ", " às ");
}

export function dataCurta(iso?: string | null): string {
  if (!iso) return "—";
  const data = new Date(iso);
  return Number.isNaN(data.getTime()) ? iso : DATA_LONGA.format(data);
}

/** ISO com fuso (como o backend devolve) para o valor de um input datetime-local em Brasília. */
export function paraCampoDataHora(iso?: string | null): string {
  if (!iso) return "";
  const data = new Date(iso);
  if (Number.isNaN(data.getTime())) return "";
  const partes = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "America/Sao_Paulo",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(data)
      .map((p) => [p.type, p.value]),
  );
  return `${partes.year}-${partes.month}-${partes.day}T${partes.hour}:${partes.minute}`;
}

export function relogio(segundos: number): string {
  const dois = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(segundos / 3600);
  const m = Math.floor((segundos % 3600) / 60);
  return `${h ? `${h}:` : ""}${dois(m)}:${dois(segundos % 60)}`;
}

export function duracao(segundos?: number | null): string {
  if (!segundos) return "";
  const m = Math.floor(segundos / 60);
  const s = segundos % 60;
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m}:${String(s).padStart(2, "0")}`;
}

export function tamanhoDoArquivo(bytes: number): string {
  const mb = bytes / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

export function porcento(valor: number): string {
  return `${valor.toLocaleString("pt-BR", { maximumFractionDigits: 1 })}%`;
}

export function plural(n: number, singular: string, pluralForma?: string): string {
  return `${n} ${n === 1 ? singular : (pluralForma ?? `${singular}s`)}`;
}

/** Só caminho interno volta depois do login: nada de https://outro-site ou //outro-site. */
export function destinoSeguro(volta: string | null, padrao: string): string {
  return volta && volta.startsWith("/") && !volta.startsWith("//") && !volta.startsWith("/\\") ? volta : padrao;
}
