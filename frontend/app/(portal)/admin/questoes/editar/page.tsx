"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState, type FormEvent } from "react";
import type { VideoNoCampo } from "@/components/CampoDeVideo";
import { CamposDaQuestao, Previa, VAZIO, alternativasPreenchidas, comentariosPreenchidos, type Formulario } from "@/components/FormularioDaQuestao";
import { proximoNome } from "@/components/curso/AdicionarQuestao";
import { Aviso, Botao, Campo, Cartao, Carregando, Pagina, Revelar, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type Assunto, type QuestaoDetalhada } from "@/lib/api";
import { plural } from "@/lib/formato";
import { LETRAS } from "@/lib/rotulos";

export default function PaginaDoEditor() {
  return (
    <Suspense>
      <Editor />
    </Suspense>
  );
}

function doDetalhe(q: QuestaoDetalhada, assuntos: Assunto[]): Formulario {
  const etiqueta = q.classificacao[0];
  const assunto = assuntos.find((a) => a.nome === etiqueta?.assunto);
  return {
    enunciado: q.enunciado,
    alternativas: { ...VAZIO.alternativas, ...q.alternativas },
    comentarios: { ...VAZIO.comentarios, ...(q.comentarios ?? {}) },
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
  const parametros = useSearchParams();
  const id = Number(parametros.get("id")) || null;
  // Vindo de Montar o curso: a questão nasce já como item deste sub-módulo.
  const submodulo = (!id && Number(parametros.get("submodulo"))) || null;
  const destino = parametros.get("destino") ?? "";
  // Quem veio da tela de montar o curso volta para o módulo em que estava.
  const moduloDeVolta = Number(parametros.get("modulo")) || null;
  const curso = moduloDeVolta ? `/admin/biblioteca/?modulo=${moduloDeVolta}` : "/admin/biblioteca/";
  const [nomeDaLinha, setNomeDaLinha] = useState(() => (submodulo ? (parametros.get("nome") ?? "") : ""));
  // O que se sabe do vídeo de resolução além do número: o título e a pasta, para mostrar no campo.
  const [video, setVideo] = useState<VideoNoCampo | null>(null);
  // "Criar e escrever a próxima" fica na tela; "Criar e pôr na aula" volta para o curso.
  const depois = useRef<"voltar" | "outra">("voltar");
  const [criada, setCriada] = useState("");
  const enunciado = useRef<HTMLTextAreaElement>(null);
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
      setVideo(questao.dados.resolucao);
    }
  }, [questao.dados, assuntos.dados]);

  // Questão de simulado que já abriu trava o que o aluno viu; classificação,
  // dificuldade e vídeo continuam editáveis.
  // Questão publicada se corrige mesmo com simulado aberto: o aluno recebe a versão nova, e o acerto
  // de quem já respondeu é refeito. Nela, alternativa em branco é alternativa removida.
  const publicada = questao.dados?.status === "PUBLICADO";
  const emProva = questao.dados?.simulados.filter((s) => s.situacao === "ABERTO" || s.situacao === "ENCERRADO") ?? [];
  const assuntoEscolhido = assuntos.dados?.find((a) => String(a.id) === form.assunto);
  // O backend aceita id ou nome; o nome deixa legível o resumo do rascunho.
  const nomeDoAssunto = assuntoEscolhido?.nome ?? "";
  const nomeDoSubassunto = assuntoEscolhido?.subassuntos.find((s) => String(s.id) === form.subassunto)?.nome ?? "";
  const muda = <K extends keyof Formulario>(chave: K, valor: Formulario[K]) => setForm((f) => ({ ...f, [chave]: valor }));

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setAviso("");
    setCriada("");
    if (!form.gabarito) return setErro("Marque o gabarito.");
    if (!form.alternativas[form.gabarito]?.trim()) return setErro(`O gabarito é a ${form.gabarito}, mas a alternativa ${form.gabarito} está em branco.`);
    // Mexer no que o aluno lê, numa questão que já tem resposta, pede um "tem certeza".
    const respostas = questao.dados?.respostas ?? 0;
    const removidas = LETRAS.filter((l) => original.alternativas[l]?.trim() && !form.alternativas[l]?.trim());
    const mexeNoQueOAlunoLe = form.enunciado !== original.enunciado || form.gabarito !== original.gabarito || LETRAS.some((l) => form.alternativas[l] !== original.alternativas[l]);
    if (id && respostas > 0 && mexeNoQueOAlunoLe) {
      const sim = await confirmar({
        titulo: "Corrigir uma questão já respondida?",
        texto:
          `${plural(respostas, "resposta já foi dada", "respostas já foram dadas")} a esta questão.` +
          (form.gabarito !== original.gabarito ? ` O acerto de cada uma é refeito com o gabarito ${form.gabarito}.` : "") +
          (removidas.length ? ` Quem marcou a ${removidas.join(" ou a ")} fica sem resposta nela.` : "") +
          " O aluno não é avisado.",
        confirmar: "Salvar correção",
      });
      if (!sim) return;
    }
    setSalvando(true);
    try {
      if (!id) {
        const nova = {
          enunciado: form.enunciado,
          alternativas: alternativasPreenchidas(form.alternativas),
          gabarito: form.gabarito,
          dificuldade: form.dificuldade,
          assunto: nomeDoAssunto || undefined,
          subassunto: nomeDoSubassunto || undefined,
          vimeo_id: form.vimeo_id.trim() || undefined,
          resolucao_comentada: form.resolucao_comentada || undefined,
          comentarios: comentariosPreenchidos(form),
        };
        if (submodulo) {
          const linha = await api.questaoNoSubmodulo(submodulo, { nome: nomeDaLinha.trim() || undefined, nova });
          if (depois.current === "voltar") {
            router.replace(curso);
            return;
          }
          // A próxima da apostila: a classificação costuma ser a mesma, e o nome da linha anda um.
          setForm({ ...VAZIO, assunto: form.assunto, subassunto: form.subassunto, dificuldade: form.dificuldade });
          setVideo(null);
          setNomeDaLinha(proximoNome(linha.nome));
          setCriada(`${linha.nome} criada em ${destino || "o sub-módulo"}, já publicada. O formulário está limpo para a próxima; o assunto e a dificuldade ficaram os mesmos.`);
          window.scrollTo({ top: 0 });
          enunciado.current?.focus();
          return;
        }
        const criada = await api.criarQuestao({ ...nova, imagem_pendente: form.imagem_pendente });
        // Já está no banco: segue para o editor dela, onde entram as figuras.
        router.replace(`/admin/questoes/editar/?id=${criada.questoes[0].questao_id}`);
        return;
      }
      const mudancas: Record<string, unknown> = {};
      if (form.enunciado !== original.enunciado) mudancas.enunciado = form.enunciado;
      // A E apagada vai vazia de propósito: é assim que o backend a tira da questão.
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
      // Só as letras cujo comentário mudou; vazio tira. A letra sem alternativa fica de fora.
      const comentarios = Object.fromEntries(
        LETRAS.filter((l) => form.alternativas[l]?.trim() && form.comentarios[l].trim() !== original.comentarios[l].trim()).map((l) => [l, form.comentarios[l].trim()]),
      );
      if (Object.keys(comentarios).length) mudancas.comentarios = comentarios;
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
      titulo={id ? `Questão #${id}` : submodulo ? "Nova questão na aula" : "Nova questão"}
      legenda={id ? undefined : submodulo ? `Entra no banco e vira uma linha de ${destino || "o sub-módulo"}, já publicada: o aluno responde ali e vê o gabarito na hora.` : "Entra no banco já publicada: fica disponível para simulados e para as aulas."}
      voltar={submodulo || moduloDeVolta ? { href: curso, rotulo: "Montar o curso" } : { href: "/admin/questoes/", rotulo: "Banco de questões" }}
      acoes={id && <Botao variante="perigo" onClick={() => remover()}>Remover</Botao>}
    >
      {dialogo}
      {questao.erro && <Aviso tom="erro">{questao.erro}</Aviso>}
      {criada && <Aviso tom="sucesso">{criada}</Aviso>}
      {emProva.length > 0 && (
        <Aviso tom="info" titulo="Esta questão está em simulado que já abriu">
          {emProva.map((s) => s.titulo).join(", ")}. Dá para corrigir: a mudança vale na hora, também para quem está fazendo a prova, e se o gabarito mudar o acerto de quem já respondeu é refeito. O aluno não é avisado.
        </Aviso>
      )}

      {!!questao.dados?.aulas?.length && (
        <Aviso tom="info" titulo="Esta questão está em aula">
          {questao.dados.aulas.join("; ")}. Lá o aluno vê o gabarito assim que responde — leve isso em conta antes de pôr a questão num simulado.
        </Aviso>
      )}

      <form onSubmit={salvar} className="grid gap-5 lg:grid-cols-2">
        <div className="flex flex-col gap-4">
          {submodulo && (
            <Cartao className="p-5">
              <Campo rotulo="Nome da linha na aula" dica='Como a questão aparece na lista do aluno, ex.: "Q04". Em branco, vira "Questão N".'>
                {(cid) => <input id={cid} value={nomeDaLinha} onChange={(e) => setNomeDaLinha(e.target.value)} maxLength={120} className="campo max-w-xs" />}
              </Campo>
            </Cartao>
          )}
          <CamposDaQuestao form={form} setForm={setForm} assuntos={assuntos.dados ?? []} publicada={publicada} video={video} setVideo={setVideo} enunciadoRef={enunciado} />

          {erro && <Aviso tom="erro">{erro}</Aviso>}
          {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
          <div className="flex flex-wrap gap-2">
            <Botao type="submit" variante="primario" ocupado={salvando} onClick={() => (depois.current = "voltar")}>{salvando ? "Salvando…" : id ? "Salvar questão" : submodulo ? "Criar e pôr na aula" : "Criar questão"}</Botao>
            {submodulo && <Botao type="submit" variante="secundario" disabled={salvando} onClick={() => (depois.current = "outra")}>Criar e escrever a próxima</Botao>}
          </div>
        </div>

        <div className="flex flex-col gap-4 lg:sticky lg:top-20 lg:self-start">
          <Previa form={form} soPreenchidas={publicada} />
          {id && questao.dados && (!publicada || questao.dados.figuras.length > 0) && <Figuras questao={questao.dados} somenteVer={publicada} aoAnexar={() => void questao.recarregar()} />}
          {!!questao.dados?.historico.length && <Correcoes mudancas={questao.dados.historico} />}
        </div>
      </form>
    </Pagina>
  );
}

/** As correções feitas na questão depois de publicada: quem, quando, o que mudou e como estava. */
function Correcoes({ mudancas }: { mudancas: QuestaoDetalhada["historico"] }) {
  const [aberta, setAberta] = useState<number | null>(null);
  return (
    <Cartao className="flex flex-col gap-3 p-5">
      <TituloDeSecao>Correções</TituloDeSecao>
      <p className="-mt-1 text-[13px] text-suave">O que mudou no que o aluno lê depois que a questão foi publicada. Só a equipe vê.</p>
      <ol className="flex flex-col divide-y divide-borda/70">
        {mudancas.map((m, i) => (
          <li key={i} className="flex flex-col gap-1 py-2.5 first:pt-0 last:pb-0">
            <p className="text-[15px] text-tinta">{m.resumo}</p>
            <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
              <p className="text-[13px] text-suave">{m.quem}, em {m.quando}</p>
              <Revelar aberto={aberta === i} aoAlternar={() => setAberta(aberta === i ? null : i)} className="text-[13px]">
                {aberta === i ? "Recolher" : "Ver como estava"}
              </Revelar>
            </div>
            {aberta === i && <pre className="surge mt-1 whitespace-pre-wrap rounded-md bg-canvas p-3 font-sans text-[13px] text-tinta-2">{m.antes}</pre>}
          </li>
        ))}
      </ol>
    </Cartao>
  );
}

function Figuras({ questao, aoAnexar, somenteVer = false }: { questao: QuestaoDetalhada; aoAnexar: () => void; somenteVer?: boolean }) {
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
      {!somenteVer && (
        <p className="-mt-1 text-[13px] text-suave">Para trocar uma marca de figura pendente: a figura entra na primeira marca figura:pendente da parte escolhida; sem marca, no fim (na alternativa, a marca é obrigatória). PNG, JPEG, WEBP ou GIF até 2 MB.</p>
      )}
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
      {!somenteVer && (
        <>
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
        <Botao variante="secundario" disabled={!arquivo} ocupado={enviando} onClick={() => anexar()}>{enviando ? "Anexando…" : "Anexar figura"}</Botao>
      </div>
        </>
      )}
    </Cartao>
  );
}
