// Cliente HTTP do portal. Uma função por caso de uso, sem abstração extra.

const BASE = "/api";

function token(): string | null {
  return localStorage.getItem("token");
}

async function req<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  const cabecalhos: Record<string, string> = { "content-type": "application/json" };
  const t = token();
  if (t) cabecalhos.authorization = `Bearer ${t}`;

  const resposta = await fetch(BASE + caminho, { ...init, headers: { ...cabecalhos, ...(init.headers as object) } });
  if (resposta.status === 401) {
    localStorage.clear();
    window.location.href = "/";
    throw new Error("Sessão expirada.");
  }
  if (!resposta.ok) {
    const corpo = await resposta.json().catch(() => ({ detail: resposta.statusText }));
    throw new Error(corpo.detail ?? "Falha na requisição.");
  }
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
