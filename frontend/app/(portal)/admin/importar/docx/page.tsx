"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { EscolherDocx } from "@/components/Envio";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao } from "@/components/ui";
import { api, tokenDoLink, useDados, type LinkDeEnvio, type RevisaoDocx } from "@/lib/api";
import { LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDoDocx() {
  return (
    <Suspense>
      <ImportarDocx />
    </Suspense>
  );
}

function ImportarDocx() {
  const importacao = Number(useSearchParams().get("importacao")) || null;
  return importacao ? <Revisao id={importacao} /> : <Pedido />;
}

function Pedido() {
  const router = useRouter();
  const turmas = useDados(() => api.turmas());
  const [escolhidas, setEscolhidas] = useState<string[]>([]);
  const [titulo, setTitulo] = useState("");
  const [abre, setAbre] = useState("");
  const [fecha, setFecha] = useState("");
  const [duracao, setDuracao] = useState("");
  const [pasta, setPasta] = useState("");
  const [link, setLink] = useState<LinkDeEnvio | null>(null);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  async function gerar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    if (!escolhidas.length) return setErro("Escolha ao menos uma turma.");
    setOcupado(true);
    try {
      setLink(
        await api.linkDocx({
          turmas: escolhidas,
          titulo: titulo.trim() || undefined,
          abre_em: abre || undefined,
          fecha_em: fecha || undefined,
          duracao_minutos: duracao ? Number(duracao) : undefined,
          pasta_resolucao: pasta.trim() || undefined,
        }),
      );
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  async function enviar(arquivo: File) {
    if (!link) return;
    setErro("");
    setOcupado(true);
    try {
      await api.enviarDocx(tokenDoLink(link.link), arquivo);
      router.replace(`/admin/importar/docx/?importacao=${link.importacao_id}`);
    } catch (ex) {
      setErro((ex as Error).message);
      setOcupado(false);
    }
  }

  return (
    <Pagina titulo="Simulado em .docx" legenda="Primeiro os dados do simulado; depois o arquivo. O servidor lê o documento por regras e cria um rascunho." voltar={{ href: "/admin/importar/", rotulo: "Importar" }} estreita>
      {!link ? (
        <Cartao className="p-5">
          <form onSubmit={gerar} className="flex flex-col gap-4">
            <fieldset>
              <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas</legend>
              <div className="flex flex-wrap gap-2">
                {turmas.dados?.map((t) => {
                  const marcada = escolhidas.includes(t.nome);
                  return (
                    <label key={t.id} className={`flex cursor-pointer items-center gap-2 rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"}`}>
                      <input type="checkbox" className="sr-only" checked={marcada} onChange={() => setEscolhidas((a) => (marcada ? a.filter((n) => n !== t.nome) : [...a, t.nome]))} />
                      {t.nome}
                    </label>
                  );
                })}
              </div>
            </fieldset>
            <Campo rotulo="Título" dica="Vazio usa o título do documento.">{(id) => <input id={id} maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} className="campo" />}</Campo>
            <div className="grid gap-4 sm:grid-cols-3">
              <Campo rotulo="Abre em" dica="Horário de Brasília">{(id) => <input id={id} type="datetime-local" value={abre} onChange={(e) => setAbre(e.target.value)} className="campo" />}</Campo>
              <Campo rotulo="Fecha em">{(id) => <input id={id} type="datetime-local" value={fecha} onChange={(e) => setFecha(e.target.value)} className="campo" />}</Campo>
              <Campo rotulo="Tempo de prova (min)">{(id) => <input id={id} type="number" min={1} max={1440} value={duracao} onChange={(e) => setDuracao(e.target.value)} className="campo" />}</Campo>
            </div>
            <Campo rotulo="Pasta do Vimeo com as resoluções" dica="Opcional. A questão 7 recebe o vídeo Q07.">{(id) => <input id={id} value={pasta} onChange={(e) => setPasta(e.target.value)} placeholder="Nome ou id da pasta" className="campo" />}</Campo>
            {erro && <Aviso tom="erro">{erro}</Aviso>}
            <div>
              <Botao type="submit" variante="primario" disabled={ocupado}>{ocupado ? "Conferindo…" : "Continuar"}</Botao>
            </div>
          </form>
        </Cartao>
      ) : (
        <Cartao className="flex flex-col gap-4 p-5">
          <TituloDeSecao>Envie o arquivo</TituloDeSecao>
          <EscolherDocx enviando={ocupado} aoEnviar={(arquivo) => void enviar(arquivo)} rotulo="Enviar e ler" />
          {erro && <Aviso tom="erro">{erro}</Aviso>}
          <p className="text-[13px] text-suave">
            Outra pessoa da equipe vai enviar? Passe o link de uso único (vale até {link.expira_em}):{" "}
            <code className="select-all break-all rounded-campo bg-canvas px-1.5 py-0.5">{link.link}</code>. Depois abra a revisão em{" "}
            <Link href={`/admin/importar/docx/?importacao=${link.importacao_id}`} className="font-semibold text-acento hover:underline">importação #{link.importacao_id}</Link>.
          </p>
        </Cartao>
      )}
    </Pagina>
  );
}

function Revisao({ id }: { id: number }) {
  const revisao = useDados(() => api.revisarImportacao(id), [id]);
  return (
    <Pagina
      titulo={revisao.dados ? revisao.dados.titulo : `Importação #${id}`}
      legenda={revisao.dados && `${revisao.dados.arquivo ?? "documento"} · ${revisao.dados.total_questoes} questões no rascunho`}
      voltar={{ href: "/admin/importar/docx/", rotulo: "Novo .docx" }}
      acoes={revisao.dados && <BotaoLink variante="primario" href={`/admin/rascunhos/revisar/?id=${revisao.dados.rascunho_id}`}>Revisar e publicar</BotaoLink>}
    >
      <Estado {...revisao} linhas={4}>
        {(r) => <ConteudoDaRevisao revisao={r} id={id} aoCompletar={() => void revisao.recarregar()} />}
      </Estado>
    </Pagina>
  );
}

function ConteudoDaRevisao({ revisao: r, id, aoCompletar }: { revisao: RevisaoDocx; id: number; aoCompletar: () => void }) {
  return (
    <div className="flex flex-col gap-4">
      {r.avisos_gerais.length > 0 && (
        <Aviso tom="atencao" titulo="Avisos da leitura">
          <ul className="list-disc pl-5">{r.avisos_gerais.map((a) => <li key={a}>{a}</li>)}</ul>
        </Aviso>
      )}
      {r.pendencias_para_publicar.length > 0 && (
        <Aviso tom="atencao" titulo="Antes de publicar">
          <ul className="list-disc pl-5">{r.pendencias_para_publicar.map((p) => <li key={p}>{p}</li>)}</ul>
        </Aviso>
      )}
      {r.incompletas.map((q) => (
        <Completar key={q.numero} importacao={id} incompleta={q} aoCompletar={aoCompletar} />
      ))}
      <Cartao className="flex flex-col gap-3 p-5">
        <TituloDeSecao>Questões lidas</TituloDeSecao>
        <ol className="flex flex-col gap-3">
          {r.questoes.map((q) => (
            <li key={q.questao_id} className="rounded-cartao border border-borda p-4">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className="font-semibold tabular-nums">{q.ordem}.</span>
                {q.numero_no_documento !== null && q.numero_no_documento !== q.ordem && <Etiqueta>nº {q.numero_no_documento} no documento</Etiqueta>}
                <Etiqueta tom="sucesso">Gabarito {q.gabarito}</Etiqueta>
                {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="ml-auto text-sm font-semibold text-acento hover:underline">Editar #{q.questao_id}</Link>
              </div>
              <TextoFormatado texto={q.enunciado} compacto />
              <ul className="mt-2 flex flex-col gap-1">
                {LETRAS.map((l) => (
                  <li key={l} className={`flex gap-2 rounded-md px-2 py-1 text-[15px] ${q.gabarito === l ? "bg-sucesso-fundo" : ""}`}>
                    <span className="font-semibold">{l})</span>
                    <TextoFormatado texto={q.alternativas[l] ?? ""} compacto className="min-w-0 flex-1" />
                  </li>
                ))}
              </ul>
              {q.avisos.length > 0 && <p className="mt-2 text-[13px] text-atencao">{q.avisos.join(" · ")}</p>}
            </li>
          ))}
        </ol>
      </Cartao>
    </div>
  );
}

function Completar({ importacao, incompleta, aoCompletar }: { importacao: number; incompleta: RevisaoDocx["incompletas"][number]; aoCompletar: () => void }) {
  const [enunciado, setEnunciado] = useState("");
  const [alternativas, setAlternativas] = useState("");
  const [gabarito, setGabarito] = useState("");
  const [resolucao, setResolucao] = useState("");
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setOcupado(true);
    try {
      await api.completarQuestao(importacao, { numero: incompleta.numero, enunciado, alternativas, gabarito, resolucao: resolucao || undefined });
      aoCompletar();
    } catch (ex) {
      setErro((ex as Error).message);
      setOcupado(false);
    }
  }

  return (
    <Cartao className="flex flex-col gap-4 border-atencao-borda p-5">
      <div>
        <TituloDeSecao>Questão {incompleta.numero}: não fechou</TituloDeSecao>
        <p className="text-[15px] text-atencao">{incompleta.avisos.join(" · ")}</p>
      </div>
      <p className="text-[15px] text-suave">Diga quais blocos do documento formam cada parte. O texto sai do documento, sem redigitar.</p>
      <ol className="max-h-72 overflow-y-auto rounded-cartao border border-borda bg-canvas text-[15px]">
        {incompleta.blocos.map((b) => (
          <li key={b.indice} className="flex gap-3 border-b border-borda px-3 py-1.5 last:border-b-0">
            <span className="w-8 shrink-0 text-right font-mono text-[13px] text-suave">{b.indice}</span>
            <span className="min-w-0 flex-1 break-words">{b.texto || <em className="text-apagado">(vazio)</em>}</span>
          </li>
        ))}
      </ol>
      <form onSubmit={enviar} className="grid gap-3 sm:grid-cols-2">
        <Campo rotulo="Enunciado (blocos)">{(id) => <input id={id} required value={enunciado} onChange={(e) => setEnunciado(e.target.value)} placeholder="12-18" className="campo" />}</Campo>
        <Campo rotulo="Alternativas (5 blocos)">{(id) => <input id={id} required value={alternativas} onChange={(e) => setAlternativas(e.target.value)} placeholder="19-23" className="campo" />}</Campo>
        <Campo rotulo="Gabarito">
          {(id) => (
            <select id={id} required value={gabarito} onChange={(e) => setGabarito(e.target.value)} className="campo">
              <option value="">Escolha</option>
              {LETRAS.map((l) => <option key={l} value={l}>{l}</option>)}
            </select>
          )}
        </Campo>
        <Campo rotulo="Resolução (blocos, opcional)">{(id) => <input id={id} value={resolucao} onChange={(e) => setResolucao(e.target.value)} placeholder="24-30" className="campo" />}</Campo>
        {erro && <Aviso tom="erro" className="sm:col-span-2">{erro}</Aviso>}
        <div className="sm:col-span-2">
          <Botao type="submit" variante="secundario" disabled={ocupado}>{ocupado ? "Montando…" : "Montar questão"}</Botao>
        </div>
      </form>
    </Cartao>
  );
}
