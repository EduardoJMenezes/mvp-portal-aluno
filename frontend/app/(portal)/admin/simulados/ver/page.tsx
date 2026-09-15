"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { MontarProva, doBanco, paraApi, type EntradaDaProva } from "@/components/MontarProva";
import { Abas, Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type SimuladoDoProfessor } from "@/lib/api";
import { paraCampoDataHora, plural, porcento } from "@/lib/formato";
import { LETRAS, SITUACAO } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDoSimulado() {
  return (
    <Suspense>
      <Simulado />
    </Suspense>
  );
}

type Aba = "prova" | "estatisticas" | "ranking";

function Simulado() {
  const id = Number(useSearchParams().get("id"));
  const simulado = useDados(() => api.simulado(id), [id]);
  const [aba, setAba] = useState<Aba>("prova");
  const s = simulado.dados;

  return (
    <Pagina
      titulo={s?.titulo ?? "Simulado"}
      legenda={s && `${s.turmas.join(", ") || "Sem turma"} · ${plural(s.total_questoes, "questão", "questões")}${s.duracao_minutos ? ` · ${s.duracao_minutos} min de prova` : ""}`}
      voltar={{ href: "/admin/simulados/", rotulo: "Simulados" }}
      acoes={s && <Etiqueta tom={SITUACAO[s.situacao][0]}>{SITUACAO[s.situacao][1]}</Etiqueta>}
    >
      <Abas
        abas={[
          { valor: "prova", rotulo: "Prova" },
          { valor: "estatisticas", rotulo: "Estatísticas" },
          { valor: "ranking", rotulo: "Ranking" },
        ]}
        atual={aba}
        aoTrocar={setAba}
      />
      {aba === "prova" && (
        <Estado {...simulado} linhas={4}>
          {(dados) => <Prova simulado={dados} aoSalvar={() => void simulado.recarregar()} />}
        </Estado>
      )}
      {aba === "estatisticas" && <Estatisticas id={id} />}
      {aba === "ranking" && <Ranking id={id} />}
    </Pagina>
  );
}

function Prova({ simulado: s, aoSalvar }: { simulado: SimuladoDoProfessor; aoSalvar: () => void }) {
  const router = useRouter();
  const [dialogo, confirmar] = useConfirmar();
  const [erro, setErro] = useState("");

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${s.titulo}"?`,
      texto:
        s.situacao === "RASCUNHO"
          ? "O rascunho vai junto, com as questões novas que nasceram nele."
          : "Os alunos deixam de ver o simulado. As provas feitas ficam guardadas.",
      confirmar: "Remover simulado",
      perigo: true,
    });
    if (!sim) return;
    try {
      await api.removerSimulado(s.simulado_id);
      router.replace("/admin/simulados/");
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {dialogo}
      {s.situacao === "RASCUNHO" && (
        <Aviso tom="atencao" titulo="Em rascunho: nenhum aluno vê">
          {s.pendencias_para_publicar.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {s.pendencias_para_publicar.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
          {s.rascunho_id && (
            <div className="mt-2">
              <BotaoLink tamanho="pequeno" variante="primario" href={`/admin/rascunhos/revisar/?id=${s.rascunho_id}`}>Revisar e publicar</BotaoLink>
            </div>
          )}
        </Aviso>
      )}
      <Edicao simulado={s} aoSalvar={aoSalvar} />

      <Cartao className="flex flex-col gap-3 p-5">
        <TituloDeSecao>Questões com gabarito</TituloDeSecao>
        <ol className="flex flex-col gap-3">
          {s.questoes.map((q) => (
            <li key={q.questao_id} className="rounded-cartao border border-borda p-4">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className="font-semibold tabular-nums">{q.ordem}.</span>
                <Etiqueta tom="sucesso">Gabarito {q.gabarito}</Etiqueta>
                {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                {q.resolucao ? <Etiqueta tom="info">Vídeo: {q.resolucao}</Etiqueta> : <Etiqueta>Sem vídeo de resolução</Etiqueta>}
                <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="ml-auto text-sm font-semibold text-acento hover:underline">Editar questão #{q.questao_id}</Link>
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
            </li>
          ))}
        </ol>
      </Cartao>

      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {s.situacao !== "ABERTO" && (
        <div>
          <Botao variante="perigo" onClick={() => void remover()}>Remover simulado</Botao>
        </div>
      )}
    </div>
  );
}

function Edicao({ simulado: s, aoSalvar }: { simulado: SimuladoDoProfessor; aoSalvar: () => void }) {
  const turmas = useDados(() => api.turmas());
  const tudo = s.situacao === "RASCUNHO" || s.situacao === "AGENDADO";
  const [titulo, setTitulo] = useState(s.titulo);
  const [abre, setAbre] = useState(paraCampoDataHora(s.abre_em));
  const [fecha, setFecha] = useState(paraCampoDataHora(s.fecha_em));
  const [duracao, setDuracao] = useState(s.duracao_minutos ? String(s.duracao_minutos) : "");
  const [escolhidas, setEscolhidas] = useState(s.turmas);
  const [questoes, setQuestoes] = useState<EntradaDaProva[]>(() => s.questoes.map(doBanco));
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [salvando, setSalvando] = useState(false);

  // Depois de salvar, o formulário parte do que o backend devolveu.
  useEffect(() => {
    setTitulo(s.titulo);
    setAbre(paraCampoDataHora(s.abre_em));
    setFecha(paraCampoDataHora(s.fecha_em));
    setDuracao(s.duracao_minutos ? String(s.duracao_minutos) : "");
    setEscolhidas(s.turmas);
    setQuestoes(s.questoes.map(doBanco));
  }, [s]);

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setAviso("");
    const mudancas: Record<string, unknown> = {};
    if (titulo.trim() !== s.titulo) mudancas.titulo = titulo.trim();
    if (s.situacao !== "ENCERRADO" && fecha !== paraCampoDataHora(s.fecha_em)) mudancas.fecha_em = fecha;
    if (tudo) {
      if (abre !== paraCampoDataHora(s.abre_em)) mudancas.abre_em = abre;
      if (duracao !== (s.duracao_minutos ? String(s.duracao_minutos) : "")) mudancas.duracao_minutos = Number(duracao);
      if ([...escolhidas].sort().join("|") !== [...s.turmas].sort().join("|")) mudancas.turmas = escolhidas;
      const ordemAtual = s.questoes.map((q) => q.questao_id).join(",");
      if (questoes.some((q) => q.questao_id === undefined) || questoes.map((q) => q.questao_id).join(",") !== ordemAtual) mudancas.questoes = paraApi(questoes);
    }
    if (!Object.keys(mudancas).length) return setAviso("Nada mudou.");
    if (mudancas.fecha_em === "" || mudancas.abre_em === "") return setErro("Agenda não pode ficar em branco depois de definida.");
    setSalvando(true);
    try {
      await api.editarSimulado(s.simulado_id, mudancas);
      setAviso(s.situacao === "RASCUNHO" ? "Rascunho atualizado." : "Simulado atualizado. Os alunos já veem a mudança.");
      aoSalvar();
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setSalvando(false);
    }
  }

  const regra = {
    RASCUNHO: "Tudo muda enquanto é rascunho.",
    AGENDADO: "Ainda não abriu: tudo muda, mas o simulado precisa continuar pronto para ir ao ar.",
    ABERTO: "Está aberto: só o título muda, e o fechamento só pode ser estendido.",
    ENCERRADO: "Fechou e o resultado saiu: só o título muda.",
  }[s.situacao];

  return (
    <Cartao className="p-5">
      <form onSubmit={salvar} className="flex flex-col gap-4">
        <div>
          <TituloDeSecao>Configuração</TituloDeSecao>
          <p className="text-[15px] text-suave">{regra}</p>
        </div>
        <Campo rotulo="Título">{(cid) => <input id={cid} required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} className="campo" />}</Campo>
        <div className="grid gap-4 sm:grid-cols-3">
          <Campo rotulo="Abre em" dica="Horário de Brasília">
            {(cid) => <input id={cid} type="datetime-local" disabled={!tudo} value={abre} onChange={(e) => setAbre(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Fecha em">
            {(cid) => <input id={cid} type="datetime-local" disabled={s.situacao === "ENCERRADO"} min={s.situacao === "ABERTO" ? paraCampoDataHora(s.fecha_em) : undefined} value={fecha} onChange={(e) => setFecha(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Tempo de prova (min)">
            {(cid) => <input id={cid} type="number" min={1} max={1440} disabled={!tudo} value={duracao} onChange={(e) => setDuracao(e.target.value)} className="campo" />}
          </Campo>
        </div>
        {tudo && (
          <>
            <fieldset>
              <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas</legend>
              <div className="flex flex-wrap gap-2">
                {turmas.dados?.map((t) => {
                  const marcada = escolhidas.includes(t.nome);
                  return (
                    <label key={t.id} className={`flex cursor-pointer items-center gap-2 rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"}`}>
                      <input type="checkbox" className="sr-only" checked={marcada} onChange={() => setEscolhidas((atual) => (marcada ? atual.filter((n) => n !== t.nome) : [...atual, t.nome]))} />
                      {t.nome}
                    </label>
                  );
                })}
              </div>
            </fieldset>
            <div className="flex flex-col gap-2">
              <p className="text-sm font-semibold text-tinta-2">Ordem e questões da prova</p>
              <MontarProva entradas={questoes} aoMudar={setQuestoes} permiteNova={s.situacao === "RASCUNHO" && !!s.rascunho_id} />
            </div>
          </>
        )}
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
        <div>
          <Botao type="submit" variante="primario" disabled={salvando}>{salvando ? "Salvando…" : "Salvar mudanças"}</Botao>
        </div>
      </form>
    </Cartao>
  );
}

function Estatisticas({ id }: { id: number }) {
  const dados = useDados(() => api.estatisticas(id), [id]);
  return (
    <Estado {...dados} linhas={3}>
      {(e) =>
        !e.encontrou_dados ? (
          <Vazio titulo="Ainda sem provas">{e.mensagem}</Vazio>
        ) : (
          <div className="flex flex-col gap-4">
            {e.parcial && <Aviso tom="atencao">Parcial: o simulado ainda não fechou e os números mudam até lá.</Aviso>}
            <div className="grid gap-3 sm:grid-cols-3">
              <Numero rotulo="Média da turma" valor={porcento(e.media_percentual ?? 0)} />
              <Numero rotulo="Fizeram a prova" valor={`${e.alunos_responderam} de ${e.alunos_matriculados}`} />
              <Numero rotulo="Questão mais difícil" valor={e.maior_dificuldade ? `#${e.maior_dificuldade.questao_id} · ${porcento(e.maior_dificuldade.percentual_acerto)}` : "—"} detalhe={e.maior_dificuldade?.topico ?? undefined} />
            </div>
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[44rem]">
                <thead>
                  <tr>
                    <th scope="col">Nº</th>
                    <th scope="col">Tópico</th>
                    <th scope="col">Acerto</th>
                    {LETRAS.map((l) => (
                      <th key={l} scope="col" className="text-center">{l}</th>
                    ))}
                    <th scope="col" className="text-center">Branco</th>
                  </tr>
                </thead>
                <tbody>
                  {e.por_questao?.map((q) => (
                    <tr key={q.questao_id}>
                      <td className="tabular-nums">{q.ordem}</td>
                      <td className="max-w-56 truncate text-suave" title={q.topico ?? undefined}>{q.topico ?? "—"}</td>
                      <td>
                        <div className="flex items-center gap-2">
                          <div className="h-2 w-20 overflow-hidden rounded-full bg-canvas" aria-hidden="true">
                            <div className={`h-full ${q.percentual_acerto < 40 ? "bg-erro" : q.percentual_acerto < 70 ? "bg-atencao" : "bg-sucesso"}`} style={{ width: `${q.percentual_acerto}%` }} />
                          </div>
                          <span className="tabular-nums">{porcento(q.percentual_acerto)}</span>
                        </div>
                      </td>
                      {LETRAS.map((l) => (
                        <td key={l} className={`text-center tabular-nums ${q.gabarito === l ? "bg-sucesso-fundo font-semibold text-sucesso" : ""}`}>
                          {q.distribuicao[l] ?? 0}
                        </td>
                      ))}
                      <td className="text-center tabular-nums text-suave">{q.em_branco}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Cartao>
            <p className="-mt-2 text-[13px] text-suave">A coluna em verde é o gabarito. Em branco conta como erro.</p>
          </div>
        )
      }
    </Estado>
  );
}

function Numero({ rotulo, valor, detalhe }: { rotulo: string; valor: string; detalhe?: string }) {
  return (
    <Cartao className="p-4">
      <p className="text-[13px] font-semibold uppercase tracking-wide text-suave">{rotulo}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-tinta">{valor}</p>
      {detalhe && <p className="truncate text-[13px] text-suave">{detalhe}</p>}
    </Cartao>
  );
}

function Ranking({ id }: { id: number }) {
  const dados = useDados(() => api.ranking(id), [id]);
  return (
    <Estado {...dados} linhas={3}>
      {(r) =>
        r.ranking.length === 0 ? (
          <Vazio titulo="Ninguém entregou ainda" />
        ) : (
          <div className="flex flex-col gap-3">
            {r.parcial && <Aviso tom="atencao">Parcial: só entra quem já entregou, e o simulado ainda não fechou.</Aviso>}
            <p className="text-[15px] text-suave">O ranking completo é só seu; o aluno vê apenas a própria posição, depois do fechamento.</p>
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[36rem]">
                <thead>
                  <tr>
                    <th scope="col" className="w-12">#</th>
                    <th scope="col">Aluno</th>
                    <th scope="col">Turma</th>
                    <th scope="col" className="text-right">Acertos</th>
                    <th scope="col" className="text-right">%</th>
                  </tr>
                </thead>
                <tbody>
                  {r.ranking.map((l) => (
                    <tr key={`${l.posicao}-${l.aluno}`}>
                      <td className="font-semibold tabular-nums">{l.posicao}º</td>
                      <td>
                        {l.aluno}
                        {l.entregue_automaticamente && <Etiqueta className="ml-2">Entregue pelo tempo</Etiqueta>}
                      </td>
                      <td className="text-suave">{l.turmas.join(", ")}</td>
                      <td className="text-right tabular-nums">{l.acertos}/{l.total}</td>
                      <td className="text-right tabular-nums">{porcento(l.percentual)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Cartao>
          </div>
        )
      }
    </Estado>
  );
}
