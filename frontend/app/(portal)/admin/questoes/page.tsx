"use client";

import { useEffect, useState } from "react";
import { ComoFunciona, Passos } from "@/components/ComoFunciona";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Carregando, Etiqueta, LinkNoTexto, Pagina, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Questao } from "@/lib/api";
import { plural } from "@/lib/formato";
import { DIFICULDADE } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

const POR_PAGINA = 20;

const PEDIDO = "Quero importar prints de questões.";

/**
 * Print virar questão é trabalho do Claude: ele lê a imagem, escreve a questão e recorta a figura.
 * O portal só ensina o caminho.
 */
function QuestoesPorPrints() {
  const [copiado, setCopiado] = useState(false);
  return (
    <ComoFunciona
      rotulo="Questões a partir de prints"
      titulo="Questões a partir de prints"
      legenda="Para questão que só existe em imagem: print de prova, de PDF ou de site. Quem faz é o Claude, conversando com você."
    >
      <Passos>
        <li>
          Se ainda não conectou o Claude ao portal, faça isso em <LinkNoTexto href="/admin/claude/">Conectar ao Claude</LinkNoTexto>.
        </li>
        <li>
          No chat do Claude, peça:
          <span className="mt-1 flex flex-wrap items-center gap-2">
            <span className="select-all rounded-campo border border-borda bg-canvas px-2 py-1">{PEDIDO}</span>
            <Botao tamanho="pequeno" onClick={async () => { await navigator.clipboard?.writeText(PEDIDO); setCopiado(true); }}>{copiado ? "Copiado" : "Copiar"}</Botao>
          </span>
        </li>
        <li>Ele responde com um link de envio, que vale 30 minutos e serve uma vez.</li>
        <li>Abra o link, cole os prints com Ctrl+V ou escolha as imagens, e envie.</li>
        <li>Volte ao chat e avise que enviou. Ele lê os prints, escreve as questões e recorta as figuras. Se faltar alguma informação no print, ele pergunta.</li>
        <li>As questões aparecem aqui, no Banco de questões, em cima da lista e em rascunho. Abra uma por uma, confira e publique. O Claude também manda o link de cada uma.</li>
      </Passos>
      <p className="text-suave">Uma figura saiu cortada ou com texto junto? Diga a ele qual, e ele recorta de novo.</p>
      <p className="text-suave">Se a figura já é um arquivo de imagem, não precisa disso: escreva a questão em “Nova questão” e insira a imagem direto no texto.</p>
    </ComoFunciona>
  );
}

export default function BancoDeQuestoes() {
  const assuntos = useDados(() => api.assuntos());
  const [busca, setBusca] = useState("");
  const [buscaAplicada, setBuscaAplicada] = useState("");
  const [assunto, setAssunto] = useState("");
  const [dificuldade, setDificuldade] = useState("");
  const [status, setStatus] = useState("");
  const [questoes, setQuestoes] = useState<Questao[]>([]);
  const [temMais, setTemMais] = useState(false);
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState("");
  // O que a tela de "Importar .docx" acabou de trazer: ela volta para cá com a contagem no endereço.
  const [importado, setImportado] = useState<{ total: number; publicadas: number; pendentes: number } | null>(null);

  useEffect(() => {
    const p = new URLSearchParams(window.location.search);
    const total = Number(p.get("importadas"));
    if (!total) return;
    setImportado({ total, publicadas: Number(p.get("publicadas")) || 0, pendentes: Number(p.get("pendentes")) || 0 });
    // O aviso é de agora: recarregar a página não deve repeti-lo.
    window.history.replaceState(null, "", window.location.pathname);
  }, []);

  // A busca por texto espera a pessoa parar de digitar.
  useEffect(() => {
    const espera = setTimeout(() => setBuscaAplicada(busca.trim()), 350);
    return () => clearTimeout(espera);
  }, [busca]);

  async function carregar(offset: number) {
    setCarregando(true);
    setErro("");
    try {
      // A última que entrou vem em cima: é onde está o que o Claude acabou de escrever para conferir.
      const pagina = await api.questoes({ busca: buscaAplicada, assunto, dificuldade, status, ordem: "recentes", limite: POR_PAGINA, offset });
      setQuestoes((atuais) => (offset ? [...atuais, ...pagina] : pagina));
      setTemMais(pagina.length === POR_PAGINA);
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setCarregando(false);
    }
  }

  useEffect(() => {
    void carregar(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [buscaAplicada, assunto, dificuldade, status]);

  // --- publicar várias de uma vez ---
  // Quem importou vinte questões não precisa abrir vinte: marca as que conferiu e publica juntas.
  const [marcadas, setMarcadas] = useState<Set<number>>(new Set());
  const [publicando, setPublicando] = useState(false);
  const [resultado, setResultado] = useState<{ publicadas: number; recusadas: { questao_id: number; motivo: string }[] } | null>(null);
  const [dialogo, confirmar] = useConfirmar();
  const emRascunho = questoes.filter((q) => q.status !== "PUBLICADO").map((q) => q.questao_id);
  const escolhidas = emRascunho.filter((id) => marcadas.has(id));
  const todasMarcadas = emRascunho.length > 0 && escolhidas.length === emRascunho.length;

  const alternar = (id: number) =>
    setMarcadas((atuais) => {
      const novas = new Set(atuais);
      if (novas.has(id)) novas.delete(id);
      else novas.add(id);
      return novas;
    });

  async function publicarMarcadas() {
    const sim = await confirmar({
      titulo: `Publicar ${plural(escolhidas.length, "questão", "questões")}?`,
      texto: "Elas saem do rascunho e passam a poder entrar em simulado e em aula. Dá para corrigir uma questão depois de publicada.",
      confirmar: "Publicar",
    });
    if (!sim) return;
    setPublicando(true);
    setErro("");
    setResultado(null);
    try {
      const r = await api.publicarQuestoes(escolhidas);
      setResultado({ publicadas: r.publicadas.length, recusadas: r.recusadas });
      setImportado(null);
      // As que não saíram continuam marcadas: é nelas que falta mexer.
      setMarcadas(new Set(r.recusadas.map((x) => x.questao_id)));
      await carregar(0);
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setPublicando(false);
    }
  }

  return (
    <Pagina
      titulo="Banco de questões"
      legenda="O acervo de questões, da mais recente para a mais antiga. A que está em rascunho ainda não aparece para ninguém: abra, confira e publique."
      acoes={
        <>
          <QuestoesPorPrints />
          <BotaoLink href="/admin/questoes/importar/">Importar .docx</BotaoLink>
          <BotaoLink variante="primario" href="/admin/questoes/editar/">Nova questão</BotaoLink>
        </>
      }
    >
      {importado && (
        <Aviso tom={importado.pendentes ? "atencao" : "sucesso"} titulo={`${plural(importado.total, "questão importada", "questões importadas")} do .docx`}>
          {importado.publicadas === importado.total
            ? "Já estão publicadas, em cima da lista: podem entrar em simulado e em aula."
            : importado.publicadas === 0
              ? "Estão em rascunho, em cima da lista. Abra cada uma para conferir e publicar, ou marque várias e publique de uma vez."
              : `${plural(importado.publicadas, "foi publicada", "foram publicadas")}; ${plural(importado.total - importado.publicadas, "ficou em rascunho", "ficaram em rascunho")}, em cima da lista.`}
          {importado.pendentes > 0 && ` ${plural(importado.pendentes, "está com imagem pendente", "estão com imagem pendente")}: insira a figura no texto antes de publicar.`}
        </Aviso>
      )}

      <Cartao className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr]">
        <Campo rotulo="Buscar no enunciado">
          {(id) => <input id={id} type="search" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Ex.: pureza, rendimento" className="campo" />}
        </Campo>
        <Campo rotulo="Assunto">
          {(id) => (
            <select id={id} value={assunto} onChange={(e) => setAssunto(e.target.value)} className="campo">
              <option value="">Todos</option>
              {assuntos.dados?.map((a) => (
                <option key={a.id} value={a.id}>{a.nome}</option>
              ))}
            </select>
          )}
        </Campo>
        <Campo rotulo="Dificuldade">
          {(id) => (
            <select id={id} value={dificuldade} onChange={(e) => setDificuldade(e.target.value)} className="campo">
              <option value="">Todas</option>
              {Object.entries(DIFICULDADE).map(([valor, rotulo]) => (
                <option key={valor} value={valor}>{rotulo}</option>
              ))}
            </select>
          )}
        </Campo>
        <Campo rotulo="Situação">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)} className="campo">
              <option value="">Todas</option>
              <option value="PUBLICADO">Publicadas</option>
              <option value="RASCUNHO">Em rascunho</option>
            </select>
          )}
        </Campo>
      </Cartao>

      {dialogo}
      {resultado && (
        <Aviso tom={resultado.recusadas.length ? "atencao" : "sucesso"} titulo={resultado.publicadas ? plural(resultado.publicadas, "questão publicada", "questões publicadas") : "Nenhuma questão foi publicada"}>
          {resultado.recusadas.length > 0 && (
            <>
              {plural(resultado.recusadas.length, "não saiu", "não saíram")} e {resultado.recusadas.length === 1 ? "continua marcada" : "continuam marcadas"} na lista:
              <ul className="mt-1 list-disc pl-5">
                {resultado.recusadas.slice(0, 4).map((x) => (
                  <li key={x.questao_id}>{x.motivo}</li>
                ))}
                {resultado.recusadas.length > 4 && <li>e mais {resultado.recusadas.length - 4}.</li>}
              </ul>
            </>
          )}
        </Aviso>
      )}
      {emRascunho.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-cartao border border-borda bg-papel px-4 py-2.5">
          <label className="flex cursor-pointer items-center gap-2 text-[15px]">
            <input type="checkbox" className="size-4 accent-acento" checked={todasMarcadas} onChange={() => setMarcadas(todasMarcadas ? new Set() : new Set(emRascunho))} />
            Marcar {emRascunho.length === 1 ? "a questão em rascunho" : `as ${emRascunho.length} em rascunho`} desta lista
          </label>
          <Botao variante="primario" tamanho="pequeno" ocupado={publicando} disabled={!escolhidas.length || publicando} onClick={publicarMarcadas}>
            {publicando ? "Publicando…" : escolhidas.length ? `Publicar ${plural(escolhidas.length, "marcada", "marcadas")}` : "Publicar marcadas"}
          </Botao>
        </div>
      )}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {carregando && questoes.length === 0 ? (
        <Carregando linhas={4} />
      ) : questoes.length === 0 ? (
        <Vazio titulo="Nenhuma questão encontrada">Mude os filtros, cadastre uma nova questão ou importe várias de um .docx.</Vazio>
      ) : (
        <ul className="flex flex-col gap-3">
          {questoes.map((q) => (
            <Cartao key={q.questao_id} como="li" className="flex flex-col gap-3 p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  {q.status !== "PUBLICADO" && (
                    <input type="checkbox" className="size-4 accent-acento" aria-label={`Marcar a questão ${q.questao_id} para publicar`} checked={marcadas.has(q.questao_id)} onChange={() => alternar(q.questao_id)} />
                  )}
                  <span className="font-mono text-sm text-suave">#{q.questao_id}</span>
                  {q.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicada</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}
                  <Etiqueta>{DIFICULDADE[q.dificuldade] ?? q.dificuldade}</Etiqueta>
                  {q.gabarito && <Etiqueta tom="info">Gabarito {q.gabarito}</Etiqueta>}
                  {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                  {q.video_resolucao_id && <Etiqueta>Com vídeo</Etiqueta>}
                  {!!q.aulas?.length && <span title={q.aulas.join("; ")}><Etiqueta tom="info">Em aula</Etiqueta></span>}
                </div>
                <BotaoLink tamanho="mini" variante={q.status === "PUBLICADO" ? "neutro" : "secundario"} href={`/admin/questoes/editar/?id=${q.questao_id}`}>{q.status === "PUBLICADO" ? "Abrir" : "Conferir e publicar"}</BotaoLink>
              </div>
              <div className="relative max-h-40 overflow-hidden">
                <TextoFormatado texto={q.enunciado} compacto />
                <div className="pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-papel" aria-hidden="true" />
              </div>
              <p className="text-[13px] text-suave">{q.classificacao.map((c) => (c.subassunto ? `${c.assunto} › ${c.subassunto}` : c.assunto)).join(", ") || "Sem classificação"}</p>
            </Cartao>
          ))}
        </ul>
      )}
      {temMais && (
        <div className="flex justify-center">
          <Botao ocupado={carregando} onClick={() => carregar(questoes.length)}>{carregando ? "Carregando…" : "Carregar mais"}</Botao>
        </div>
      )}
    </Pagina>
  );
}
