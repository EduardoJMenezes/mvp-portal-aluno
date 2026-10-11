"use client";

import { useEffect, useState } from "react";
import { ComoFunciona, Passos } from "@/components/ComoFunciona";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Carregando, Etiqueta, LinkNoTexto, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Questao } from "@/lib/api";
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
        <li>Volte ao chat e avise que enviou. Ele lê os prints, escreve as questões e recorta as figuras. Vai perguntar o gabarito, que quase nunca vem no print.</li>
        <li>
          Confira o resultado em <LinkNoTexto href="/admin/rascunhos/">Rascunhos</LinkNoTexto>. Nada aparece para os alunos antes da sua aprovação.
        </li>
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
      legenda="O acervo de questões: as de simulado e as que viram linha de aula, onde o aluno responde e vê o gabarito na hora."
      acoes={
        <>
          <QuestoesPorPrints />
          <BotaoLink variante="primario" href="/admin/questoes/editar/">Nova questão</BotaoLink>
        </>
      }
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
                  {!!q.aulas?.length && <span title={q.aulas.join("; ")}><Etiqueta tom="info">Em aula</Etiqueta></span>}
                </div>
                <BotaoLink tamanho="mini" href={`/admin/questoes/editar/?id=${q.questao_id}`}>Abrir</BotaoLink>
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
