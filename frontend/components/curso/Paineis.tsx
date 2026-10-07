"use client";

// O que se abre dentro de um sub-módulo para pôr conteúdo nele: vídeos do Vimeo, questão, aula ao
// vivo e a etiqueta de assunto. O PDF usa o EscolherPdf, que já existia.

import { X } from "lucide-react";
import Link from "next/link";
import { useState, type FormEvent, type ReactNode } from "react";
import { CampoCategoria } from "@/components/Categoria";
import { ColocarVideo } from "@/components/ColocarVideo";
import { BuscaNoBanco } from "@/components/MontarProva";
import { PdfDaAulaAoVivo } from "@/components/Pdf";
import { Aviso, Botao, BotaoLink, Campo } from "@/components/ui";
import { abrirEmNovaAba, api, type Assunto, type Aula, type Modulo, type SubModulo, type VideoVimeo } from "@/lib/api";
import { duracao, emBrasilia, plural } from "@/lib/formato";
import { Azulejo, BIBLIOTECA, type Executar, type Tipo } from "./comum";

/** A moldura comum: o tipo do que entra, o que vai acontecer e o X de fechar. */
export function Painel({ tipo, titulo, legenda, aoFechar, children }: { tipo?: Tipo; titulo: string; legenda?: ReactNode; aoFechar: () => void; children: ReactNode }) {
  return (
    <div className="rounded-cartao border border-borda bg-canvas/70 p-4">
      <div className="mb-3 flex items-start gap-3">
        {tipo && <Azulejo tipo={tipo} />}
        <div className="min-w-0 flex-1">
          <h4 className="font-semibold text-tinta">{titulo}</h4>
          {legenda && <p className="text-[13px] text-suave">{legenda}</p>}
        </div>
        <button type="button" onClick={aoFechar} aria-label={`Fechar: ${titulo}`} className="flex size-8 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-papel hover:text-tinta">
          <X aria-hidden="true" className="size-[18px]" />
        </button>
      </div>
      {children}
    </div>
  );
}

// --- vídeos do Vimeo -----------------------------------------------------------

export function AdicionarVideos({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const [busca, setBusca] = useState("");
  const [resultados, setResultados] = useState<VideoVimeo[] | null>(null);
  const [escolhidos, setEscolhidos] = useState<Record<string, VideoVimeo>>({});
  const [buscando, setBuscando] = useState(false);
  const [erro, setErro] = useState("");

  async function buscar(e: FormEvent) {
    e.preventDefault();
    setBuscando(true);
    setErro("");
    try {
      setResultados(await api.videosVimeo({ busca: busca.trim(), limite: 25 }));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setBuscando(false);
    }
  }

  async function adicionar() {
    const videos = Object.values(escolhidos).map((v) => ({ vimeo_id: v.id, titulo: v.titulo, embed_url: v.embed_url }));
    let recusados: string[] = [];
    const ok = await executar(
      async () => {
        recusados = (await api.adicionarVideos(BIBLIOTECA, modulo.id, sub.id, videos)).erros;
      },
      () =>
        `${plural(videos.length - recusados.length, "vídeo adicionado", "vídeos adicionados")} em ${sub.nome}, já ${videos.length - recusados.length === 1 ? "publicado" : "publicados"}.` +
        (recusados.length ? ` Ficaram de fora: ${recusados.join("; ")}.` : ""),
    );
    if (ok) aoFechar();
  }

  const quantos = Object.keys(escolhidos).length;

  return (
    <Painel tipo="video" titulo="Adicionar vídeos" legenda="Busque no Vimeo pelo título e marque quantos quiser. Entram no fim da lista, já publicados." aoFechar={aoFechar}>
      <div className="flex flex-col gap-3">
        <form onSubmit={buscar} className="flex flex-wrap items-end gap-2">
          <Campo rotulo="Título no Vimeo" className="min-w-60 flex-1">
            {(id) => <input id={id} autoFocus value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Ex.: Aula 3, ou Q04" className="campo" />}
          </Campo>
          <Botao type="submit" variante="secundario" disabled={buscando}>{buscando ? "Buscando…" : "Buscar"}</Botao>
        </form>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        {resultados && resultados.length === 0 && <p className="text-[15px] text-suave">Nenhum vídeo com esse título. Tente uma parte menor do nome.</p>}
        {resultados && resultados.length > 0 && (
          <ul className="max-h-72 divide-y divide-borda overflow-y-auto rounded-xl border border-borda bg-papel">
            {resultados.map((v) => (
              <li key={v.id}>
                <label className="flex cursor-pointer items-center gap-3 px-3 py-2.5 hover:bg-canvas">
                  <input
                    type="checkbox"
                    checked={!!escolhidos[v.id]}
                    onChange={(e) =>
                      setEscolhidos((atual) => {
                        const novo = { ...atual };
                        if (e.target.checked) novo[v.id] = v;
                        else delete novo[v.id];
                        return novo;
                      })
                    }
                    className="size-4 accent-acento"
                  />
                  <span className="min-w-0 flex-1 truncate text-[15px]">{v.titulo}</span>
                  <span className="shrink-0 text-[13px] tabular-nums text-suave">{duracao(v.duracao_segundos)}</span>
                </label>
              </li>
            ))}
          </ul>
        )}
        {resultados && resultados.length > 0 && (
          <div>
            <Botao variante="primario" disabled={!quantos} onClick={() => void adicionar()}>
              {quantos ? `Adicionar ${plural(quantos, "vídeo")}` : "Marque os vídeos"}
            </Botao>
          </div>
        )}
      </div>
    </Painel>
  );
}

// --- questão -------------------------------------------------------------------

/** Uma linha de questão: criada agora, no editor, ou tirada do acervo. Já sai publicada. */
export function AdicionarQuestao({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const [doAcervo, setDoAcervo] = useState(false);
  const jaNaAula = new Set<number | undefined>(sub.itens.map((i) => i.questao?.questao_id).filter((id) => id !== undefined));
  const destino = encodeURIComponent(`${modulo.nome} › ${sub.nome}`);

  return (
    <Painel tipo="questao" titulo="Adicionar questão" legenda="O aluno responde ali mesmo, uma vez só, e vê o gabarito e a resolução na hora. Entra publicada." aoFechar={aoFechar}>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap gap-2">
          <BotaoLink variante="primario" href={`/admin/questoes/editar/?submodulo=${sub.id}&modulo=${modulo.id}&destino=${destino}`}>Escrever uma questão nova</BotaoLink>
          <Botao variante="secundario" onClick={() => setDoAcervo(!doAcervo)} aria-expanded={doAcervo}>Escolher do banco de questões</Botao>
        </div>
        {doAcervo && (
          <BuscaNoBanco
            jaNaProva={jaNaAula}
            rotuloJaEsta="Na aula"
            avisarReuso={false}
            aoEscolher={(q) => void executar(() => api.questaoNoSubmodulo(sub.id, { questao_id: q.questao_id }), `Questão #${q.questao_id} adicionada em ${sub.nome}, já publicada.`)}
          />
        )}
      </div>
    </Painel>
  );
}

// --- aula ao vivo --------------------------------------------------------------

/** Agenda e já publica: a sala do Zoom abre agora, e a aula aparece no capítulo para a turma. */
export function NovaAulaAoVivo({ turmas, sub, categorias, executar, aoFechar }: { turmas: string[]; sub: SubModulo; categorias: string[]; executar: Executar; aoFechar: () => void }) {
  const [categoria, setCategoria] = useState("");
  const [titulo, setTitulo] = useState("");
  const [quando, setQuando] = useState("");
  const [minutos, setMinutos] = useState(90);
  const [descricao, setDescricao] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function agendar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    const ok = await executar(async () => {
      // O campo é hora local; o backend guarda em UTC. A conversão é do navegador.
      const aula = await api.agendarAula({
        titulo: titulo.trim(),
        inicio_em: new Date(quando).toISOString(),
        minutos,
        descricao: descricao.trim(),
        turmas,
        submodulo_id: sub.id,
        categoria: categoria.trim(),
      });
      await api.editarAula(aula.aula_id, { status: "PUBLICADO" });
    }, `"${titulo.trim()}" agendada em ${sub.nome}. A turma já vê no capítulo.`);
    setSalvando(false);
    if (ok) aoFechar();
  }

  if (turmas.length === 0) {
    return (
      <Painel tipo="aovivo" titulo="Agendar aula ao vivo" aoFechar={aoFechar}>
        <Aviso tom="atencao">Este módulo ainda não é de nenhuma turma. Escolha as turmas dele, no alto, e depois agende a aula.</Aviso>
      </Painel>
    );
  }

  return (
    <Painel
      tipo="aovivo"
      titulo="Agendar aula ao vivo"
      legenda={`A sala do Zoom é criada agora, para ${turmas.join(", ")}. A aula é gravada, e a gravação entra publicada aqui, em ${sub.nome}.`}
      aoFechar={aoFechar}
    >
      <form onSubmit={agendar} className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr_auto]">
          <Campo rotulo="Título">
            {(id) => <input id={id} autoFocus required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} placeholder="Estequiometria, aula 1" className="campo" />}
          </Campo>
          <Campo rotulo="Começa em">
            {(id) => <input id={id} type="datetime-local" required value={quando} onChange={(e) => setQuando(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Minutos">
            {(id) => <input id={id} type="number" min={5} max={480} value={minutos} onChange={(e) => setMinutos(Number(e.target.value))} className="campo w-24" />}
          </Campo>
        </div>
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr]">
          <Campo rotulo="Descrição" dica="Opcional; aparece para o aluno.">
            {(id) => <input id={id} maxLength={2000} value={descricao} onChange={(e) => setDescricao(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Categoria" dica="Ex.: Aula, Monitoria. Separa as lives no menu.">
            {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
          </Campo>
        </div>
        <div>
          <Botao type="submit" variante="primario" disabled={salvando || !titulo.trim() || !quando}>{salvando ? "Agendando…" : "Agendar aula"}</Botao>
        </div>
      </form>
    </Painel>
  );
}

const ESTADO_DA_AULA: Record<Aula["estado"], string> = {
  RASCUNHO: "rascunho, sem sala",
  AGENDADA: "agendada",
  AGUARDANDO: "sala aberta",
  ABERTA: "ao vivo agora",
  ENCERRADA: "encerrada",
};

/** As aulas ao vivo ligadas ao sub-módulo: ficam no alto, fora da fila, porque não têm ordem. */
export function AulasDoSubmodulo({ aulas, executar }: { aulas: Aula[]; executar: Executar }) {
  const [erro, setErro] = useState("");

  const iniciar = async (aula: Aula) => {
    setErro("");
    try {
      await abrirEmNovaAba(() => api.iniciarAula(aula.aula_id));
    } catch (ex) {
      setErro((ex as Error).message);
    }
  };

  return (
    <div className="flex flex-col">
      {erro && <Aviso tom="erro" className="mx-3 mt-3">{erro}</Aviso>}
      <ul className="divide-y divide-borda/70">
        {aulas.map((aula) => {
          const semGravacao = aula.estado === "ENCERRADA" && !aula.gravacao_item_id && aula.gravacao !== "enviando";
          const situacao = [
            `${aula.minutos} min`,
            ESTADO_DA_AULA[aula.estado],
            aula.gravacao === "enviando" && "gravação indo para o Vimeo",
            aula.gravacao_item_id && "gravação publicada no curso",
            semGravacao && "sem gravação",
          ].filter(Boolean);
          return (
            <li key={aula.aula_id} className="flex items-start gap-2 py-2.5 pl-10 pr-3 sm:pl-11">
              <Azulejo tipo="aovivo" />
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate font-medium text-tinta">{aula.titulo}</p>
                    <p className="text-[13px] text-suave">Aula ao vivo em {emBrasilia(aula.inicio_em)}, {situacao.join(", ")}</p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {aula.tem_sala && aula.estado !== "ENCERRADA" && (
                      <Botao tamanho="pequeno" variante="primario" onClick={() => void iniciar(aula)}>Iniciar</Botao>
                    )}
                    <Link href="/admin/aulas/" className="text-sm font-semibold text-acento hover:underline">Gerenciar</Link>
                  </div>
                </div>
                <PdfDaAulaAoVivo aula={aula} executar={executar} />
                {aula.estado === "ENCERRADA" && (
                  <ColocarVideo
                    aula={aula}
                    aoColocar={(link) => executar(() => api.colocarVideoNaAula(aula.aula_id, link), `Vídeo publicado no lugar da gravação de "${aula.titulo}".`)}
                  />
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// --- etiqueta de assunto -------------------------------------------------------

export function Classificar({ modulo, sub, assuntos, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const [assunto, setAssunto] = useState("");
  const [subassunto, setSubassunto] = useState("");
  const [faixa, setFaixa] = useState("");
  const escolhido = assuntos.find((a) => String(a.id) === assunto);

  async function aplicar(e: FormEvent) {
    e.preventDefault();
    const ok = await executar(
      () => api.classificar(BIBLIOTECA, modulo.id, sub.id, { assunto, subassunto: subassunto || undefined, itens: faixa.trim() || undefined }),
      `Assunto aplicado aos vídeos de ${sub.nome}.`,
    );
    if (ok) aoFechar();
  }

  return (
    <Painel titulo="Classificar os vídeos por assunto" legenda="O assunto é o que alimenta o desempenho do aluno. Vale para os vídeos deste sub-módulo." aoFechar={aoFechar}>
      {!assuntos.length ? (
        <Aviso tom="atencao">
          Nenhum assunto cadastrado. <Link href="/admin/assuntos/" className="font-semibold underline">Cadastre em Assuntos</Link>.
        </Aviso>
      ) : (
        <form onSubmit={aplicar} className="grid gap-3 sm:grid-cols-[1fr_1fr_14rem_auto] sm:items-end">
          <Campo rotulo="Assunto">
            {(id) => (
              <select id={id} required value={assunto} onChange={(e) => { setAssunto(e.target.value); setSubassunto(""); }} className="campo">
                <option value="">Escolha…</option>
                {assuntos.map((a) => (
                  <option key={a.id} value={a.id}>{a.nome}</option>
                ))}
              </select>
            )}
          </Campo>
          <Campo rotulo="Sub-assunto">
            {(id) => (
              <select id={id} value={subassunto} onChange={(e) => setSubassunto(e.target.value)} className="campo" disabled={!escolhido?.subassuntos.length}>
                <option value="">Nenhum</option>
                {escolhido?.subassuntos.map((s) => (
                  <option key={s.id} value={s.id}>{s.nome}</option>
                ))}
              </select>
            )}
          </Campo>
          <Campo rotulo="Quais vídeos (em branco, todos)">
            {(id) => <input id={id} value={faixa} onChange={(e) => setFaixa(e.target.value)} placeholder="Q01-Q03" className="campo" />}
          </Campo>
          <Botao type="submit" variante="primario" disabled={!assunto}>Aplicar</Botao>
        </form>
      )}
    </Painel>
  );
}
