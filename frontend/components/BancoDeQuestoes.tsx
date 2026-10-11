"use client";

// O banco de questões para quem está escolhendo: quem monta uma prova ou põe questões numa aula.
// A lista já chega aberta, agrupada por assunto, e afina por busca (trecho do enunciado ou o
// número), assunto, sub-assunto e dificuldade. Cada questão mostra só o começo do enunciado;
// "Ver a questão" abre ali mesmo as alternativas, o gabarito e a resolução.

import { Check, Image as Figura, Play, Search } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode, type Ref } from "react";
import { AlternativasDaQuestao, EsperandoAResolucao, ResolucaoDaQuestao } from "@/components/PreviaDaQuestao";
import { Aviso, Botao, BotaoLink, Carregamento, Esqueleto, Etiqueta, Revelar } from "@/components/ui";
import { api, useDados, type Assunto, type Questao, type QuestaoDetalhada } from "@/lib/api";
import { plural } from "@/lib/formato";
import { DIFICULDADE } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

const POR_PAGINA = 20;
/** Quanto a busca espera depois da última tecla. */
const ESPERA_DA_BUSCA_MS = 350;
const SEM_ASSUNTO = "Sem assunto";

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

/** O resto da questão: as alternativas, que a lista já trouxe, e, quando chega, a resolução. */
function QuestaoAberta({ questao, detalhe, erro }: { questao: Questao; detalhe?: QuestaoDetalhada; erro?: string }) {
  return (
    <div className="surge flex flex-col gap-3 px-5 pb-4">
      <AlternativasDaQuestao alternativas={questao.alternativas} gabarito={questao.gabarito} comentarios={detalhe?.comentarios} />
      {erro ? <Aviso tom="erro">{erro}</Aviso> : !detalhe ? <EsperandoAResolucao /> : <ResolucaoDaQuestao detalhe={detalhe} />}
      {!!questao.aulas?.length && <p className="text-[13px] text-suave">Está em aula: {questao.aulas.join("; ")}.</p>}
      <div>
        <BotaoLink tamanho="pequeno" outraAba href={`/admin/questoes/editar/?id=${questao.questao_id}`}>Abrir no editor</BotaoLink>
      </div>
    </div>
  );
}

export function BancoDeQuestoes({
  jaEsta,
  marcaDeJaEsta = () => "Já está aqui",
  aoEscolher,
  acao,
  noVazio,
  esconder = "Esconder as que já estão aqui",
  avisarAula = false,
  assuntos: dados,
  campoRef,
}: {
  /** As questões que já estão no destino: aparecem marcadas, sem o botão. */
  jaEsta: Set<number>;
  marcaDeJaEsta?: (q: Questao) => string;
  /** Devolvendo uma promessa, os botões esperam por ela: uma questão entra de cada vez. */
  aoEscolher: (q: Questao) => unknown;
  /** O que fica ao lado da busca: "Escrever uma nova", por exemplo. */
  acao?: ReactNode;
  /** O que oferecer quando nada casa com a busca. */
  noVazio?: ReactNode;
  esconder?: string;
  /** Na prova, a questão que está em aula merece o aviso: lá o gabarito sai para quem responde. */
  avisarAula?: boolean;
  /** Quem já tem a lista de assuntos passa; senão, ela é lida aqui. */
  assuntos?: Assunto[];
  campoRef?: Ref<HTMLInputElement>;
}) {
  const lidos = useDados(() => (dados ? Promise.resolve(dados) : api.assuntos()), [dados]);
  const assuntos = dados ?? lidos.dados ?? [];

  const [busca, setBusca] = useState("");
  const [buscaAplicada, setBuscaAplicada] = useState("");
  const [assunto, setAssunto] = useState("");
  const [subassunto, setSubassunto] = useState("");
  const [dificuldade, setDificuldade] = useState("");
  const [soNovas, setSoNovas] = useState(false);
  const [questoes, setQuestoes] = useState<Questao[] | null>(null);
  const [temMais, setTemMais] = useState(false);
  const [carregandoMais, setCarregandoMais] = useState(false);
  const [erro, setErro] = useState("");
  const [escolhendo, setEscolhendo] = useState<number | null>(null);
  const vez = useRef(0);
  const doAssunto = assuntos.find((a) => String(a.id) === assunto);

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
      const pagina = await api.questoes({ busca: buscaAplicada, assunto, subassunto, dificuldade, status: "PUBLICADO", ordem: "assunto", limite: POR_PAGINA, offset });
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
  }, [buscaAplicada, assunto, subassunto, dificuldade]);

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

  async function escolher(q: Questao) {
    setEscolhendo(q.questao_id);
    try {
      await aoEscolher(q);
    } finally {
      setEscolhendo(null);
    }
  }

  const filtrando = !!(buscaAplicada || assunto || dificuldade);
  const visiveis = questoes && (soNovas ? questoes.filter((q) => !jaEsta.has(q.questao_id)) : questoes);
  const escondidas = questoes && visiveis ? questoes.length - visiveis.length : 0;
  // Sem assunto escolhido e sem texto na busca, a lista vem por assunto: o nome dele abre cada grupo.
  const emGrupos = !assunto && !buscaAplicada;
  const filtro = "campo w-auto max-w-52 py-1.5 text-sm";

  return (
    <>
      {/* No celular a busca rola com a lista: presa, tomaria metade da tela. */}
      <div className="top-0 z-10 flex flex-col gap-2.5 border-b border-borda bg-papel px-5 py-3 sm:sticky">
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="busca-no-banco" className="sr-only">Buscar a questão pelo enunciado ou pelo número</label>
          <div data-foco-inicial className="relative min-w-56 flex-1">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-apagado" />
            <input
              id="busca-no-banco"
              ref={campoRef}
              type="search"
              value={busca}
              onChange={(e) => setBusca(e.target.value)}
              // O painel pode estar dentro do formulário da prova: Enter aqui não pode enviá-lo.
              onKeyDown={(e) => e.key === "Enter" && e.preventDefault()}
              placeholder="Trecho do enunciado, ou o número (#46)"
              className="campo pl-9"
            />
          </div>
          {acao}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <label htmlFor="assunto-do-banco" className="sr-only">Assunto</label>
          <select id="assunto-do-banco" value={assunto} onChange={(e) => { setAssunto(e.target.value); setSubassunto(""); }} className={filtro}>
            <option value="">Todos os assuntos</option>
            {assuntos.map((a) => (
              <option key={a.id} value={a.id}>{a.nome}</option>
            ))}
          </select>
          {!!doAssunto?.subassuntos.length && (
            <>
              <label htmlFor="subassunto-do-banco" className="sr-only">Sub-assunto</label>
              <select id="subassunto-do-banco" value={subassunto} onChange={(e) => setSubassunto(e.target.value)} className={filtro}>
                <option value="">Todos os sub-assuntos</option>
                {doAssunto.subassuntos.map((s) => (
                  <option key={s.id} value={s.id}>{s.nome}</option>
                ))}
              </select>
            </>
          )}
          <label htmlFor="dificuldade-do-banco" className="sr-only">Dificuldade</label>
          <select id="dificuldade-do-banco" value={dificuldade} onChange={(e) => setDificuldade(e.target.value)} className={filtro}>
            <option value="">Qualquer dificuldade</option>
            {Object.entries(DIFICULDADE).map(([valor, rotulo]) => (
              <option key={valor} value={valor}>{rotulo}</option>
            ))}
          </select>
          <label className="flex cursor-pointer items-center gap-2 text-sm text-tinta-2">
            <input type="checkbox" checked={soNovas} onChange={(e) => setSoNovas(e.target.checked)} className="size-4 accent-acento" />
            {esconder}
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
            <p className="font-semibold text-tinta">{escondidas ? "As que apareceram já foram escolhidas" : filtrando ? "Nenhuma questão publicada com isso" : "O banco ainda não tem questão publicada"}</p>
            <p className="mt-0.5 text-[15px] text-suave">
              {escondidas
                ? `Desmarque “${esconder}” para vê-las.`
                : buscaAplicada
                  ? "A busca olha o texto do enunciado como ele foi cadastrado. Tente um trecho menor, sem fórmula."
                  : filtrando
                    ? "Mude o assunto ou a dificuldade."
                    : "As questões entram no banco quando são publicadas."}
            </p>
          </div>
          {!escondidas && noVazio}
        </div>
      ) : (
        <div key={`${buscaAplicada}:${assunto}:${subassunto}:${dificuldade}`} className="surge">
          <p className="border-b border-borda bg-canvas/70 px-5 py-2 text-[13px] font-semibold text-tinta-2">
            {temMais ? `As primeiras ${visiveis.length} ${filtrando ? "que casam" : "do banco"}` : `${plural(visiveis.length, "questão", "questões")}${filtrando ? "" : " no banco"}`}
            {emGrupos && <span className="font-normal text-suave">, por assunto</span>}
            {escondidas > 0 && <span className="font-normal text-suave">, fora {escondidas} já {escondidas === 1 ? "escolhida" : "escolhidas"}</span>}
          </p>
          <ul className="divide-y divide-borda/70">
            {visiveis.map((q, i) => {
              const esta = jaEsta.has(q.questao_id);
              const expandida = aberta === q.questao_id;
              const c = q.classificacao[0];
              const grupo = c?.assunto ?? SEM_ASSUNTO;
              const abreGrupo = emGrupos && grupo !== (visiveis[i - 1]?.classificacao[0]?.assunto ?? (i ? SEM_ASSUNTO : null));
              const comFigura = q.enunciado.includes("](figura:");
              // Dentro do grupo do assunto, basta o sub-assunto; fora dele, o caminho inteiro.
              const etiqueta = emGrupos ? (c?.subassunto ?? null) : c ? (c.subassunto ? `${c.assunto} › ${c.subassunto}` : c.assunto) : SEM_ASSUNTO;
              return (
                <li key={q.questao_id} className={expandida ? "bg-canvas/40" : "transition-colors hover:bg-canvas/60"}>
                  {abreGrupo && <h3 className="border-b border-borda/70 bg-lilas/50 px-5 py-1.5 text-[13px] font-semibold text-acento-forte">{grupo}</h3>}
                  <div className="flex items-start gap-4 px-5 py-3">
                    <div className="min-w-0 flex-1">
                      {/* Fechada, só o começo do enunciado, sem a figura: é por ele que se reconhece a questão. */}
                      <div onClick={() => abrir(q.questao_id)} className={`cursor-pointer text-[15px] ${expandida ? "" : "line-clamp-2 max-h-[3.5em] overflow-hidden [&_.figura-pendente]:hidden [&_img]:hidden [&_table]:hidden"}`}>
                        <TextoFormatado texto={q.enunciado} compacto={!expandida} />
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-suave">
                        <span className="font-semibold tabular-nums text-tinta-2">#{q.questao_id}</span>
                        <span>{[etiqueta, DIFICULDADE[q.dificuldade] ?? q.dificuldade, q.gabarito && `gabarito ${q.gabarito}`].filter(Boolean).join(", ")}</span>
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
                        {avisarAula && !!q.aulas?.length && (
                          <span title={`Lá o aluno vê o gabarito ao responder: ${q.aulas.join("; ")}`}>
                            <Etiqueta tom="atencao">Também em aula</Etiqueta>
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
                        {marcaDeJaEsta(q)}
                      </span>
                    ) : (
                      <Botao tamanho="pequeno" variante="secundario" disabled={escolhendo !== null && escolhendo !== q.questao_id} ocupado={escolhendo === q.questao_id} onClick={() => escolher(q)} className="shrink-0">
                        Adicionar
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
    </>
  );
}
