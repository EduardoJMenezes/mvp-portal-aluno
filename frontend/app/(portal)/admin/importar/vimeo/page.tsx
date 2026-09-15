"use client";

import Link from "next/link";
import { useState } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao } from "@/components/ui";
import { api, useDados, type Destino, type PastaVimeo, type Rascunho, type SimulacaoVimeo } from "@/lib/api";
import { duracao, plural } from "@/lib/formato";

const DESTINO_VAZIO: Destino = { faixa: "", modulo: "", submodulo: "" };

export default function ImportarDoVimeo() {
  const turmas = useDados(() => api.turmas());
  const assuntos = useDados(() => api.assuntos());
  const [turma, setTurma] = useState("");
  const modulos = useDados(() => (turma ? api.modulos(turma) : Promise.resolve([])), [turma]);
  const [pasta, setPasta] = useState<PastaVimeo | null>(null);
  const [destinos, setDestinos] = useState<Destino[]>([{ ...DESTINO_VAZIO }]);
  const [simulacao, setSimulacao] = useState<SimulacaoVimeo | null>(null);
  const [criados, setCriados] = useState<{ rascunhos: Rascunho[]; aviso: string } | null>(null);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState<"" | "simulando" | "importando">("");

  const mudarDestino = (i: number, parcial: Partial<Destino>) => {
    setDestinos((atuais) => atuais.map((d, j) => (j === i ? { ...d, ...parcial } : d)));
    setSimulacao(null);
  };

  const limpos = () =>
    destinos
      .filter((d) => d.modulo && d.submodulo)
      .map((d) => ({ ...d, assunto: d.assunto || undefined, subassunto: d.subassunto || undefined }));

  async function simular() {
    if (!pasta) return;
    setErro("");
    setOcupado("simulando");
    try {
      setSimulacao(await api.simularImportacao(pasta.id, turma, limpos()));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado("");
    }
  }

  async function importar() {
    if (!pasta) return;
    setErro("");
    setOcupado("importando");
    try {
      setCriados(await api.importarPasta(pasta.id, turma, limpos()));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado("");
    }
  }

  if (criados) {
    return (
      <Pagina titulo="Rascunhos criados" voltar={{ href: "/admin/importar/", rotulo: "Importar" }} estreita>
        <Aviso tom="sucesso" titulo={plural(criados.rascunhos.length, "rascunho criado", "rascunhos criados")}>{criados.aviso}</Aviso>
        <Cartao>
          <ul className="divide-y divide-borda">
            {criados.rascunhos.map((r) => (
              <li key={r.rascunho_id}>
                <Link href={`/admin/rascunhos/revisar/?id=${r.rascunho_id}`} className="flex items-center justify-between gap-3 px-5 py-4 hover:bg-canvas">
                  <span>
                    <span className="block font-medium">{[r.modulo, r.submodulo].filter(Boolean).join(" › ")}</span>
                    <span className="text-[13px] text-suave">{r.resumo}</span>
                  </span>
                  <span className="text-sm font-semibold text-acento">Revisar</span>
                </Link>
              </li>
            ))}
          </ul>
        </Cartao>
      </Pagina>
    );
  }

  const modulosDaTurma = modulos.dados ?? [];

  return (
    <Pagina titulo="Pasta do Vimeo para o curso" legenda="Escolha a pasta, diga para onde vai cada faixa de números e simule antes de criar os rascunhos." voltar={{ href: "/admin/importar/", rotulo: "Importar" }}>
      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>1. Turma e pasta</TituloDeSecao>
        <Campo rotulo="Turma" className="max-w-sm">
          {(id) => (
            <select id={id} value={turma} onChange={(e) => { setTurma(e.target.value); setDestinos([{ ...DESTINO_VAZIO }]); setSimulacao(null); }} className="campo">
              <option value="">Escolha a turma</option>
              {turmas.dados?.map((t) => (
                <option key={t.id} value={t.nome}>{t.nome}</option>
              ))}
            </select>
          )}
        </Campo>
        {pasta ? (
          <div className="flex flex-wrap items-center gap-3 rounded-cartao border border-borda bg-canvas px-4 py-3">
            <span className="min-w-0 flex-1">
              <span className="block font-semibold">{pasta.nome}</span>
              <span className="text-[13px] text-suave">{pasta.dentro_de ? `dentro de ${pasta.dentro_de} · ` : ""}{plural(pasta.videos ?? 0, "vídeo")}</span>
            </span>
            <Botao tamanho="pequeno" onClick={() => { setPasta(null); setSimulacao(null); }}>Trocar pasta</Botao>
          </div>
        ) : (
          <EscolherPasta aoEscolher={(p) => { setPasta(p); setSimulacao(null); }} />
        )}
      </Cartao>

      {turma && pasta && (
        <Cartao className="flex flex-col gap-4 p-5">
          <TituloDeSecao>2. Destinos</TituloDeSecao>
          <p className="-mt-2 text-[15px] text-suave">A faixa fala dos números lidos do título do vídeo (Q07 → 7), não da posição na pasta. Um destino sem faixa recolhe o que sobrar.</p>
          {modulos.erro && <Aviso tom="erro">{modulos.erro}</Aviso>}
          {modulos.dados && modulosDaTurma.length === 0 && (
            <Aviso tom="atencao">
              Esta turma ainda não tem módulos. Crie em <Link href="/admin/turmas/" className="font-semibold underline">Turmas › Curso</Link> antes de importar.
            </Aviso>
          )}
          <ol className="flex flex-col gap-3">
            {destinos.map((d, i) => {
              const modulo = modulosDaTurma.find((m) => m.nome === d.modulo);
              const assunto = assuntos.dados?.find((a) => a.nome === d.assunto);
              return (
                <li key={i} className="grid gap-3 rounded-cartao border border-borda p-3 sm:grid-cols-2 lg:grid-cols-[7rem_1fr_1fr_1fr_1fr_auto] lg:items-end">
                  <Campo rotulo="Faixa">{(id) => <input id={id} value={d.faixa} onChange={(e) => mudarDestino(i, { faixa: e.target.value })} placeholder="1-14" className="campo" />}</Campo>
                  <Campo rotulo="Módulo">
                    {(id) => (
                      <select id={id} value={d.modulo} onChange={(e) => mudarDestino(i, { modulo: e.target.value, submodulo: "" })} className="campo">
                        <option value="">Escolha</option>
                        {modulosDaTurma.map((m) => (
                          <option key={m.id} value={m.nome}>{m.nome}</option>
                        ))}
                      </select>
                    )}
                  </Campo>
                  <Campo rotulo="Sub-módulo">
                    {(id) => (
                      <select id={id} value={d.submodulo} disabled={!modulo} onChange={(e) => mudarDestino(i, { submodulo: e.target.value })} className="campo">
                        <option value="">Escolha</option>
                        {modulo?.submodulos.map((s) => (
                          <option key={s.id} value={s.nome}>{s.nome}</option>
                        ))}
                      </select>
                    )}
                  </Campo>
                  <Campo rotulo="Assunto (opcional)">
                    {(id) => (
                      <select id={id} value={d.assunto ?? ""} onChange={(e) => mudarDestino(i, { assunto: e.target.value, subassunto: "" })} className="campo">
                        <option value="">Sem etiqueta</option>
                        {assuntos.dados?.map((a) => (
                          <option key={a.id} value={a.nome}>{a.nome}</option>
                        ))}
                      </select>
                    )}
                  </Campo>
                  <Campo rotulo="Sub-assunto">
                    {(id) => (
                      <select id={id} value={d.subassunto ?? ""} disabled={!assunto?.subassuntos.length} onChange={(e) => mudarDestino(i, { subassunto: e.target.value })} className="campo">
                        <option value="">Nenhum</option>
                        {assunto?.subassuntos.map((s) => (
                          <option key={s.id} value={s.nome}>{s.nome}</option>
                        ))}
                      </select>
                    )}
                  </Campo>
                  <Botao tamanho="pequeno" variante="texto" disabled={destinos.length === 1} onClick={() => { setDestinos(destinos.filter((_, j) => j !== i)); setSimulacao(null); }}>Tirar</Botao>
                </li>
              );
            })}
          </ol>
          <div className="flex flex-wrap gap-2">
            <Botao tamanho="pequeno" onClick={() => setDestinos([...destinos, { ...DESTINO_VAZIO }])}>Outro destino</Botao>
            <Botao tamanho="pequeno" variante="secundario" disabled={!limpos().length || !!ocupado} onClick={() => void simular()}>{ocupado === "simulando" ? "Lendo a pasta…" : "Simular"}</Botao>
          </div>
        </Cartao>
      )}

      {erro && <Aviso tom="erro">{erro}</Aviso>}

      {simulacao && (
        <Cartao className="flex flex-col gap-4 p-5">
          <TituloDeSecao acao={<Botao variante="primario" disabled={!!ocupado} onClick={() => void importar()}>{ocupado === "importando" ? "Criando…" : "Criar rascunhos"}</Botao>}>3. O que vai acontecer</TituloDeSecao>
          <p className="text-[15px] text-suave">
            {plural(simulacao.videos_na_pasta, "vídeo")} na pasta {simulacao.pasta.nome}
            {simulacao.videos_ja_no_acervo.length > 0 && ` · ${simulacao.videos_ja_no_acervo.length} já estão no acervo e são reaproveitados`}. {simulacao.observacao}
          </p>
          {simulacao.destinos.map((d) => (
            <div key={`${d.modulo}-${d.submodulo}-${d.faixa}`} className="rounded-cartao border border-borda">
              <div className="flex flex-wrap items-center gap-2 border-b border-borda px-4 py-3">
                <span className="font-semibold">{d.modulo} › {d.submodulo}</span>
                <Etiqueta>{d.faixa || "o que sobrar"}</Etiqueta>
                {d.assunto && <Etiqueta tom="info">{d.subassunto ? `${d.assunto} › ${d.subassunto}` : d.assunto}</Etiqueta>}
                <span className="ml-auto text-sm font-semibold text-sucesso">{plural(d.itens_que_serao_criados, "item novo", "itens novos")}</span>
              </div>
              {(d.ja_neste_submodulo.length > 0 || d.numeros_da_faixa_sem_video.length > 0) && (
                <div className="flex flex-col gap-1 px-4 pt-3 text-[13px]">
                  {d.ja_neste_submodulo.length > 0 && <p className="text-suave">Já neste sub-módulo (não duplica): {d.ja_neste_submodulo.join(", ")}</p>}
                  {d.numeros_da_faixa_sem_video.length > 0 && <p className="text-atencao">Números da faixa sem vídeo: {d.numeros_da_faixa_sem_video.join(", ")}</p>}
                </div>
              )}
              <ItensDoPlano itens={d.itens} />
            </div>
          ))}
          {simulacao.sem_destino.length > 0 && (
            <div className="rounded-cartao border border-atencao-borda">
              <p className="border-b border-atencao-borda bg-atencao-fundo px-4 py-3 font-semibold text-atencao">Ficam de fora ({simulacao.sem_destino.length}) — nenhum destino cobre o número</p>
              <ItensDoPlano itens={simulacao.sem_destino} />
            </div>
          )}
        </Cartao>
      )}
    </Pagina>
  );
}

function ItensDoPlano({ itens }: { itens: SimulacaoVimeo["sem_destino"] }) {
  if (!itens.length) return <p className="px-4 py-3 text-[15px] text-suave">Nenhum vídeo.</p>;
  return (
    <ul className="max-h-72 divide-y divide-borda overflow-y-auto">
      {itens.map((item) => (
        <li key={item.vimeo_id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2 text-[15px]">
          <span className="w-10 shrink-0 font-semibold tabular-nums text-suave">{item.numero ?? "?"}</span>
          <span className="min-w-0 flex-1">{item.titulo}</span>
          <span className="text-[13px] tabular-nums text-suave">{duracao(item.duracao_segundos)}</span>
          {item.avisos.length > 0 && <span className="basis-full pl-[3.25rem] text-[13px] text-atencao">{item.avisos.join(" · ")}</span>}
        </li>
      ))}
    </ul>
  );
}

function EscolherPasta({ aoEscolher }: { aoEscolher: (pasta: PastaVimeo) => void }) {
  const [busca, setBusca] = useState("");
  const [aplicada, setAplicada] = useState("");
  const pastas = useDados(() => api.pastasVimeo(aplicada || undefined), [aplicada]);

  return (
    <div className="flex flex-col gap-3">
      <form
        className="flex flex-wrap gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setAplicada(busca.trim());
        }}
      >
        <label htmlFor="busca-pasta" className="sr-only">Buscar pasta</label>
        <input id="busca-pasta" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Buscar pasta pelo nome" className="campo min-w-48 flex-1" />
        <Botao type="submit" tamanho="pequeno" variante="secundario">Buscar</Botao>
      </form>
      <Estado {...pastas} linhas={2}>
        {(r) => (
          <>
            <p className="text-[13px] text-suave">Mostrando {r.mostrando} de {r.total_no_vimeo} pastas.</p>
            <ul className="max-h-80 divide-y divide-borda overflow-y-auto rounded-cartao border border-borda">
              {r.pastas.map((p) => (
                <li key={p.id}>
                  <button type="button" onClick={() => aoEscolher(p)} className="flex w-full items-center justify-between gap-3 px-4 py-2.5 text-left hover:bg-canvas">
                    <span className="min-w-0">
                      <span className="block truncate font-medium">{p.nome}</span>
                      {p.dentro_de && <span className="block truncate text-[13px] text-suave">dentro de {p.dentro_de}</span>}
                    </span>
                    <span className="shrink-0 text-[13px] tabular-nums text-suave">{plural(p.videos ?? 0, "vídeo")}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </Estado>
    </div>
  );
}
