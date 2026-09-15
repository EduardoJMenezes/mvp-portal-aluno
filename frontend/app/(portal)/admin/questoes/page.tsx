"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Carregando, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Questao } from "@/lib/api";
import { DIFICULDADE } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

const POR_PAGINA = 20;

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

  // A busca por texto espera a pessoa parar de digitar.
  useEffect(() => {
    const espera = setTimeout(() => setBuscaAplicada(busca.trim()), 350);
    return () => clearTimeout(espera);
  }, [busca]);

  async function carregar(offset: number) {
    setCarregando(true);
    setErro("");
    try {
      const pagina = await api.questoes({ busca: buscaAplicada, assunto, dificuldade, status, limite: POR_PAGINA, offset });
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

  return (
    <Pagina
      titulo="Banco de questões"
      legenda="As questões de simulado. As da apostila moram na apostila: aqui entra só o vídeo da resolução delas, no curso."
      acoes={<BotaoLink variante="primario" href="/admin/questoes/editar/">Nova questão</BotaoLink>}
    >
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

      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {carregando && questoes.length === 0 ? (
        <Carregando linhas={4} />
      ) : questoes.length === 0 ? (
        <Vazio titulo="Nenhuma questão encontrada">Mude os filtros ou cadastre uma nova questão.</Vazio>
      ) : (
        <ul className="flex flex-col gap-3">
          {questoes.map((q) => (
            <Cartao key={q.questao_id} como="li" className="flex flex-col gap-3 p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm text-suave">#{q.questao_id}</span>
                  {q.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicada</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}
                  <Etiqueta>{DIFICULDADE[q.dificuldade] ?? q.dificuldade}</Etiqueta>
                  {q.gabarito && <Etiqueta tom="info">Gabarito {q.gabarito}</Etiqueta>}
                  {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                  {q.video_resolucao_id && <Etiqueta>Com vídeo</Etiqueta>}
                </div>
                <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="text-sm font-semibold text-acento hover:underline">Abrir</Link>
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
          <Botao disabled={carregando} onClick={() => void carregar(questoes.length)}>{carregando ? "Carregando…" : "Carregar mais"}</Botao>
        </div>
      )}
    </Pagina>
  );
}
