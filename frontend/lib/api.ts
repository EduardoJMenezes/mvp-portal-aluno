"use client";

// Cliente da API. A sessão viaja no cookie httpOnly que o backend grava no
// login: aqui não há token, e o JavaScript nunca o vê.

import { useCallback, useEffect, useState } from "react";

const TEMPO_LIMITE_MS = 15_000;

export class ErroApi extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

// Frase para a tela a partir da resposta: o `detail` do backend já vem em
// português; sem ele, a frase sai do status, nunca do texto do proxy.
async function mensagemDeErro(resposta: Response): Promise<string> {
  const corpo: unknown = await resposta.json().catch(() => null);
  const detail = corpo && typeof corpo === "object" ? (corpo as { detail?: unknown }).detail : undefined;
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const campos = detail
      .map((e) => (Array.isArray(e?.loc) ? String(e.loc[e.loc.length - 1]) : ""))
      .filter(Boolean);
    return campos.length ? `Campos inválidos: ${[...new Set(campos)].join(", ")}.` : "Dados inválidos.";
  }
  if (resposta.status === 429) return "Muitas tentativas. Espere alguns minutos e tente de novo.";
  if (resposta.status >= 502 && resposta.status <= 504)
    return `Servidor indisponível no momento (HTTP ${resposta.status}). Tente de novo em alguns segundos.`;
  if (resposta.status >= 500) return `Erro interno no servidor (HTTP ${resposta.status}). Tente de novo em instantes.`;
  return `Falha na requisição (HTTP ${resposta.status}).`;
}

// Rotas que respondem 401 como parte do próprio fluxo, sem mandar para o login.
const SEM_REDIRECIONAR = ["/login", "/demo/entrar", "/conta/senha", "/eu"];

type Opcoes = Omit<RequestInit, "body"> & { json?: unknown; body?: BodyInit };

export async function pedir<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  const { json, headers, ...resto } = opcoes;
  const cabecalhos: Record<string, string> = { ...(headers as Record<string, string>) };
  let body = opcoes.body;
  if (json !== undefined) {
    cabecalhos["content-type"] = "application/json";
    body = JSON.stringify(json);
  }

  let resposta: Response;
  try {
    resposta = await fetch(`/api${caminho}`, {
      ...resto,
      body,
      headers: cabecalhos,
      credentials: "same-origin",
      signal: resto.signal ?? AbortSignal.timeout(TEMPO_LIMITE_MS),
    });
  } catch (ex) {
    if (ex instanceof DOMException && ex.name === "TimeoutError")
      throw new ErroApi(0, "O servidor demorou demais para responder. Tente de novo.");
    if (ex instanceof DOMException && ex.name === "AbortError") throw new ErroApi(0, "Requisição cancelada.");
    throw new ErroApi(0, "Não foi possível falar com o servidor. Verifique a conexão e tente de novo.");
  }

  if (!resposta.ok) {
    const mensagem = await mensagemDeErro(resposta);
    if (typeof window !== "undefined") {
      if (resposta.status === 401 && !SEM_REDIRECIONAR.includes(caminho)) {
        const volta = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.assign(`/entrar/?volta=${volta}`);
      } else if (resposta.status === 403 && mensagem.startsWith("Troque a senha temporária")) {
        window.location.assign("/conta/?trocar=1");
      }
    }
    throw new ErroApi(resposta.status, mensagem);
  }
  return (await resposta.json()) as T;
}

/** Carrega na montagem e quando as dependências mudam; `recarregar` para depois de uma ação. */
export function useDados<T>(carregar: () => Promise<T>, dependencias: unknown[] = []) {
  const [dados, setDados] = useState<T | null>(null);
  const [erro, setErro] = useState("");
  const [carregando, setCarregando] = useState(true);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const executar = useCallback(carregar, dependencias);

  const recarregar = useCallback(async () => {
    setCarregando(true);
    setErro("");
    try {
      setDados(await executar());
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível carregar.");
    } finally {
      setCarregando(false);
    }
  }, [executar]);

  useEffect(() => {
    void recarregar();
  }, [recarregar]);

  return { dados, erro, carregando, recarregar, setDados };
}

// --- tipos -------------------------------------------------------------------

export type Papel = "ADMIN" | "GERENCIADOR" | "ALUNO";
export type StatusConteudo = "RASCUNHO" | "PUBLICADO";
export type Situacao = "RASCUNHO" | "AGENDADO" | "ABERTO" | "ENCERRADO";

export type Usuario = {
  id: number;
  nome: string;
  email: string;
  papel: Papel;
  turmas: string[];
  trocar_senha: boolean;
};

export type Video = {
  id: number;
  titulo: string;
  bloqueado: boolean;
  motivo?: string;
  vimeo_id?: string;
  embed_url?: string | null;
  thumbnail_url?: string | null;
  duracao_segundos?: number | null;
};

export type ItemCurso = { id: number; nome: string; ordem: number; status: StatusConteudo; video_id: number; video?: Video | null };
export type SubModulo = { id: number; nome: string; tipo: string; ordem: number; itens: ItemCurso[] };
export type Modulo = { id: number; nome: string; ordem: number; turma: string; submodulos: SubModulo[] };
export type ConteudoDaTurma = { turma: string; turma_id: number; modulos: Modulo[] };

export type Turma = {
  id: number;
  nome: string;
  ano: number;
  alunos: number;
  modulos: number;
  itens_publicados: number;
  itens_em_rascunho?: number;
};

export type SimuladoResumo = {
  simulado_id: number;
  titulo: string;
  status: StatusConteudo;
  situacao: Situacao;
  turmas: string[];
  abre_em: string | null;
  fecha_em: string | null;
  duracao_minutos: number | null;
  total_questoes: number;
  tentativas?: number;
  minha_prova?: { iniciada: boolean; entregue: boolean; prazo_em: string | null };
  resultado_disponivel?: boolean;
};

export type QuestaoDaProva = {
  ordem: number;
  questao_id: number;
  enunciado: string;
  alternativas: Record<string, string>;
  marcada?: string | null;
};

export type Prova = SimuladoResumo & {
  estado: "AGENDADO" | "EM_ANDAMENTO" | "ENTREGUE" | "ENCERRADO";
  prazo_em?: string;
  segundos_restantes?: number;
  questoes?: QuestaoDaProva[];
  entregue_automaticamente?: boolean;
  resultado_em?: string;
};

export type Recomendacao = { topico: string; erros: number; videos: Video[] };

export type Resultado = {
  simulado_id: number;
  titulo: string;
  acertos: number;
  total: number;
  percentual: number;
  em_branco: number;
  entregue_automaticamente: boolean;
  posicao: number;
  participantes: number;
  questoes: (QuestaoDaProva & {
    marcada: string | null;
    em_branco: boolean;
    gabarito: string;
    correta: boolean;
    resolucao_comentada: string | null;
    resolucao: Video | null;
  })[];
  analise: Recomendacao[];
};

export type Historico = {
  simulados: {
    simulado_id: number;
    titulo: string;
    fechou_em: string | null;
    acertos: number;
    total: number;
    percentual: number;
    posicao: number;
    participantes: number;
  }[];
  topicos: Recomendacao[];
};

export type Classificacao = { assunto: string; subassunto: string | null };

export type RascunhoResumo = {
  rascunho_id: number;
  tipo: "ITENS" | "QUESTOES" | "SIMULADO";
  status: StatusConteudo;
  resumo: string;
  turma: string | null;
  modulo: string | null;
  submodulo: string | null;
  criado_por: string;
  origem: string;
  criado_em: string;
  aprovado_por: string | null;
  aprovado_via: string | null;
  publicado_em: string | null;
};

export type Rascunho = RascunhoResumo & {
  itens: { item_id: number; nome: string; ordem: number; status: StatusConteudo; video: { vimeo_id: string; titulo: string }; assuntos: Classificacao[] }[];
  questoes: {
    questao_id: number;
    enunciado: string;
    alternativas: Record<string, string>;
    gabarito: string | null;
    completa: boolean;
    resolucao_comentada: string | null;
    dificuldade: string;
    classificacao: Classificacao[];
    imagem_pendente: boolean;
    video: { vimeo_id: string; titulo: string } | null;
  }[];
  simulado?: {
    simulado_id: number;
    titulo: string;
    turmas: string[];
    abre_em: string | null;
    fecha_em: string | null;
    duracao_minutos: number | null;
    questoes: { ordem: number; questao_id: number; nova: boolean; enunciado: string; gabarito: string; imagem_pendente: boolean; resolucao: string | null }[];
    pendencias_para_publicar: string[];
  };
  publicado: boolean;
  aviso: string;
};

export type Questao = {
  questao_id: number;
  enunciado: string;
  alternativas: Record<string, string>;
  dificuldade: string;
  status: StatusConteudo;
  classificacao: Classificacao[];
  imagem_pendente: boolean;
  video_resolucao_id: number | null;
  gabarito?: string;
};

export type QuestaoDetalhada = Questao & {
  resolucao_comentada: string | null;
  figuras: { figura_id: number; parte: string }[];
  resolucao: { vimeo_id: string; titulo: string } | null;
  simulados: { simulado_id: number; titulo: string; situacao: Situacao }[];
};

export type SimuladoDoProfessor = SimuladoResumo & {
  rascunho_id: number | null;
  questoes: {
    ordem: number;
    questao_id: number;
    enunciado: string;
    alternativas: Record<string, string>;
    gabarito: string;
    imagem_pendente: boolean;
    resolucao_comentada: string | null;
    resolucao: string | null;
  }[];
  pendencias_para_publicar: string[];
};

export type Estatisticas = {
  simulado: string;
  simulado_id: number;
  turmas: string[];
  situacao: Situacao;
  parcial: boolean;
  alunos_matriculados: number;
  alunos_responderam: number;
  encontrou_dados: boolean;
  mensagem?: string;
  media_percentual?: number;
  por_questao?: {
    ordem: number;
    questao_id: number;
    enunciado: string;
    topico: string | null;
    gabarito: string;
    acertos: number;
    em_branco: number;
    percentual_acerto: number;
    distribuicao: Record<string, number>;
  }[];
  por_aluno?: { aluno: string; acertos: number; total: number; percentual: number; entregue: boolean }[];
  maior_dificuldade?: { questao_id: number; topico: string | null; enunciado: string; percentual_acerto: number } | null;
};

export type Ranking = {
  simulado_id: number;
  titulo: string;
  situacao: Situacao;
  parcial: boolean;
  participantes: number;
  ranking: { posicao: number; aluno: string; turmas: string[]; acertos: number; total: number; percentual: number; entregue_automaticamente: boolean }[];
};

export type Desempenho = {
  aluno: string;
  encontrou_dados: boolean;
  mensagem?: string;
  simulado?: string;
  simulado_id?: number;
  turmas?: string[];
  situacao?: Situacao;
  entregue?: boolean;
  entregue_automaticamente?: boolean;
  acertos?: number;
  em_branco?: number;
  total_questoes?: number;
  percentual?: number;
  questoes?: { ordem: number; questao_id: number; enunciado: string; topico: string | null; marcada: string | null; gabarito: string; correta: boolean }[];
  erros_por_topico?: [string, number][];
  recomendacoes?: Recomendacao[];
};

export type Assunto = { id: number; nome: string; subassuntos: { id: number; nome: string }[] };

export type Aluno = { id: number; nome: string; email: string; senha_temporaria: boolean; criado_em: string | null };

export type TokenMcp = { id: number; nome: string; criado_em: string | null; ultimo_uso_em: string | null; revogado: boolean };

export type PastaVimeo = { id: string; nome: string; dentro_de: string | null; videos: number | null; videos_com_subpastas: number | null; tem_subpasta: boolean };
export type VideoVimeo = { id: string; titulo: string; url?: string | null; thumbnail_url?: string | null; duracao_segundos?: number | null; embed_url?: string | null };

export type ItemDoPlano = { numero: number | null; titulo: string; vimeo_id: string; duracao_segundos: number | null; confianca_do_numero: string; avisos: string[] };
export type Destino = { faixa: string; modulo: string; submodulo: string; assunto?: string; subassunto?: string };
export type SimulacaoVimeo = {
  pasta: { id: string; nome: string };
  turma: string;
  videos_na_pasta: number;
  videos_ja_no_acervo: string[];
  destinos: {
    modulo: string;
    submodulo: string;
    faixa: string;
    assunto: string | null;
    subassunto: string | null;
    itens_que_serao_criados: number;
    ja_neste_submodulo: string[];
    numeros_da_faixa_sem_video: number[];
    itens: ItemDoPlano[];
  }[];
  sem_destino: ItemDoPlano[];
  observacao: string;
};

export type RevisaoDocx = {
  importacao_id: number;
  rascunho_id: number;
  simulado_id: number;
  titulo: string;
  arquivo: string | null;
  total_questoes: number;
  avisos_gerais: string[];
  questoes: { ordem: number; numero_no_documento: number | null; questao_id: number; enunciado: string; alternativas: Record<string, string>; gabarito: string; resolucao_comentada: string | null; imagem_pendente: boolean; avisos: string[] }[];
  incompletas: { numero: number; avisos: string[]; blocos: { indice: number; texto: string }[] }[];
  pendencias_para_publicar: string[];
};

export type LinkDeEnvio = { importacao_id: number; link: string; expira_em: string; instrucao: string };

export const tokenDoLink = (link: string) => link.replace(/\/+$/, "").split("/").pop() ?? "";

const q = (parametros: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams();
  for (const [chave, valor] of Object.entries(parametros)) if (valor !== undefined && valor !== null && valor !== "") p.set(chave, String(valor));
  const texto = p.toString();
  return texto ? `?${texto}` : "";
};
const seg = encodeURIComponent;

// --- chamadas ----------------------------------------------------------------

export const api = {
  saude: () => pedir<{ ok: boolean; banco: string; vimeo: string; mcp: string; mcp_oauth: "github" | "token-bearer"; modo_demo: boolean }>("/saude"),

  // sessão
  sessaoConfig:() => pedir<{ modo_demo: boolean; contas_demo: { nome: string; email: string; papel: Papel; turmas: string[] }[] }>("/sessao/config"),
  entrar: (email: string, senha: string) => pedir<{ usuario: Usuario }>("/login", { method: "POST", json: { email, senha } }),
  entrarDemo: (email: string) => pedir<{ usuario: Usuario }>("/demo/entrar", { method: "POST", json: { email } }),
  sair: () => pedir<{ saiu: boolean }>("/logout", { method: "POST" }),
  eu: () => pedir<Usuario>("/eu"),
  trocarSenha: (senha_atual: string, nova_senha: string) =>
    pedir<{ usuario: Usuario }>("/conta/senha", { method: "POST", json: { senha_atual, nova_senha } }),

  // aluno
  conteudo: () => pedir<ConteudoDaTurma[]>("/aluno/conteudo"),
  simulados: () => pedir<SimuladoResumo[]>("/aluno/simulados"),
  prova: (id: number) => pedir<Prova>(`/aluno/simulados/${id}`),
  responder: (id: number, questao_id: number, alternativa: string) =>
    pedir<{ registrado: boolean; respondidas: number; total: number; segundos_restantes: number }>(`/aluno/simulados/${id}/responder`, {
      method: "POST",
      json: { questao_id, alternativa },
    }),
  entregar: (id: number) => pedir<{ mensagem: string; resultado_em: string }>(`/aluno/simulados/${id}/entregar`, { method: "POST" }),
  resultado: (id: number) => pedir<Resultado>(`/aluno/simulados/${id}/resultado`),
  historico: () => pedir<Historico>("/aluno/desempenho"),

  // envio pelo link (sem login)
  envio: (token: string) =>
    pedir<{ situacao: "AGUARDANDO" | "RECEBIDO" | "EXPIRADO"; formato: "docx" | "prints"; titulo: string | null; turmas: string[]; pedido_por: string; expira_em: string }>(
      `/importacoes/${seg(token)}`,
    ),
  enviarDocx: (token: string, arquivo: File) => enviarArquivos(`/importacoes/${seg(token)}/arquivo`, "arquivo", [arquivo]),
  enviarPrints: (token: string, prints: File[]) => enviarArquivos(`/importacoes/${seg(token)}/prints`, "arquivos", prints),

  // professor: consulta
  turmas: () => pedir<Turma[]>("/admin/turmas"),
  modulos: (turma: string | number) => pedir<Modulo[]>(`/admin/modulos${q({ turma })}`),
  assuntos: () => pedir<Assunto[]>("/admin/assuntos"),
  questoes: (filtro: { assunto?: string; status?: string; dificuldade?: string; busca?: string; limite?: number; offset?: number }) =>
    pedir<Questao[]>(`/admin/questoes${q(filtro)}`),
  desempenho: (aluno: string | number, simulado?: string | number) =>
    pedir<Desempenho>(`/admin/alunos/${seg(String(aluno))}/desempenho${q({ simulado })}`),

  // turmas e alunos
  criarTurma: (nome: string, ano: number) => pedir<{ id: number; nome: string; ano: number }>("/admin/turmas", { method: "POST", json: { nome, ano } }),
  editarTurma: (turma: number, dados: { nome?: string; ano?: number }) =>
    pedir<{ id: number; nome: string; ano: number }>(`/admin/turmas/${turma}`, { method: "PATCH", json: dados }),
  alunosDaTurma: (turma: number) => pedir<{ turma_id: number; turma: string; alunos: Aluno[] }>(`/admin/turmas/${turma}/alunos`),
  matricular: (turma: number, email: string, nome: string) =>
    pedir<{ turma: string; aluno: Aluno; senha_temporaria: string | null }>(`/admin/turmas/${turma}/alunos`, { method: "POST", json: { email, nome } }),
  desmatricular: (turma: number, aluno: number) => pedir<{ removido: boolean }>(`/admin/turmas/${turma}/alunos/${aluno}`, { method: "DELETE" }),
  redefinirSenha: (aluno: number) => pedir<{ aluno: Aluno; senha_temporaria: string }>(`/admin/alunos/${aluno}/senha`, { method: "POST" }),

  // curso
  criarModulo: (turma: number, nome: string, submodulos?: string[]) =>
    pedir<{ modulo_id: number }>(`/admin/turmas/${turma}/modulos`, { method: "POST", json: { nome, submodulos } }),
  editarModulo: (turma: number, modulo: number, dados: { nome?: string; ordem?: number }) =>
    pedir(`/admin/turmas/${turma}/modulos/${modulo}`, { method: "PATCH", json: dados }),
  removerModulo: (turma: number, modulo: number) =>
    pedir<{ itens_publicados_que_somem_da_tela: number }>(`/admin/turmas/${turma}/modulos/${modulo}`, { method: "DELETE" }),
  criarSubmodulo: (turma: number, modulo: number, nome: string) =>
    pedir(`/admin/turmas/${turma}/modulos/${modulo}/submodulos`, { method: "POST", json: { nome } }),
  removerSubmodulo: (turma: number, modulo: number, submodulo: number) =>
    pedir(`/admin/turmas/${turma}/modulos/${modulo}/submodulos/${submodulo}`, { method: "DELETE" }),
  editarItem: (turma: number, modulo: number, submodulo: number, item: number, dados: { nome?: string; ordem?: number; mover_para_submodulo?: string }) =>
    pedir(`/admin/turmas/${turma}/modulos/${modulo}/submodulos/${submodulo}/itens/${item}`, { method: "PATCH", json: dados }),
  removerItem: (turma: number, modulo: number, submodulo: number, item: number) =>
    pedir(`/admin/turmas/${turma}/modulos/${modulo}/submodulos/${submodulo}/itens/${item}`, { method: "DELETE" }),
  adicionarVideos: (turma: number, modulo: number, submodulo: number, videos: { vimeo_id: string; titulo?: string; embed_url?: string | null; nome?: string }[]) =>
    pedir<Rascunho>(`/admin/turmas/${turma}/modulos/${modulo}/submodulos/${submodulo}/itens`, { method: "POST", json: { videos } }),
  classificar: (turma: number, modulo: number, submodulo: number, dados: { assunto: string; subassunto?: string; itens?: string }) =>
    pedir<{ videos_classificados: string[] }>(`/admin/turmas/${turma}/modulos/${modulo}/submodulos/${submodulo}/classificacao`, { method: "POST", json: dados }),

  // assuntos
  cadastrarAssunto: (nome: string, subassuntos?: string[]) => pedir("/admin/assuntos", { method: "POST", json: { nome, subassuntos } }),
  editarAssunto: (assunto: number, nome: string) => pedir(`/admin/assuntos/${assunto}`, { method: "PATCH", json: { nome } }),
  removerAssunto: (assunto: number) =>
    pedir<{ videos_que_perdem_a_etiqueta: number; questoes_que_perdem_a_etiqueta: number }>(`/admin/assuntos/${assunto}`, { method: "DELETE" }),
  editarSubassunto: (assunto: number, sub: number, nome: string) =>
    pedir(`/admin/assuntos/${assunto}/subassuntos/${sub}`, { method: "PATCH", json: { nome } }),
  removerSubassunto: (assunto: number, sub: number) => pedir(`/admin/assuntos/${assunto}/subassuntos/${sub}`, { method: "DELETE" }),

  // rascunhos
  rascunhos: (status?: string) => pedir<RascunhoResumo[]>(`/admin/rascunhos${q({ status })}`),
  rascunho: (id: number) => pedir<Rascunho>(`/admin/rascunhos/${id}`),
  publicar: (id: number, itens?: number[]) =>
    pedir<{ publicado: boolean; mensagem?: string }>(`/admin/rascunhos/${id}/publicar`, { method: "POST", json: itens ? { itens } : {} }),
  descartar: (id: number) => pedir(`/admin/rascunhos/${id}`, { method: "DELETE" }),

  // questões
  questao: (id: number) => pedir<QuestaoDetalhada>(`/admin/questoes/${id}`),
  criarQuestao: (dados: Record<string, unknown>) => pedir<Rascunho>("/admin/questoes", { method: "POST", json: dados }),
  editarQuestao: (id: number, dados: Record<string, unknown>) =>
    pedir<QuestaoDetalhada>(`/admin/questoes/${id}`, { method: "PATCH", json: dados }),
  removerQuestao: (id: number) => pedir(`/admin/questoes/${id}`, { method: "DELETE" }),
  anexarFigura: (id: number, arquivo: File, parte: string, alternativa?: string) => {
    const corpo = new FormData();
    corpo.append("arquivo", arquivo);
    corpo.append("parte", parte);
    if (alternativa) corpo.append("alternativa", alternativa);
    return pedir<{ figura_id: number; imagem_pendente: boolean }>(`/admin/questoes/${id}/figuras`, { method: "POST", body: corpo });
  },

  // simulados
  simuladosDoProfessor: (turma?: string) => pedir<SimuladoResumo[]>(`/admin/simulados${q({ turma })}`),
  simulado: (id: number) => pedir<SimuladoDoProfessor>(`/admin/simulados/${id}`),
  criarSimulado: (dados: Record<string, unknown>) => pedir<Rascunho>("/admin/simulados", { method: "POST", json: dados }),
  editarSimulado: (id: number, dados: Record<string, unknown>) =>
    pedir<SimuladoResumo>(`/admin/simulados/${id}`, { method: "PATCH", json: dados }),
  removerSimulado: (id: number) => pedir<{ questoes_novas_removidas: number }>(`/admin/simulados/${id}`, { method: "DELETE" }),
  estatisticas: (id: number) => pedir<Estatisticas>(`/admin/simulados/${id}/estatisticas`),
  ranking: (id: number) => pedir<Ranking>(`/admin/simulados/${id}/ranking`),

  // vimeo
  pastasVimeo: (busca?: string) => pedir<{ total_no_vimeo: number; mostrando: number; pastas: PastaVimeo[] }>(`/admin/vimeo/pastas${q({ busca })}`),
  videosVimeo: (filtro: { pasta?: string; busca?: string; limite?: number }) => pedir<VideoVimeo[]>(`/admin/vimeo/videos${q(filtro)}`),
  simularImportacao: (pasta: string, turma: string, destinos: Destino[]) =>
    pedir<SimulacaoVimeo>("/admin/vimeo/importacoes/simulacao", { method: "POST", json: { pasta, turma, destinos } }),
  importarPasta: (pasta: string, turma: string, destinos: Destino[]) =>
    pedir<{ rascunhos: Rascunho[]; aviso: string }>("/admin/vimeo/importacoes", { method: "POST", json: { pasta, turma, destinos } }),

  // importações
  linkDocx: (dados: { turmas: string[]; titulo?: string; abre_em?: string; fecha_em?: string; duracao_minutos?: number; pasta_resolucao?: string }) =>
    pedir<LinkDeEnvio>("/admin/importacoes", { method: "POST", json: dados }),
  revisarImportacao: (id: number) => pedir<RevisaoDocx>(`/admin/importacoes/${id}`),
  completarQuestao: (id: number, dados: { numero: number; enunciado: string; alternativas: string; gabarito: string; resolucao?: string }) =>
    pedir<{ questao_id: number; ordem: number }>(`/admin/importacoes/${id}/questoes`, { method: "POST", json: dados }),
  linkPrints: () => pedir<LinkDeEnvio>("/admin/importacoes/prints", { method: "POST" }),
  totalDePrints: (id: number) => pedir<{ importacao_id: number; total_prints: number }>(`/admin/importacoes/${id}/prints`),
  recortar: (id: number, dados: { print: number; questao: number; retangulo: number[]; parte: string; alternativa?: string; estender?: boolean }) =>
    pedir<{ figura_id: number }>(`/admin/importacoes/${id}/recortes`, { method: "POST", json: dados }),

  // tokens do MCP
  tokens: () => pedir<TokenMcp[]>("/admin/tokens"),
  emitirToken: (nome: string) => pedir<TokenMcp & { token: string }>("/admin/tokens", { method: "POST", json: { nome } }),
  revogarToken: (id: number) => pedir<TokenMcp>(`/admin/tokens/${id}`, { method: "DELETE" }),
};

async function enviarArquivos(caminho: string, campo: string, arquivos: File[]) {
  const corpo = new FormData();
  arquivos.forEach((arquivo) => corpo.append(campo, arquivo));
  return pedir<{ mensagem: string; titulo?: string; questoes_lidas?: number; questoes_completas?: number; figuras?: number; prints?: number; importacao_id: number }>(
    caminho,
    { method: "POST", body: corpo, signal: AbortSignal.timeout(120_000) },
  );
}
