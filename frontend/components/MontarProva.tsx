"use client";

import { Plus, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { BancoDeQuestoes } from "@/components/BancoDeQuestoes";
import { Modal, OffCanvas, useSaida } from "@/components/Camadas";
import type { VideoNoCampo } from "@/components/CampoDeVideo";
import { CamposDaQuestao, Previa, VAZIO, alternativasPreenchidas, comentariosPreenchidos, faltaNaQuestao, type Formulario } from "@/components/FormularioDaQuestao";
import { Aviso, Botao, Etiqueta } from "@/components/ui";
import { api, useDados, type Questao } from "@/lib/api";
import { plural } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

/**
 * A questão escrita na hora, que nasce no rascunho do simulado. Leva tudo o que o editor do banco
 * leva: `form` é o formulário como foi preenchido (para reabrir e corrigir antes de criar o
 * simulado), e `assunto`/`subassunto` são os nomes, que é o que a API recebe.
 */
export type QuestaoNovaNaProva = {
  chave: string;
  questao_id?: undefined;
  enunciado: string;
  alternativas: Record<string, string>;
  gabarito: string;
  dificuldade: string;
  assunto?: string;
  subassunto?: string;
  form?: Formulario;
  video?: VideoNoCampo | null;
};

// Uma questão da prova: do banco (pelo id) ou nova.
export type EntradaDaProva = { chave: string; questao_id: number; enunciado: string; gabarito?: string | null } | QuestaoNovaNaProva;

export const paraApi = (entradas: EntradaDaProva[]) =>
  entradas.map((e) =>
    e.questao_id !== undefined
      ? e.questao_id
      : {
          enunciado: e.enunciado,
          alternativas: e.alternativas,
          gabarito: e.gabarito,
          dificuldade: e.dificuldade,
          assunto: e.assunto || undefined,
          subassunto: e.subassunto || undefined,
          vimeo_id: e.form?.vimeo_id.trim() || undefined,
          resolucao_comentada: e.form?.resolucao_comentada.trim() || undefined,
          comentarios: e.form ? comentariosPreenchidos(e.form) : undefined,
          imagem_pendente: e.form?.imagem_pendente || undefined,
        },
  );

export const doBanco = (q: { questao_id: number; enunciado: string; gabarito?: string | null }): EntradaDaProva => ({
  chave: `q${q.questao_id}`,
  questao_id: q.questao_id,
  enunciado: q.enunciado,
  gabarito: q.gabarito,
});

export function MontarProva({
  entradas,
  aoMudar,
  permiteNova,
}: {
  entradas: EntradaDaProva[];
  aoMudar: (entradas: EntradaDaProva[]) => void;
  permiteNova: boolean;
}) {
  // "nova" abre o formulário em branco; um número reabre a questão nova daquela posição.
  const [painel, setPainel] = useState<"banco" | "nova" | number | null>(null);
  const editada = typeof painel === "number" ? entradas[painel] : undefined;

  const mover = (i: number, passo: -1 | 1) => {
    const nova = [...entradas];
    [nova[i], nova[i + passo]] = [nova[i + passo], nova[i]];
    aoMudar(nova);
  };

  return (
    <div className="flex flex-col gap-3">
      {entradas.length === 0 ? (
        <p className="rounded-cartao border border-dashed border-borda px-4 py-6 text-center text-[15px] text-suave">Nenhuma questão ainda. Escolha do banco ou escreva uma nova.</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {entradas.map((e, i) => (
            <li key={e.chave} className="flex items-start gap-3 rounded-cartao border border-borda bg-papel px-3 py-2.5">
              <span className="mt-0.5 w-7 shrink-0 text-right font-semibold tabular-nums text-suave">{i + 1}.</span>
              <div className="min-w-0 flex-1">
                <div className="mb-1 flex flex-wrap gap-1.5">
                  {e.questao_id !== undefined ? <Etiqueta>#{e.questao_id}</Etiqueta> : <Etiqueta tom="atencao">Nova</Etiqueta>}
                  {e.gabarito && <Etiqueta tom="sucesso">Gabarito {e.gabarito}</Etiqueta>}
                  {e.questao_id === undefined && e.assunto && <Etiqueta tom="info">{e.subassunto ? `${e.assunto} › ${e.subassunto}` : e.assunto}</Etiqueta>}
                </div>
                <div className="line-clamp-2 text-[15px]">
                  <TextoFormatado texto={e.enunciado} compacto />
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-0.5">
                {e.questao_id === undefined && permiteNova && (
                  <Botao tamanho="mini" className="mr-1" onClick={() => setPainel(i)}>Editar</Botao>
                )}
                <BotaoIcone rotulo={`Subir questão ${i + 1}`} disabled={i === 0} onClick={() => mover(i, -1)}>↑</BotaoIcone>
                <BotaoIcone rotulo={`Descer questão ${i + 1}`} disabled={i === entradas.length - 1} onClick={() => mover(i, 1)}>↓</BotaoIcone>
                <BotaoIcone rotulo={`Tirar questão ${i + 1}`} onClick={() => aoMudar(entradas.filter((_, j) => j !== i))}>×</BotaoIcone>
              </div>
            </li>
          ))}
        </ol>
      )}

      <div className="flex flex-wrap gap-2">
        <Botao tamanho="pequeno" variante="secundario" onClick={() => setPainel("banco")}>
          <Search aria-hidden="true" className="size-4" />
          Escolher do banco
        </Botao>
        {permiteNova && (
          <Botao tamanho="pequeno" onClick={() => setPainel("nova")}>
            <Plus aria-hidden="true" className="size-4" />
            Escrever questão nova
          </Botao>
        )}
      </div>
      {painel === "banco" && <EscolherDoBanco entradas={entradas} aoEscolher={(q) => aoMudar([...entradas, doBanco(q)])} aoFechar={() => setPainel(null)} />}
      {(painel === "nova" || (typeof painel === "number" && editada?.questao_id === undefined)) && (
        <EscreverQuestao
          // A chave garante um formulário novo a cada abertura, e o da questão certa ao editar.
          key={typeof painel === "number" ? `e${painel}` : "nova"}
          inicial={editada && editada.questao_id === undefined ? editada : undefined}
          posicao={typeof painel === "number" ? painel + 1 : entradas.length + 1}
          aoGravar={(q) => {
            aoMudar(typeof painel === "number" ? entradas.map((e, j) => (j === painel ? q : e)) : [...entradas, q]);
          }}
          aoFechar={() => setPainel(null)}
        />
      )}
    </div>
  );
}

function BotaoIcone({ rotulo, children, ...resto }: { rotulo: string; children: string; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" aria-label={rotulo} title={rotulo} className="flex size-8 items-center justify-center rounded-md text-lg text-suave hover:bg-canvas hover:text-tinta disabled:opacity-30 disabled:hover:bg-transparent" {...resto}>
      {children}
    </button>
  );
}

/**
 * O banco de questões aberto ao lado: a prova continua à vista atrás, e cada "Adicionar" põe a
 * questão no fim dela sem fechar o painel.
 */
function EscolherDoBanco({ entradas, aoEscolher, aoFechar }: { entradas: EntradaDaProva[]; aoEscolher: (q: Questao) => void; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const doBancoNaProva = new Map(entradas.flatMap((e, i) => (e.questao_id !== undefined ? [[e.questao_id, i + 1] as const] : [])));
  return (
    <OffCanvas
      {...saida}
      aoFechar={saida.fechar}
      lado="direita"
      tamanho="grande"
      semMargem
      titulo="Questões do banco"
      legenda="Só as publicadas. Cada uma entra no fim da prova; a ordem você acerta depois, na lista."
      rodape={
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-tinta-2">{entradas.length ? `${plural(entradas.length, "questão", "questões")} na prova` : "A prova ainda está vazia"}</p>
          <Botao variante="primario" onClick={saida.fechar}>Pronto</Botao>
        </div>
      }
    >
      <BancoDeQuestoes
        jaEsta={new Set(doBancoNaProva.keys())}
        marcaDeJaEsta={(q) => `Na prova, questão ${doBancoNaProva.get(q.questao_id)}`}
        aoEscolher={aoEscolher}
        esconder="Esconder as que já estão na prova"
        avisarAula
      />
    </OffCanvas>
  );
}

// A questão que está sendo escrita fica guardada no navegador até entrar na prova: fechar o modal
// sem querer (Esc, o X, "Fechar"), ou até a aba, não joga fora o que já foi escrito.
const GUARDADA = "questao-nova-em-andamento";
type EmAndamento = { form: Formulario; video: VideoNoCampo | null };

function lerEmAndamento(): EmAndamento | null {
  try {
    const cru = localStorage.getItem(GUARDADA);
    const lido = cru ? (JSON.parse(cru) as EmAndamento) : null;
    // Formulário de uma versão antiga, sem algum campo: completa com o vazio.
    return lido?.form ? { form: { ...VAZIO, ...lido.form }, video: lido.video ?? null } : null;
  } catch {
    return null;
  }
}
function guardarEmAndamento(atual: EmAndamento | null) {
  try {
    if (atual) localStorage.setItem(GUARDADA, JSON.stringify(atual));
    else localStorage.removeItem(GUARDADA);
  } catch {
    // Navegador sem armazenamento (janela anônima restrita): a questão só não fica guardada.
  }
}
const igual = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/**
 * A questão escrita na hora, num modal de tela cheia: o mesmo formulário do editor do banco, com a
 * prévia de como o aluno vê ao lado. Nada vai para o servidor aqui — a questão nasce junto com o
 * rascunho do simulado e é aprovada com ele.
 *
 * Fechar não perde o trabalho. A questão nova fica guardada e volta ao reabrir; na que já está na
 * prova (`inicial`), sair com mudança por guardar pede confirmação.
 */
function EscreverQuestao({ inicial, posicao, aoGravar, aoFechar }: { inicial?: QuestaoNovaNaProva; posicao: number; aoGravar: (q: QuestaoNovaNaProva) => void; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const assuntos = useDados(() => api.assuntos());
  const [retomada] = useState(() => (inicial ? null : lerEmAndamento()));
  const [form, setForm] = useState<Formulario>(inicial?.form ?? retomada?.form ?? VAZIO);
  const [video, setVideo] = useState<VideoNoCampo | null>(inicial?.video ?? retomada?.video ?? null);
  const [avisoDeRetomada, setAvisoDeRetomada] = useState(!!retomada);
  const [saindo, setSaindo] = useState(false);
  const [erro, setErro] = useState("");

  // Cada mudança na questão nova vai para o navegador; em branco, não há o que guardar.
  useEffect(() => {
    if (inicial) return;
    guardarEmAndamento(igual(form, VAZIO) ? null : { form, video });
  }, [inicial, form, video]);

  function gravar() {
    const falta = faltaNaQuestao(form);
    if (falta) return setErro(falta);
    const assunto = assuntos.dados?.find((a) => String(a.id) === form.assunto);
    aoGravar({
      chave: inicial?.chave ?? `n${Date.now()}`,
      enunciado: form.enunciado,
      alternativas: alternativasPreenchidas(form.alternativas),
      gabarito: form.gabarito,
      dificuldade: form.dificuldade,
      assunto: assunto?.nome,
      subassunto: assunto?.subassuntos.find((s) => String(s.id) === form.subassunto)?.nome,
      form,
      video,
    });
    // Entrou na prova: o que estava guardado já cumpriu o papel.
    if (!inicial) guardarEmAndamento(null);
    saida.fechar();
  }

  const mudouAQueJaEstava = !!inicial && !igual(form, inicial.form ?? VAZIO);
  const pedirParaSair = () => {
    if (mudouAQueJaEstava && !saindo) setSaindo(true);
    else saida.fechar();
  };
  const comecarDoZero = () => {
    setForm(VAZIO);
    setVideo(null);
    setAvisoDeRetomada(false);
    setErro("");
  };

  return (
    <Modal
      {...saida}
      aoFechar={pedirParaSair}
      tamanho="tela"
      fechaClicandoFora={false}
      titulo={inicial ? `Questão ${posicao} da prova` : "Escrever questão nova"}
      legenda={inicial ? "Nasce em rascunho junto com o simulado e é aprovada com ele." : "Nasce em rascunho junto com o simulado e é aprovada com ele. O que você escreve aqui fica guardado neste navegador até entrar na prova."}
      rodape={
        saindo ? (
          <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-3">
            <p className="text-[15px] font-medium text-tinta">Sair sem guardar o que você mudou nesta questão?</p>
            <div className="flex gap-2">
              <Botao onClick={() => setSaindo(false)}>Continuar editando</Botao>
              <Botao variante="perigo" onClick={saida.fechar}>Sair sem guardar</Botao>
            </div>
          </div>
        ) : (
          <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-end gap-2">
            {erro && <p role="alert" className="mr-auto text-sm font-medium text-erro">{erro}</p>}
            <Botao onClick={pedirParaSair}>{inicial ? "Cancelar" : "Fechar e continuar depois"}</Botao>
            <Botao variante="primario" onClick={gravar}>{inicial ? "Guardar a questão" : "Adicionar à prova"}</Botao>
          </div>
        )
      }
    >
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-4">
        {avisoDeRetomada && (
          <Aviso tom="info" titulo="Você tinha uma questão começada">
            Ela voltou como estava.{" "}
            <Botao tamanho="mini" className="ml-1 align-middle" onClick={comecarDoZero}>Começar do zero</Botao>
          </Aviso>
        )}
        <div className="grid gap-5 lg:grid-cols-2">
          <div className="flex flex-col gap-4" data-foco-inicial>
            <CamposDaQuestao form={form} setForm={setForm} assuntos={assuntos.dados ?? []} video={video} setVideo={setVideo} />
          </div>
          <div className="lg:sticky lg:top-0 lg:self-start">
            <Previa form={form} />
          </div>
        </div>
      </div>
    </Modal>
  );
}
