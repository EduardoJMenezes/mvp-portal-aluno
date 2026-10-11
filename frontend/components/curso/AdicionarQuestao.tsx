"use client";

// Questões entrando num sub-módulo. Quem cadastra está com a apostila aberta ao lado e vai de
// questão em questão: cola um trecho do enunciado, reconhece a questão pelo começo dela, confere
// se precisar e adiciona; a que não existe, escreve. O painel fica aberto entre uma e outra, e o
// nome da próxima linha ("Q05") anda sozinho.

import { Check, Image as Figura, Play, Plus, Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { OffCanvas, useSaida } from "@/components/Camadas";
import { AlternativasDaQuestao, EsperandoAResolucao, ResolucaoDaQuestao } from "@/components/PreviaDaQuestao";
import { Aviso, Botao, BotaoLink, Carregamento, Esqueleto, Revelar } from "@/components/ui";
import { api, type Assunto, type ItemCurso, type Modulo, type Questao, type QuestaoDetalhada, type SubModulo } from "@/lib/api";
import { plural } from "@/lib/formato";
import { DIFICULDADE } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";
import { Azulejo, type Executar } from "./comum";

const POR_PAGINA = 20;
/** Quanto a busca espera depois da última tecla. */
const ESPERA_DA_BUSCA_MS = 350;

/** O nome que continua a numeração das questões do sub-módulo: depois de "Q04" vem "Q05". */
export function proximoNome(nome: string | undefined): string {
  const partes = nome && /^(.*?)(\d+)$/.exec(nome.trim());
  if (!partes) return "";
  return partes[1] + String(Number(partes[2]) + 1).padStart(partes[2].length, "0");
}
const ultimaQuestao = (itens: ItemCurso[]) => itens.filter((i) => i.questao).at(-1)?.nome;

const etiqueta = (q: Questao) => {
  const c = q.classificacao[0];
  return c ? (c.subassunto ? `${c.assunto} › ${c.subassunto}` : c.assunto) : null;
};

function EsqueletoDeQuestoes() {
  return (
    <Carregamento rotulo="Buscando questões" className="divide-y divide-borda/70">
      {["w-11/12", "w-4/5", "w-full", "w-3/4", "w-5/6"].map((largura, i) => (
        <div key={i} className="flex items-start gap-4 px-5 py-3.5">
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <Esqueleto className={`h-4 ${largura}`} />
            <Esqueleto className="h-4 w-2/5" />
            <Esqueleto className="mt-1 h-3 w-1/3" />
          </div>
          <Esqueleto className="h-8 w-24 shrink-0 rounded-campo" />
        </div>
      ))}
    </Carregamento>
  );
}

/** O resto da questão, para conferir com a apostila: as alternativas, que a lista já trouxe, e, quando chega, a resolução. */
function QuestaoAberta({ questao, detalhe, erro }: { questao: Questao; detalhe?: QuestaoDetalhada; erro?: string }) {
  return (
    <div className="surge flex flex-col gap-3 px-5 pb-4">
      <AlternativasDaQuestao alternativas={questao.alternativas} gabarito={questao.gabarito} comentarios={detalhe?.comentarios} />
      {erro ? <Aviso tom="erro">{erro}</Aviso> : !detalhe ? <EsperandoAResolucao /> : <ResolucaoDaQuestao detalhe={detalhe} />}
      {!!questao.aulas?.length && <p className="text-[13px] text-suave">Já está em: {questao.aulas.join("; ")}.</p>}
      <div>
        <BotaoLink tamanho="pequeno" outraAba href={`/admin/questoes/editar/?id=${questao.questao_id}`}>Abrir no editor</BotaoLink>
      </div>
    </div>
  );
}

export function AdicionarQuestao({ modulo, sub, assuntos, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const jaNaAula = useMemo(() => new Set(sub.itens.map((i) => i.questao?.questao_id).filter((id): id is number => id !== undefined)), [sub.itens]);
  const quantas = jaNaAula.size;

  // --- a busca ---
  const campo = useRef<HTMLInputElement>(null);
  const [busca, setBusca] = useState("");
  const [buscaAplicada, setBuscaAplicada] = useState("");
  const [assunto, setAssunto] = useState("");
  const [soNovas, setSoNovas] = useState(false);
  const [questoes, setQuestoes] = useState<Questao[] | null>(null);
  const [temMais, setTemMais] = useState(false);
  const [carregandoMais, setCarregandoMais] = useState(false);
  const [erro, setErro] = useState("");
  const vez = useRef(0);

  useEffect(() => {
    const espera = setTimeout(() => setBuscaAplicada(busca.trim()), ESPERA_DA_BUSCA_MS);
    return () => clearTimeout(espera);
  }, [busca]);

  async function carregar(offset: number) {
    const minha = ++vez.current;
    setErro("");
    if (offset) setCarregandoMais(true);
    else setQuestoes(null);
    try {
      const pagina = await api.questoes({ busca: buscaAplicada, assunto, status: "PUBLICADO", limite: POR_PAGINA, offset });
      if (minha !== vez.current) return;
      setQuestoes((atuais) => (offset && atuais ? [...atuais, ...pagina] : pagina));
      setTemMais(pagina.length === POR_PAGINA);
    } catch (ex) {
      if (minha !== vez.current) return;
      setErro((ex as Error).message);
      setQuestoes((atuais) => atuais ?? []);
    } finally {
      if (minha === vez.current) setCarregandoMais(false);
    }
  }
  useEffect(() => {
    void carregar(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [buscaAplicada, assunto]);

  // --- conferir a questão ---
  const [aberta, setAberta] = useState<number | null>(null);
  const [detalhes, setDetalhes] = useState<Record<number, QuestaoDetalhada>>({});
  const [erroDoDetalhe, setErroDoDetalhe] = useState<Record<number, string>>({});

  const abrir = (id: number) => {
    if (aberta === id) return setAberta(null);
    setAberta(id);
    if (detalhes[id]) return;
    setErroDoDetalhe((atual) => ({ ...atual, [id]: "" }));
    api.questao(id).then(
      (d) => setDetalhes((atual) => ({ ...atual, [id]: d })),
      (ex: Error) => setErroDoDetalhe((atual) => ({ ...atual, [id]: ex.message })),
    );
  };

  // --- adicionar ---
  // O nome da próxima linha segue a numeração do sub-módulo até o professor escrever outro.
  const sugerido = proximoNome(ultimaQuestao(sub.itens));
  const [nomeEscrito, setNomeEscrito] = useState<string | null>(null);
  const nome = (nomeEscrito ?? sugerido).trim();
  const [adicionando, setAdicionando] = useState<number | null>(null);
  const [entraram, setEntraram] = useState<Record<number, string>>({});

  async function adicionar(q: Questao) {
    setAdicionando(q.questao_id);
    let linha = nome;
    const ok = await executar(
      async () => {
        linha = (await api.questaoNoSubmodulo(sub.id, { questao_id: q.questao_id, nome: nome || undefined })).nome;
      },
      () => `Questão #${q.questao_id} entrou em ${sub.nome} como ${linha}, já publicada.`,
    );
    setAdicionando(null);
    if (!ok) return;
    setEntraram((atual) => ({ ...atual, [q.questao_id]: linha }));
    setNomeEscrito(null);
    // A próxima busca começa por cima da anterior: é só colar o trecho seguinte.
    campo.current?.focus();
    campo.current?.select();
  }

  const destino = encodeURIComponent(`${modulo.nome} › ${sub.nome}`);
  const escrever = `/admin/questoes/editar/?submodulo=${sub.id}&modulo=${modulo.id}&destino=${destino}${nome ? `&nome=${encodeURIComponent(nome)}` : ""}`;
  const visiveis = questoes && (soNovas ? questoes.filter((q) => !jaNaAula.has(q.questao_id)) : questoes);
  const escondidas = questoes && visiveis ? questoes.length - visiveis.length : 0;
  const feitas = Object.keys(entraram).length;

  return (
    <OffCanvas
      {...saida}
      aoFechar={saida.fechar}
      lado="direita"
      tamanho="grande"
      semMargem
      titulo={
        <span className="flex items-center gap-3">
          <Azulejo tipo="questao" />
          Adicionar questão
        </span>
      }
      legenda={`Em ${sub.nome}, de ${modulo.nome}. Entra publicada: o aluno responde uma vez e vê o gabarito e a resolução na hora.`}
      rodape={
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <label className="flex items-center gap-2.5 text-sm text-tinta-2">
            <span>
              <span className="block font-semibold">A próxima entra como</span>
              <span className="block text-[13px] text-suave">{quantas ? `${plural(quantas, "questão", "questões")} em ${sub.nome}${feitas ? `, ${feitas} de agora` : ""}` : "É a primeira questão daqui"}</span>
            </span>
            <input value={nomeEscrito ?? sugerido} onChange={(e) => setNomeEscrito(e.target.value)} maxLength={120} placeholder="Questão N" aria-label="Nome da próxima linha" className="campo w-32" />
          </label>
          <Botao onClick={saida.fechar}>Fechar</Botao>
        </div>
      }
    >
      {/* No celular a busca rola com a lista: presa, tomaria um terço da tela. */}
      <div className="top-0 z-10 flex flex-col gap-2.5 border-b border-borda bg-papel px-5 py-3 sm:sticky">
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="busca-de-questao" className="sr-only">Buscar a questão pelo enunciado ou pelo número</label>
          <div data-foco-inicial className="relative min-w-56 flex-1">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-apagado" />
            <input id="busca-de-questao" ref={campo} type="search" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Cole um trecho do enunciado, ou o número (#46)" className="campo pl-9" />
          </div>
          <BotaoLink variante="primario" href={escrever}>
            <Plus aria-hidden="true" className="size-4" />
            Escrever uma nova
          </BotaoLink>
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <label htmlFor="assunto-da-busca" className="sr-only">Assunto</label>
          <select id="assunto-da-busca" value={assunto} onChange={(e) => setAssunto(e.target.value)} className="campo w-auto max-w-56 py-1.5 text-sm">
            <option value="">Todos os assuntos</option>
            {assuntos.map((a) => (
              <option key={a.id} value={a.id}>{a.nome}</option>
            ))}
          </select>
          <label className="flex cursor-pointer items-center gap-2 text-sm text-tinta-2">
            <input type="checkbox" checked={soNovas} onChange={(e) => setSoNovas(e.target.checked)} className="size-4 accent-acento" />
            Esconder as que já estão aqui
          </label>
        </div>
      </div>

      {erro && (
        <div className="p-5">
          <Aviso tom="erro">{erro}</Aviso>
        </div>
      )}
      {!visiveis ? (
        <EsqueletoDeQuestoes />
      ) : visiveis.length === 0 && !erro ? (
        <div className="flex flex-col items-start gap-3 px-5 py-8">
          <div>
            <p className="font-semibold text-tinta">{escondidas ? "As que apareceram já estão aqui" : buscaAplicada || assunto ? "Nenhuma questão publicada com isso" : "O banco ainda não tem questão publicada"}</p>
            <p className="mt-0.5 text-[15px] text-suave">
              {escondidas ? "Desmarque “Esconder as que já estão aqui” para vê-las." : buscaAplicada ? "A busca olha o texto do enunciado como ele foi cadastrado. Tente um trecho menor, sem fórmula, ou escreva a questão agora." : "Escreva a questão agora: ela entra no banco e nesta aula de uma vez."}
            </p>
          </div>
          {!escondidas && <BotaoLink variante="secundario" href={escrever}>Escrever esta questão{nome ? ` (${nome})` : ""}</BotaoLink>}
        </div>
      ) : (
        <div key={`${buscaAplicada}:${assunto}`} className="surge">
          <p className="border-b border-borda bg-canvas/70 px-5 py-2 text-[13px] font-semibold text-tinta-2">
            {temMais ? `As primeiras ${visiveis.length} ${buscaAplicada || assunto ? "que casam" : "do banco"}` : `${plural(visiveis.length, "questão", "questões")}${buscaAplicada || assunto ? "" : " no banco"}`}
            {escondidas > 0 && <span className="font-normal text-suave">, fora {escondidas} que já {escondidas === 1 ? "está" : "estão"} aqui</span>}
          </p>
          <ul className="divide-y divide-borda/70">
            {visiveis.map((q) => {
              const esta = jaNaAula.has(q.questao_id);
              const expandida = aberta === q.questao_id;
              const assuntoDela = etiqueta(q);
              const comFigura = q.enunciado.includes("](figura:");
              return (
                <li key={q.questao_id} className={expandida ? "bg-canvas/40" : "transition-colors hover:bg-canvas/60"}>
                  <div className="flex items-start gap-4 px-5 py-3">
                    <div className="min-w-0 flex-1">
                      {/* Fechada, só o começo do enunciado, sem a figura: é por ele que se reconhece a questão da apostila. */}
                      <div onClick={() => abrir(q.questao_id)} className={`cursor-pointer text-[15px] ${expandida ? "" : "line-clamp-2 max-h-[3.5em] overflow-hidden [&_.figura-pendente]:hidden [&_img]:hidden [&_table]:hidden"}`}>
                        <TextoFormatado texto={q.enunciado} compacto={!expandida} />
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-suave">
                        <span className="font-semibold tabular-nums text-tinta-2">#{q.questao_id}</span>
                        <span>{[assuntoDela ?? "Sem assunto", DIFICULDADE[q.dificuldade] ?? q.dificuldade, q.gabarito && `gabarito ${q.gabarito}`].filter(Boolean).join(", ")}</span>
                        {comFigura && (
                          <span className="inline-flex items-center gap-1" title="O enunciado tem figura">
                            <Figura aria-hidden="true" className="size-3.5" />
                            figura
                          </span>
                        )}
                        {q.video_resolucao_id && (
                          <span className="inline-flex items-center gap-1" title="Tem vídeo de resolução">
                            <Play aria-hidden="true" className="size-3" fill="currentColor" />
                            resolução em vídeo
                          </span>
                        )}
                        <Revelar aberto={expandida} aoAlternar={() => abrir(q.questao_id)} className="text-[13px]">
                          {expandida ? "Recolher" : "Ver a questão"}
                        </Revelar>
                      </div>
                    </div>
                    {esta ? (
                      <span className="inline-flex shrink-0 items-center gap-1.5 py-1.5 text-sm font-semibold text-sucesso">
                        <Check aria-hidden="true" className="size-4" strokeWidth={2.5} />
                        {entraram[q.questao_id] ? `Entrou como ${entraram[q.questao_id]}` : "Já está aqui"}
                      </span>
                    ) : (
                      <Botao tamanho="pequeno" variante="secundario" disabled={adicionando !== null} ocupado={adicionando === q.questao_id} onClick={() => adicionar(q)} className="shrink-0">
                        {adicionando === q.questao_id ? "Adicionando…" : "Adicionar"}
                      </Botao>
                    )}
                  </div>
                  {expandida && <QuestaoAberta questao={q} detalhe={detalhes[q.questao_id]} erro={erroDoDetalhe[q.questao_id]} />}
                </li>
              );
            })}
          </ul>
          {temMais && (
            <div className="flex justify-center border-t border-borda/70 p-4">
              <Botao tamanho="pequeno" ocupado={carregandoMais} onClick={() => carregar(questoes?.length ?? 0)}>{carregandoMais ? "Carregando…" : "Carregar mais"}</Botao>
            </div>
          )}
        </div>
      )}
    </OffCanvas>
  );
}
