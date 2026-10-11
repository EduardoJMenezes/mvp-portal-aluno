"use client";

// O formulário da questão, inteiro: enunciado, alternativas com gabarito e comentário de cada uma,
// dificuldade, assunto, vídeo de resolução e resolução comentada, mais a prévia de como o aluno
// vê. É o mesmo no editor do banco e no modal de "Escrever questão nova" de um simulado: questão
// criada por um caminho não pode nascer com menos campos que pelo outro.

import { ImagePlus } from "lucide-react";
import { useId, useMemo, useRef, useState, type ClipboardEvent, type Dispatch, type Ref, type SetStateAction, type TextareaHTMLAttributes } from "react";
import { CampoDeVideo, type VideoNoCampo } from "@/components/CampoDeVideo";
import { Botao, BotaoIcone, Campo, Cartao, Etiqueta } from "@/components/ui";
import { api, type Assunto } from "@/lib/api";
import { DIFICULDADE, LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export type Formulario = {
  enunciado: string;
  alternativas: Record<string, string>;
  /** Por letra: o que o aluno lê se marcar aquela alternativa. */
  comentarios: Record<string, string>;
  gabarito: string;
  dificuldade: string;
  assunto: string;
  subassunto: string;
  vimeo_id: string;
  resolucao_comentada: string;
  imagem_pendente: boolean;
};

export const VAZIO: Formulario = {
  enunciado: "",
  alternativas: { A: "", B: "", C: "", D: "", E: "" },
  comentarios: { A: "", B: "", C: "", D: "", E: "" },
  gabarito: "",
  dificuldade: "MEDIA",
  assunto: "",
  subassunto: "",
  vimeo_id: "",
  resolucao_comentada: "",
  imagem_pendente: false,
};

// A E é opcional: vazia, a questão vai de A a D.
export const alternativasPreenchidas = (alternativas: Record<string, string>) =>
  Object.fromEntries(Object.entries(alternativas).filter(([letra, texto]) => letra !== "E" || texto.trim()));

// Comentário só vale em alternativa que existe: a E em branco não leva o dela.
export const comentariosPreenchidos = (form: Formulario) =>
  Object.fromEntries(LETRAS.filter((l) => form.alternativas[l]?.trim() && form.comentarios[l]?.trim()).map((l) => [l, form.comentarios[l].trim()]));

/** O que falta para a questão poder ser gravada, em palavras; vazio quando está tudo certo. */
export function faltaNaQuestao(form: Formulario, publicada = false): string {
  if (!form.enunciado.trim()) return "Escreva o enunciado.";
  if (!publicada && LETRAS.some((l) => l !== "E" && !form.alternativas[l]?.trim())) return "Preencha as alternativas de A a D; a E é opcional.";
  if (!form.gabarito) return "Marque o gabarito.";
  if (!form.alternativas[form.gabarito]?.trim()) return `O gabarito é a ${form.gabarito}, mas a alternativa ${form.gabarito} está em branco.`;
  return "";
}

const IMAGENS = "image/png,image/jpeg,image/webp,image/gif";

/**
 * Um texto de questão que aceita figura: pelo botão, ou colando a imagem (Ctrl+V) direto no
 * campo. A imagem sobe na hora e entra no texto como `![](figura:ID)`, no lugar do cursor; a
 * prévia ao lado já a mostra. Ela passa a ser da questão quando a questão é gravada.
 *
 * `emLinha`: a figura fica no meio do texto (alternativa), e o botão é só o ícone, ao lado. Sem
 * isso, ela ganha um parágrafo só dela (enunciado, resolução), e o botão fica embaixo.
 */
function TextoComFigura({
  valor,
  aoMudar,
  onde,
  emLinha = false,
  ref,
  ...resto
}: Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, "value" | "onChange"> & { valor: string; aoMudar: (valor: string) => void; onde: string; emLinha?: boolean; ref?: Ref<HTMLTextAreaElement> }) {
  const campo = useRef<HTMLTextAreaElement | null>(null);
  const arquivo = useRef<HTMLInputElement>(null);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState("");

  async function inserir(imagem: File) {
    setErro("");
    setEnviando(true);
    try {
      const { figura_id } = await api.guardarFigura(imagem);
      const el = campo.current;
      const inicio = el ? el.selectionStart : valor.length;
      const fim = el ? el.selectionEnd : inicio;
      const antes = valor.slice(0, inicio);
      const depois = valor.slice(fim);
      const marca = `![](figura:${figura_id})`;
      aoMudar(
        emLinha
          ? antes + marca + depois
          : (antes.trim() ? antes.replace(/\s*$/, "\n\n") : "") + marca + (depois.trim() ? "\n\n" + depois.replace(/^\s*/, "") : ""),
      );
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  function colar(e: ClipboardEvent<HTMLTextAreaElement>) {
    const imagem = [...e.clipboardData.files].find((f) => f.type.startsWith("image/"));
    if (!imagem) return;
    // Colou um print: ele vira figura, em vez de o navegador ignorar.
    e.preventDefault();
    void inserir(imagem);
  }

  const texto = (
    <textarea
      {...resto}
      ref={(el) => {
        campo.current = el;
        if (typeof ref === "function") ref(el);
        else if (ref) ref.current = el;
      }}
      value={valor}
      onChange={(e) => aoMudar(e.target.value)}
      onPaste={colar}
    />
  );
  const escolher = <input ref={arquivo} type="file" accept={IMAGENS} hidden onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void inserir(f); }} />;

  if (emLinha) {
    return (
      <div className="flex flex-col gap-1">
        <div className="flex items-start gap-1">
          <div className="min-w-0 flex-1">{texto}</div>
          <BotaoIcone rotulo={`Inserir imagem ${onde}`} icone={ImagePlus} ocupado={enviando} onClick={() => arquivo.current?.click()} className="mt-1" />
        </div>
        {escolher}
        {erro && <p role="alert" className="text-[13px] text-erro">{erro}</p>}
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-1.5">
      {texto}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Botao tamanho="mini" ocupado={enviando} onClick={() => arquivo.current?.click()}>
          <ImagePlus aria-hidden="true" className="size-3.5" />
          Inserir imagem {onde}
        </Botao>
        <span className="text-[13px] text-suave">ou cole a imagem direto no texto (Ctrl+V)</span>
        {erro && <p role="alert" className="basis-full text-[13px] text-erro">{erro}</p>}
      </div>
      {escolher}
    </div>
  );
}

/**
 * Os campos. `publicada`: a questão já está no ar — nela, alternativa em branco é alternativa
 * removida, e nenhuma letra é obrigatória.
 */
export function CamposDaQuestao({
  form,
  setForm,
  assuntos,
  publicada = false,
  video,
  setVideo,
  enunciadoRef,
}: {
  form: Formulario;
  setForm: Dispatch<SetStateAction<Formulario>>;
  assuntos: Assunto[];
  publicada?: boolean;
  /** O que se sabe do vídeo de resolução além do número: título e pasta, para mostrar no campo. */
  video: VideoNoCampo | null;
  setVideo: (video: VideoNoCampo | null) => void;
  enunciadoRef?: Ref<HTMLTextAreaElement>;
}) {
  const grupo = useId();
  const muda = <K extends keyof Formulario>(chave: K, valor: Formulario[K]) => setForm((f) => ({ ...f, [chave]: valor }));
  const assuntoEscolhido = assuntos.find((a) => String(a.id) === form.assunto);
  return (
    <>
    <Cartao className="flex flex-col gap-4 p-5">
      <Campo rotulo="Enunciado" dica={<>Markdown. Fórmula entre $…$ (química em \ce&#123;…&#125;). Imagem que você ainda não tem: ![](figura:pendente).</>}>
        {(cid) => <TextoComFigura id={cid} ref={enunciadoRef} required rows={8} valor={form.enunciado} aoMudar={(v) => muda("enunciado", v)} onde="no enunciado" className="campo" />}
      </Campo>
      <fieldset className="flex flex-col gap-4">
        <legend className="mb-1 text-sm font-semibold text-tinta-2">Alternativas, gabarito e comentários</legend>
        <p className="-mt-2 text-[13px] text-suave">
          Clique na letra para marcar o gabarito. Embaixo de cada alternativa, diga em uma ou duas frases por que o aluno costuma marcá-la e onde está o erro; na correta, por que é ela. O aluno lê o comentário da que marcou, logo depois de responder. É opcional, mas é o que transforma a correção em explicação.
        </p>
        {LETRAS.map((letra) => (
          <div key={letra} className="flex items-start gap-2">
            <label className={`mt-1.5 flex size-8 shrink-0 items-center justify-center rounded-full border text-sm font-semibold cursor-pointer ${form.gabarito === letra ? "border-sucesso bg-sucesso text-white" : "border-borda-campo text-tinta-2"}`}>
              <input type="radio" name={grupo} value={letra} checked={form.gabarito === letra} onChange={() => muda("gabarito", letra)} className="sr-only" />
              <span aria-hidden="true">{letra}</span>
              <span className="sr-only">Gabarito {letra}</span>
            </label>
            <div className="flex min-w-0 flex-1 flex-col gap-1.5">
              <TextoComFigura
                emLinha
                onde={`na alternativa ${letra}`}
                aria-label={letra === "E" ? "Alternativa E (opcional)" : `Alternativa ${letra}`}
                placeholder={publicada ? "Em branco, a alternativa sai da questão" : letra === "E" ? "Opcional: em branco, a questão vai de A a D" : undefined}
                required={letra !== "E" && !publicada}
                rows={2}
                valor={form.alternativas[letra]}
                aoMudar={(v) => setForm((f) => ({ ...f, alternativas: { ...f.alternativas, [letra]: v } }))}
                className="campo min-h-0"
              />
              {(publicada ? form.alternativas[letra].trim() : letra !== "E" || form.alternativas.E.trim()) && (
                <textarea
                  aria-label={`Comentário da alternativa ${letra}`}
                  placeholder={form.gabarito === letra ? `Comentário: por que a ${letra} é a correta` : `Comentário: por que alguém marca a ${letra}, e onde está o erro`}
                  rows={1}
                  maxLength={1000}
                  value={form.comentarios[letra]}
                  onChange={(e) => setForm((f) => ({ ...f, comentarios: { ...f.comentarios, [letra]: e.target.value } }))}
                  className="campo min-h-0 border-borda bg-canvas/60 font-sans text-[14px]"
                />
              )}
            </div>
          </div>
        ))}
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
        <input type="checkbox" checked={form.imagem_pendente} onChange={(e) => muda("imagem_pendente", e.target.checked)} className="size-4 accent-acento" />
        Imagem pendente
      </label>
      <Campo rotulo="Assunto">
        {(cid) => (
          <select id={cid} value={form.assunto} onChange={(e) => setForm((f) => ({ ...f, assunto: e.target.value, subassunto: "" }))} className="campo">
            <option value="">Sem classificação</option>
            {assuntos.map((a) => (
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
      <CampoDeVideo
        rotulo="Vídeo de resolução"
        dica="O aluno assiste depois de responder. Escolha pelas pastas do Vimeo ou pela busca, e confira antes de usar."
        tituloDaEscolha="Escolher o vídeo de resolução"
        video={form.vimeo_id ? (video?.vimeo_id === form.vimeo_id ? video : { vimeo_id: form.vimeo_id }) : null}
        aoMudar={(v) => {
          setVideo(v);
          muda("vimeo_id", v?.vimeo_id ?? "");
        }}
      />
      <Campo rotulo="Resolução comentada" dica="O aluno vê depois que o simulado fecha — ou, na aula, assim que responde.">
        {(cid) => <TextoComFigura id={cid} rows={5} valor={form.resolucao_comentada} aoMudar={(v) => muda("resolucao_comentada", v)} onde="na resolução" className="campo" />}
      </Campo>
    </Cartao>
    </>
  );
}

export function Previa({ form, soPreenchidas = false }: { form: Formulario; soPreenchidas?: boolean }) {
  const vazia = useMemo(() => !form.enunciado.trim(), [form.enunciado]);
  return (
    <Cartao className="flex flex-col gap-4 p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-suave">Como o aluno vê</h2>
        {form.gabarito && <Etiqueta tom="sucesso">Gabarito {form.gabarito}</Etiqueta>}
      </div>
      {vazia ? <p className="text-[15px] text-suave">A prévia aparece enquanto você escreve.</p> : <TextoFormatado texto={form.enunciado} />}
      <ul className="flex flex-col gap-2">
        {LETRAS.filter((letra) => (soPreenchidas ? form.alternativas[letra]?.trim() : letra !== "E" || form.alternativas.E?.trim())).map((letra) => (
          <li key={letra} className={`flex items-start gap-3 rounded-cartao border px-3 py-2 ${form.gabarito === letra ? "border-sucesso-borda bg-sucesso-fundo" : "border-borda"}`}>
            <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-borda-campo text-xs font-semibold">{letra}</span>
            <div className="min-w-0 flex-1">
              <TextoFormatado texto={form.alternativas[letra] || " "} compacto />
              {form.comentarios[letra]?.trim() && <p className="mt-1 border-t border-borda pt-1 text-[13px] text-suave">{form.comentarios[letra]}</p>}
            </div>
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
