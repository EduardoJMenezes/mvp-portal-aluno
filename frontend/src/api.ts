// Cliente HTTP do portal. Uma função por caso de uso, sem abstração extra.

const BASE = "/api";

// Tempo máximo de espera por uma resposta. Sem isso, um banco que não responde
// deixa o botão em "entrando…" até o navegador desistir sozinho (minutos).
const TEMPO_LIMITE_MS = 15_000;

function token(): string | null {
  return localStorage.getItem("token");
}

// Traduz uma resposta de erro em uma frase para a tela. As páginas mostram
// `Error.message` direto ao usuário, então nada de texto do navegador ou do
// proxy em inglês — e nada de `statusText`, que vem vazio em HTTP/2.
async function mensagemDeErro(resposta: Response): Promise<string> {
  const corpo: unknown = await resposta.json().catch(() => null);
  const detail = corpo && typeof corpo === "object" ? (corpo as { detail?: unknown }).detail : undefined;

  if (typeof detail === "string" && detail.trim()) return detail;

  // 422 do FastAPI: uma lista de erros de validação, cada um com o caminho do campo.
  if (Array.isArray(detail)) {
    const campos = detail
      .map((e) => (Array.isArray(e?.loc) ? String(e.loc[e.loc.length - 1]) : ""))
      .filter(Boolean);
    return campos.length
      ? `Campos inválidos: ${[...new Set(campos)].join(", ")}.`
      : "Dados inválidos na requisição.";
  }

  // Sem `detail` legível: a resposta veio do proxy (502/503 do Railway) ou do
  // servidor em texto puro. A frase sai do status.
  if (resposta.status >= 502 && resposta.status <= 504) {
    return `Servidor indisponível no momento (HTTP ${resposta.status}). Tente novamente em alguns segundos.`;
  }
  if (resposta.status >= 500) {
    return `Erro interno no servidor (HTTP ${resposta.status}). Tente novamente em instantes.`;
  }
  return `Falha na requisição (HTTP ${resposta.status}).`;
}

async function req<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  const cabecalhos: Record<string, string> = { "content-type": "application/json" };
  const t = token();
  if (t) cabecalhos.authorization = `Bearer ${t}`;

  let resposta: Response;
  try {
    resposta = await fetch(BASE + caminho, {
      ...init,
      headers: { ...cabecalhos, ...(init.headers as object) },
      signal: init.signal ?? AbortSignal.timeout(TEMPO_LIMITE_MS),
    });
  } catch (ex) {
    // fetch só rejeita quando não houve resposta nenhuma: rede, DNS, servidor
    // fora do ar ou o tempo limite acima.
    if (ex instanceof DOMException && ex.name === "TimeoutError") {
      throw new Error("O servidor demorou demais para responder. Tente novamente.");
    }
    if (ex instanceof DOMException && ex.name === "AbortError") {
      throw new Error("Requisição cancelada.");
    }
    throw new Error("Não foi possível conectar ao servidor. Verifique sua conexão e tente novamente.");
  }

  if (resposta.status === 401 && caminho !== "/login") {
    localStorage.clear();
    window.location.href = "/";
    throw new Error("Sessão expirada.");
  }
  if (!resposta.ok) throw new Error(await mensagemDeErro(resposta));
  return resposta.json();
}

export type Usuario = { id: number; nome: string; email: string; papel: string; turmas: string[] };

// O vídeo bloqueado chega só com nome e motivo: o backend não manda embed_url
// para quem não pode assistir (ver services/acesso.py).
export type Video = {
  id: number; titulo: string; bloqueado: boolean; motivo?: string;
  vimeo_id?: string; embed_url?: string; thumbnail_url?: string; duracao_segundos?: number;
};

export type Item = {
  id: number; nome: string; ordem: number; status: string;
  video_id: number; video?: Video | null;
};

export type SubModulo = { id: number; nome: string; tipo: string; ordem: number; itens: Item[] };
export type Modulo = {
  id: number; nome: string; ordem: number; turma: string; submodulos: SubModulo[];
};

// Questão existe para o simulado; o conteúdo do curso é vídeo.
export type Questao = {
  questao_id: number; enunciado: string; alternativas: Record<string, string>;
  gabarito?: string; dificuldade: string; status: string;
  classificacao: { assunto: string; subassunto: string | null }[];
  video_resolucao_id: number | null;
};

export type Rascunho = {
  rascunho_id: number; tipo: string; status: string; resumo: string;
  turma: string | null; modulo: string | null; submodulo: string | null;
  criado_por: string; origem: string;
  criado_em: string; aprovado_por: string | null; aprovado_via: string | null;
  publicado_em: string | null;
  itens?: { item_id: number; nome: string; ordem: number; status: string;
    video: { vimeo_id: string; titulo: string };
    assuntos: { assunto: string; subassunto: string | null }[] }[];
  questoes?: { questao_id: number; enunciado: string;
    alternativas: Record<string, string>; gabarito: string | null; completa: boolean;
    imagem_pendente: boolean; classificacao: { assunto: string; subassunto: string | null }[];
    resolucao_comentada: string | null;
    video: { vimeo_id: string; titulo: string } | null }[];
  simulado?: { simulado_id: number; titulo: string; turmas: string[];
    abre_em: string | null; fecha_em: string | null; duracao_minutos: number | null;
    pendencias_para_publicar: string[];
    questoes: { ordem: number; questao_id: number; enunciado: string; gabarito: string;
      nova: boolean; imagem_pendente: boolean; resolucao: string | null }[] };
  aviso?: string;
};

const figuras = new Map<number, Promise<string>>();

export const api = {
  login: (email: string, senha: string) =>
    req<{ token: string; usuario: Usuario }>("/login", {
      method: "POST", body: JSON.stringify({ email, senha }),
    }),
  eu: () => req<Usuario>("/eu"),

  // admin
  turmas: () => req<any[]>("/admin/turmas"),
  modulos: (turma?: string) =>
    req<Modulo[]>(`/admin/modulos${turma ? `?turma=${encodeURIComponent(turma)}` : ""}`),
  assuntos: () =>
    req<{ id: number; nome: string; subassuntos: { id: number; nome: string }[] }[]>(
      "/admin/assuntos",
    ),
  questoes: (assunto?: string) => {
    const p = new URLSearchParams();
    if (assunto) p.set("assunto", assunto);
    return req<Questao[]>(`/admin/questoes?${p}`);
  },
  rascunhos: (status?: string) =>
    req<Rascunho[]>(`/admin/rascunhos${status ? `?status=${status}` : ""}`),
  rascunho: (id: number) => req<Rascunho>(`/admin/rascunhos/${id}`),
  publicar: (id: number) => req<any>(`/admin/rascunhos/${id}/publicar`, { method: "POST" }),
  descartar: (id: number) => req<any>(`/admin/rascunhos/${id}`, { method: "DELETE" }),
  simuladosAdmin: () => req<any[]>("/admin/simulados"),
  estatisticas: (id: number) => req<any>(`/admin/simulados/${id}/estatisticas`),

  // aluno
  conteudo: () =>
    req<{ turma: string; turma_id: number; modulos: Modulo[] }[]>("/aluno/conteudo"),
  simulados: () => req<any[]>("/aluno/simulados"),
  simulado: (id: number) => req<any>(`/aluno/simulados/${id}`),
  responder: (id: number, questao_id: number, alternativa: string) =>
    req<any>(`/aluno/simulados/${id}/responder`, {
      method: "POST", body: JSON.stringify({ questao_id, alternativa }),
    }),
  entregar: (id: number) => req<any>(`/aluno/simulados/${id}/entregar`, { method: "POST" }),
  // Só depois do fechamento: antes, o backend recusa.
  resultado: (id: number) => req<any>(`/aluno/simulados/${id}/resultado`),

  // A figura vem como arquivo, não JSON; a tela recebe um endereço local para o
  // <img>. Guardado por id: a mesma figura aparece no enunciado e no resultado.
  figura: (id: number) => {
    if (!figuras.has(id)) {
      figuras.set(id, fetch(`${BASE}/aluno/figuras/${id}`, {
        headers: { authorization: `Bearer ${token()}` },
      }).then(async (resposta) => {
        if (!resposta.ok) throw new Error(await mensagemDeErro(resposta));
        return URL.createObjectURL(await resposta.blob());
      }));
    }
    return figuras.get(id)!;
  },

  // Link de envio do .docx ou dos prints: vale sem login, o token do link é a credencial.
  envio: (token: string) => req<any>(`/importacoes/${token}`),
  enviarDocx: (token: string, arquivo: File) => enviarArquivos(`/importacoes/${token}/arquivo`, "arquivo", [arquivo]),
  enviarPrints: (token: string, prints: File[]) => enviarArquivos(`/importacoes/${token}/prints`, "arquivos", prints),
};

async function enviarArquivos(caminho: string, campo: string, arquivos: File[]) {
  const corpo = new FormData();
  arquivos.forEach((arquivo) => corpo.append(campo, arquivo));
  const resposta = await fetch(`${BASE}${caminho}`, { method: "POST", body: corpo });
  if (!resposta.ok) throw new Error(await mensagemDeErro(resposta));
  return resposta.json();
}

// Agenda e prazos saem do backend em ISO; na tela, sempre no horário de Brasília.
const FORMATO_BRASILIA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo", dateStyle: "short", timeStyle: "short",
});

export function emBrasilia(iso?: string | null): string {
  return iso ? FORMATO_BRASILIA.format(new Date(iso)).replace(", ", " às ") : "—";
}
