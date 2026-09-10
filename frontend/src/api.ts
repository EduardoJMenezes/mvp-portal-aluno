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

export type Questao = {
  vinculo_id: number; questao_id: number; numero: number; turma: string; capitulo: string;
  enunciado: string; alternativas: Record<string, string>; gabarito?: string;
  dificuldade: string; topico: string | null; subtopico: string | null; status: string;
  video: { vimeo_id: string; titulo: string; url: string; embed_url: string } | null;
};

export type Rascunho = {
  rascunho_id: number; tipo: string; status: string; resumo: string;
  turma: string | null; capitulo: string | null; criado_por: string; origem: string;
  criado_em: string; aprovado_por: string | null; aprovado_via: string | null;
  publicado_em: string | null;
  questoes?: { questao_id: number; numero: number; capitulo: string; enunciado: string;
    alternativas: Record<string, string>; gabarito: string | null; completa: boolean;
    video: { vimeo_id: string; titulo: string } | null }[];
  simulado?: { simulado_id: number; titulo: string; turma: string;
    questoes: { ordem: number; questao_id: number; enunciado: string }[] };
  aviso?: string;
};

export const api = {
  login: (email: string, senha: string) =>
    req<{ token: string; usuario: Usuario }>("/login", {
      method: "POST", body: JSON.stringify({ email, senha }),
    }),
  eu: () => req<Usuario>("/eu"),

  // admin
  turmas: () => req<any[]>("/admin/turmas"),
  capitulos: () => req<{ id: number; nome: string }[]>("/admin/capitulos"),
  questoes: (turma?: string, capitulo?: string) => {
    const p = new URLSearchParams();
    if (turma) p.set("turma", turma);
    if (capitulo) p.set("capitulo", capitulo);
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
  conteudo: () => req<{ turma: string; capitulos: { capitulo: string; questoes: Questao[] }[] }[]>("/aluno/conteudo"),
  simulados: () => req<any[]>("/aluno/simulados"),
  simulado: (id: number) => req<any>(`/aluno/simulados/${id}`),
  responder: (id: number, questao_id: number, alternativa: string) =>
    req<any>(`/aluno/simulados/${id}/responder`, {
      method: "POST", body: JSON.stringify({ questao_id, alternativa }),
    }),
  finalizar: (id: number) => req<any>(`/aluno/simulados/${id}/finalizar`, { method: "POST" }),
};
