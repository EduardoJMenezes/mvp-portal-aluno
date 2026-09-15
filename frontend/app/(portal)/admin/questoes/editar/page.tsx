"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Carregando, Etiqueta, Pagina, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type Assunto, type QuestaoDetalhada, type VideoVimeo } from "@/lib/api";
import { duracao } from "@/lib/formato";
import { DIFICULDADE, LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDoEditor() {
  return (
    <Suspense>
      <Editor />
    </Suspense>
  );
}

type Formulario = {
  enunciado: string;
  alternativas: Record<string, string>;
  gabarito: string;
  dificuldade: string;
  assunto: string;
  subassunto: string;
  vimeo_id: string;
  resolucao_comentada: string;
  imagem_pendente: boolean;
};

const VAZIO: Formulario = {
  enunciado: "",
  alternativas: { A: "", B: "", C: "", D: "", E: "" },
  gabarito: "",
  dificuldade: "MEDIA",
  assunto: "",
  subassunto: "",
  vimeo_id: "",
  resolucao_comentada: "",
  imagem_pendente: false,
};

function doDetalhe(q: QuestaoDetalhada, assuntos: Assunto[]): Formulario {
  const etiqueta = q.classificacao[0];
  const assunto = assuntos.find((a) => a.nome === etiqueta?.assunto);
  return {
    enunciado: q.enunciado,
    alternativas: { ...VAZIO.alternativas, ...q.alternativas },
    gabarito: q.gabarito ?? "",
    dificuldade: q.dificuldade,
    assunto: assunto ? String(assunto.id) : "",
    subassunto: assunto?.subassuntos.find((s) => s.nome === etiqueta?.subassunto)?.id.toString() ?? "",
    vimeo_id: q.resolucao?.vimeo_id ?? "",
    resolucao_comentada: q.resolucao_comentada ?? "",
    imagem_pendente: q.imagem_pendente,
  };
}

function Editor() {
  const router = useRouter();
  const id = Number(useSearchParams().get("id")) || null;
  const assuntos = useDados(() => api.assuntos());
  const questao = useDados(() => (id ? api.questao(id) : Promise.resolve(null)), [id]);
  const [form, setForm] = useState<Formulario>(VAZIO);
  const [original, setOriginal] = useState<Formulario>(VAZIO);
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [salvando, setSalvando] = useState(false);
  const [dialogo, confirmar] = useConfirmar();

  useEffect(() => {
    if (questao.dados && assuntos.dados) {
      const carregado = doDetalhe(questao.dados, assuntos.dados);
      setForm(carregado);
      setOriginal(carregado);
    }
  }, [questao.dados, assuntos.dados]);

  // Questão de simulado que já abriu trava o que o aluno viu; classificação,
  // dificuldade e vídeo continuam editáveis.
  const travada = !!questao.dados?.simulados.some((s) => s.situacao === "ABERTO" || s.situacao === "ENCERRADO");
  const assuntoEscolhido = assuntos.dados?.find((a) => String(a.id) === form.assunto);
  // O backend aceita id ou nome; o nome deixa legível o resumo do rascunho.
  const nomeDoAssunto = assuntoEscolhido?.nome ?? "";
  const nomeDoSubassunto = assuntoEscolhido?.subassuntos.find((s) => String(s.id) === form.subassunto)?.nome ?? "";
  const muda = <K extends keyof Formulario>(chave: K, valor: Formulario[K]) => setForm((f) => ({ ...f, [chave]: valor }));

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setAviso("");
    if (!form.gabarito) return setErro("Marque o gabarito.");
    setSalvando(true);
    try {
      if (!id) {
        const rascunho = await api.criarQuestao({
          enunciado: form.enunciado,
          alternativas: form.alternativas,
          gabarito: form.gabarito,
          dificuldade: form.dificuldade,
          assunto: nomeDoAssunto || undefined,
          subassunto: nomeDoSubassunto || undefined,
          vimeo_id: form.vimeo_id.trim() || undefined,
          resolucao_comentada: form.resolucao_comentada || undefined,
          imagem_pendente: form.imagem_pendente,
        });
        router.replace(`/admin/rascunhos/revisar/?id=${rascunho.rascunho_id}`);
        return;
      }
      const mudancas: Record<string, unknown> = {};
      if (form.enunciado !== original.enunciado) mudancas.enunciado = form.enunciado;
      if (LETRAS.some((l) => form.alternativas[l] !== original.alternativas[l])) mudancas.alternativas = form.alternativas;
      if (form.gabarito !== original.gabarito) mudancas.gabarito = form.gabarito;
      if (form.dificuldade !== original.dificuldade) mudancas.dificuldade = form.dificuldade;
      if (form.imagem_pendente !== original.imagem_pendente) mudancas.imagem_pendente = form.imagem_pendente;
      if (form.assunto !== original.assunto || form.subassunto !== original.subassunto) {
        mudancas.assunto = nomeDoAssunto;
        if (nomeDoAssunto && nomeDoSubassunto) mudancas.subassunto = nomeDoSubassunto;
      }
      if (form.vimeo_id.trim() !== original.vimeo_id) mudancas.vimeo_id = form.vimeo_id.trim();
      if (form.resolucao_comentada !== original.resolucao_comentada) mudancas.resolucao_comentada = form.resolucao_comentada;
      if (!Object.keys(mudancas).length) {
        setAviso("Nada mudou.");
        return;
      }
      await api.editarQuestao(id, mudancas);
      setAviso("Questão salva. Se ela já está publicada, a mudança vale na hora.");
      await questao.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setSalvando(false);
    }
  }

  async function remover() {
    if (!id) return;
    const sim = await confirmar({
      titulo: "Remover esta questão do banco?",
      texto: "As provas que já a usaram continuam como estão. Simulado que ainda vai acontecer impede a remoção.",
      confirmar: "Remover questão",
      perigo: true,
    });
    if (!sim) return;
    try {
      await api.removerQuestao(id);
      router.replace("/admin/questoes/");
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  if (id && (questao.carregando || assuntos.carregando) && !questao.dados) {
    return (
      <Pagina titulo="Questão" voltar={{ href: "/admin/questoes/", rotulo: "Banco de questões" }}>
        <Carregando linhas={4} />
      </Pagina>
    );
  }

  return (
    <Pagina
      titulo={id ? `Questão #${id}` : "Nova questão"}
      legenda={id ? undefined : "Nasce em rascunho: só entra no banco depois de aprovada."}
      voltar={{ href: "/admin/questoes/", rotulo: "Banco de questões" }}
      acoes={id && <Botao variante="perigo" onClick={() => void remover()}>Remover</Botao>}
    >
      {dialogo}
      {questao.erro && <Aviso tom="erro">{questao.erro}</Aviso>}
      {travada && (
        <Aviso tom="atencao" titulo="Enunciado, alternativas, gabarito e imagem travaram">
          Esta questão está em simulado que já abriu: {questao.dados?.simulados.filter((s) => s.situacao !== "RASCUNHO" && s.situacao !== "AGENDADO").map((s) => s.titulo).join(", ")}. Classificação, dificuldade e vídeo ainda mudam.
        </Aviso>
      )}

      <form onSubmit={salvar} className="grid gap-5 lg:grid-cols-2">
        <div className="flex flex-col gap-4">
          <Cartao className="flex flex-col gap-4 p-5">
            <Campo rotulo="Enunciado" dica={<>Markdown. Fórmula entre $…$ (química em \ce&#123;…&#125;). Figura que ainda vai entrar: ![](figura:pendente).</>}>
              {(cid) => <textarea id={cid} required rows={8} disabled={travada} value={form.enunciado} onChange={(e) => muda("enunciado", e.target.value)} className="campo" />}
            </Campo>
            <fieldset className="flex flex-col gap-3" disabled={travada}>
              <legend className="mb-1 text-sm font-semibold text-tinta-2">Alternativas e gabarito</legend>
              {LETRAS.map((letra) => (
                <div key={letra} className="flex items-start gap-2">
                  <label className={`mt-1.5 flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full border text-sm font-semibold ${form.gabarito === letra ? "border-sucesso bg-sucesso text-white" : "border-borda-campo text-tinta-2"}`}>
                    <input type="radio" name="gabarito" value={letra} checked={form.gabarito === letra} onChange={() => muda("gabarito", letra)} className="sr-only" />
                    <span aria-hidden="true">{letra}</span>
                    <span className="sr-only">Gabarito {letra}</span>
                  </label>
                  <textarea
                    aria-label={`Alternativa ${letra}`}
                    required
                    rows={2}
                    value={form.alternativas[letra]}
                    onChange={(e) => setForm((f) => ({ ...f, alternativas: { ...f.alternativas, [letra]: e.target.value } }))}
                    className="campo min-h-0"
                  />
                </div>
              ))}
              <p className="text-[13px] text-suave">Clique na letra para marcar o gabarito.</p>
            </fieldset>
          </Cartao>

          <Cartao className="grid gap-4 p-5 sm:grid-cols-2">
            <Campo rotulo="Dificuldade">
              {(cid) => (
                <select id={cid} value={form.dificuldade} onChange={(e) => muda("dificuldade", e.target.value)} className="campo">
                  {Object.entries(DIFICULDADE).map(([valor, rotulo]) => (
                    <option key={valor} value={valor}>{rotulo}</option>
                  ))}
                </select>
              )}
            </Campo>
            <label className="flex items-center gap-2 self-end pb-2 text-[15px]">
              <input type="checkbox" disabled={travada} checked={form.imagem_pendente} onChange={(e) => muda("imagem_pendente", e.target.checked)} className="size-4 accent-acento" />
              Imagem pendente
            </label>
            <Campo rotulo="Assunto">
              {(cid) => (
                <select id={cid} value={form.assunto} onChange={(e) => setForm((f) => ({ ...f, assunto: e.target.value, subassunto: "" }))} className="campo">
                  <option value="">Sem classificação</option>
                  {assuntos.dados?.map((a) => (
                    <option key={a.id} value={a.id}>{a.nome}</option>
                  ))}
                </select>
              )}
            </Campo>
            <Campo rotulo="Sub-assunto">
              {(cid) => (
                <select id={cid} value={form.subassunto} disabled={!assuntoEscolhido?.subassuntos.length} onChange={(e) => muda("subassunto", e.target.value)} className="campo">
                  <option value="">Nenhum</option>
                  {assuntoEscolhido?.subassuntos.map((s) => (
                    <option key={s.id} value={s.id}>{s.nome}</option>
                  ))}
                </select>
              )}
            </Campo>
          </Cartao>

          <Cartao className="flex flex-col gap-4 p-5">
            <VideoDaResolucao vimeoId={form.vimeo_id} aoEscolher={(v) => muda("vimeo_id", v)} />
            <Campo rotulo="Resolução comentada" dica="O aluno vê depois que o simulado fecha.">
              {(cid) => <textarea id={cid} rows={5} value={form.resolucao_comentada} onChange={(e) => muda("resolucao_comentada", e.target.value)} className="campo" />}
            </Campo>
          </Cartao>

          {erro && <Aviso tom="erro">{erro}</Aviso>}
          {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
          <div className="flex gap-2">
            <Botao type="submit" variante="primario" disabled={salvando}>{salvando ? "Salvando…" : id ? "Salvar questão" : "Criar em rascunho"}</Botao>
          </div>
        </div>

        <div className="flex flex-col gap-4 lg:sticky lg:top-20 lg:self-start">
          <Previa form={form} />
          {id && questao.dados && <Figuras questao={questao.dados} aoAnexar={() => void questao.recarregar()} />}
        </div>
      </form>
    </Pagina>
  );
}

function Previa({ form }: { form: Formulario }) {
  const vazia = useMemo(() => !form.enunciado.trim(), [form.enunciado]);
  return (
    <Cartao className="flex flex-col gap-4 p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-suave">Como o aluno vê</h2>
        {form.gabarito && <Etiqueta tom="sucesso">Gabarito {form.gabarito}</Etiqueta>}
      </div>
      {vazia ? <p className="text-[15px] text-suave">A prévia aparece enquanto você escreve.</p> : <TextoFormatado texto={form.enunciado} />}
      <ul className="flex flex-col gap-2">
        {LETRAS.map((letra) => (
          <li key={letra} className={`flex items-start gap-3 rounded-cartao border px-3 py-2 ${form.gabarito === letra ? "border-sucesso-borda bg-sucesso-fundo" : "border-borda"}`}>
            <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-borda-campo text-xs font-semibold">{letra}</span>
            <TextoFormatado texto={form.alternativas[letra] || " "} compacto className="min-w-0 flex-1" />
          </li>
        ))}
      </ul>
      {form.resolucao_comentada.trim() && (
        <div className="rounded-md bg-lilas p-3">
          <p className="mb-1 text-[13px] font-semibold text-acento-forte">Resolução comentada</p>
          <TextoFormatado texto={form.resolucao_comentada} compacto />
        </div>
      )}
    </Cartao>
  );
}

function VideoDaResolucao({ vimeoId, aoEscolher }: { vimeoId: string; aoEscolher: (vimeoId: string) => void }) {
  const [busca, setBusca] = useState("");
  const [resultados, setResultados] = useState<VideoVimeo[] | null>(null);
  const [erro, setErro] = useState("");
  const [buscando, setBuscando] = useState(false);

  async function buscar() {
    setBuscando(true);
    setErro("");
    try {
      setResultados(await api.videosVimeo({ busca: busca.trim(), limite: 10 }));
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setBuscando(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <Campo rotulo="Vídeo de resolução (id do Vimeo)" dica="Vazio tira o vídeo.">
        {(cid) => <input id={cid} value={vimeoId} onChange={(e) => aoEscolher(e.target.value)} placeholder="Ex.: 123456789" className="campo font-mono" />}
      </Campo>
      <div className="flex flex-wrap gap-2">
        <label htmlFor="busca-vimeo" className="sr-only">Buscar vídeo no Vimeo</label>
        <input id="busca-vimeo" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Buscar no Vimeo pelo título" className="campo min-w-48 flex-1"
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void buscar(); } }} />
        <Botao variante="secundario" tamanho="pequeno" disabled={buscando || !busca.trim()} onClick={() => void buscar()}>{buscando ? "Buscando…" : "Buscar"}</Botao>
      </div>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {resultados && (
        <ul className="max-h-56 divide-y divide-borda overflow-y-auto rounded-cartao border border-borda">
          {resultados.length === 0 && <li className="px-3 py-2 text-[15px] text-suave">Nada encontrado.</li>}
          {resultados.map((v) => (
            <li key={v.id}>
              <button type="button" onClick={() => { aoEscolher(v.id); setResultados(null); }} className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left hover:bg-canvas">
                <span className="truncate text-[15px]">{v.titulo}</span>
                <span className="shrink-0 text-[13px] text-suave">{duracao(v.duracao_segundos)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Figuras({ questao, aoAnexar }: { questao: QuestaoDetalhada; aoAnexar: () => void }) {
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [parte, setParte] = useState("ENUNCIADO");
  const [letra, setLetra] = useState("A");
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  async function anexar() {
    if (!arquivo) return;
    setEnviando(true);
    setErro("");
    try {
      await api.anexarFigura(questao.questao_id, arquivo, parte, parte === "ALTERNATIVA" ? letra : undefined);
      setArquivo(null);
      aoAnexar();
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <Cartao className="flex flex-col gap-3 p-5">
      <TituloDeSecao>Figuras</TituloDeSecao>
      <p className="-mt-1 text-[13px] text-suave">A figura entra na primeira marca figura:pendente da parte escolhida; sem marca, no fim (na alternativa, a marca é obrigatória). PNG, JPEG, WEBP ou GIF até 2 MB.</p>
      {questao.figuras.length > 0 && (
        <ul className="grid grid-cols-3 gap-2">
          {questao.figuras.map((f) => (
            <li key={f.figura_id} className="rounded-md border border-borda p-1.5 text-center">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={`/api/aluno/figuras/${f.figura_id}`} alt={`Figura ${f.figura_id}`} className="h-20 w-full object-contain" />
              <span className="text-[11px] text-suave">{f.parte.toLowerCase()} · #{f.figura_id}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <input type="file" aria-label="Arquivo da figura" accept="image/png,image/jpeg,image/webp,image/gif" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} className="min-w-0 flex-1 text-sm" />
        <select aria-label="Parte da questão" value={parte} onChange={(e) => setParte(e.target.value)} className="campo w-auto">
          <option value="ENUNCIADO">Enunciado</option>
          <option value="ALTERNATIVA">Alternativa</option>
          <option value="RESOLUCAO">Resolução</option>
        </select>
        {parte === "ALTERNATIVA" && (
          <select aria-label="Letra da alternativa" value={letra} onChange={(e) => setLetra(e.target.value)} className="campo w-auto">
            {LETRAS.map((l) => (
              <option key={l} value={l}>{l}</option>
            ))}
          </select>
        )}
      </div>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <div>
        <Botao variante="secundario" disabled={!arquivo || enviando} onClick={() => void anexar()}>{enviando ? "Anexando…" : "Anexar figura"}</Botao>
      </div>
    </Cartao>
  );
}
