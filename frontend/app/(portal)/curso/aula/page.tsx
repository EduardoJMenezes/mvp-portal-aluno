"use client";

import { Check, CircleCheck } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { CapituloConcluido } from "@/components/Comemoracao";
import { Exercicio } from "@/components/Exercicio";
import { Player } from "@/components/Player";
import { LinkDoPdf } from "@/components/Pdf";
import { Aviso, Botao, Estado, Pagina, Progresso, botao } from "@/components/ui";
import { abrirEmNovaAba, api, useDados, type AulaNoCurso, type ConteudoDaTurma, type ItemCurso } from "@/lib/api";
import { duracao, emBrasilia } from "@/lib/formato";
import { useUsuario } from "@/lib/sessao";

export default function PaginaDaAula() {
  return (
    <Suspense>
      <Aula />
    </Suspense>
  );
}

function Aula() {
  // Relido a cada minuto: a aula ao vivo do capítulo muda de estado sem recarregar.
  const conteudo = useDados(() => api.conteudo(), [], 60);
  return (
    <Estado {...conteudo} linhas={4}>
      {(turmas) => <Modulo turmas={turmas} />}
    </Estado>
  );
}

function Modulo({ turmas }: { turmas: ConteudoDaTurma[] }) {
  const router = useRouter();
  const parametros = useSearchParams();
  const moduloId = Number(parametros.get("modulo"));
  const itemId = Number(parametros.get("item"));

  const modulo = useMemo(() => turmas.flatMap((t) => t.modulos).find((m) => m.id === moduloId), [turmas, moduloId]);
  const itens = useMemo(() => modulo?.submodulos.flatMap((s) => s.itens) ?? [], [modulo]);
  // A resposta dada agora: a lista marca a questão sem esperar a árvore do curso ser relida.
  const [feitasAgora, setFeitasAgora] = useState<Record<number, boolean>>({});
  // O mesmo para a aula assistida: o que mudou nesta visita vale por cima do que a árvore trouxe.
  const [concluidosAgora, setConcluidosAgora] = useState<Record<number, boolean>>({});
  const [erroAoMarcar, setErroAoMarcar] = useState("");
  // Só o aluno tem progresso: o professor abre esta tela para conferir, e nada é gravado.
  const acompanha = useUsuario().papel === "ALUNO";

  if (!modulo) {
    return (
      <Pagina titulo="Módulo não encontrado" voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
        <Aviso tom="atencao">Este módulo não está publicado para a sua turma.</Aviso>
      </Pagina>
    );
  }

  const indice = Math.max(0, itens.findIndex((i) => i.id === itemId));
  const atual = itens[indice];
  const concluido = (item: ItemCurso) => (item.questao ? item.id in feitasAgora || !!item.questao.respondida : (concluidosAgora[item.id] ?? !!item.concluido));
  const feitos = itens.filter(concluido).length;
  const anotar = (item: number, valor: boolean) => setConcluidosAgora((c) => (c[item] === valor ? c : { ...c, [item]: valor }));

  async function marcar(item: ItemCurso, valor: boolean) {
    setErroAoMarcar("");
    anotar(item.id, valor);
    try {
      anotar(item.id, (await api.marcarConcluido(item.id, valor)).concluido);
    } catch (ex) {
      anotar(item.id, !valor);
      setErroAoMarcar(ex instanceof Error ? ex.message : "Não foi possível salvar.");
    }
  }
  const ir = (novo: number) => router.push(`/curso/aula/?modulo=${modulo.id}&item=${itens[novo].id}`, { scroll: false });

  // Para onde ir depois de terminar: o capítulo seguinte da turma que ainda tem o que fazer.
  const daTurma = turmas.find((t) => t.modulos.some((m) => m.id === modulo.id))?.modulos ?? [];
  const posicao = daTurma.findIndex((m) => m.id === modulo.id);
  const seguinte = [...daTurma.slice(posicao + 1), ...daTurma.slice(0, posicao)].find((m) => m.submodulos.some((s) => s.itens.some((i) => !i.concluido)));
  const primeiroQueFalta = seguinte?.submodulos.flatMap((s) => s.itens).find((i) => !i.concluido);

  return (
    <Pagina titulo={modulo.nome} legenda={modulo.turma} voltar={{ href: "/curso/", rotulo: "Meu curso" }}>
      {acompanha && (
        <CapituloConcluido
          modulo={modulo}
          feitos={feitos}
          total={itens.length}
          proximo={seguinte && { nome: seguinte.nome, href: `/curso/aula/?modulo=${seguinte.id}${primeiroQueFalta ? `&item=${primeiroQueFalta.id}` : ""}` }}
        />
      )}
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-3">
          {!atual ? (
            <Aviso tom="info" titulo="Ainda sem aula neste capítulo">
              As aulas ao vivo dele estão na lista. Depois de cada uma, a gravação aparece aqui.
            </Aviso>
          ) : atual.questao ? (
            <Exercicio item={atual.id} aoResponder={(correta) => setFeitasAgora((f) => ({ ...f, [atual.id]: correta }))} />
          ) : atual.video ? (
            <VideoDaAula key={atual.id} item={atual} acompanha={acompanha} aoMudar={(valor) => anotar(atual.id, valor)} />
          ) : atual.material ? (
            <Aviso tom="info" titulo="Esta aula é um PDF">
              Abra no leitor: dá para riscar por cima, e o que você marcar fica salvo.
              <div className="mt-3">
                {/* Abrir o PDF já conta como visto; o aluno desmarca se quiser voltar depois. */}
                <Link
                  href={`/materiais/ler/?id=${atual.material.material_id}`}
                  onClick={() => {
                    if (acompanha && !concluido(atual)) void marcar(atual, true);
                  }}
                  className={botao("primario")}
                >
                  Abrir o PDF
                </Link>
              </div>
            </Aviso>
          ) : (
            <Aviso tom="atencao">Este item está sem vídeo.</Aviso>
          )}
          {atual && <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h2 className="text-xl font-semibold text-tinta">{atual?.nome}</h2>
              {atual?.video && !atual.video.bloqueado && atual.video.duracao_segundos ? (
                <p className="text-sm text-suave">{duracao(atual.video.duracao_segundos)}</p>
              ) : null}
              {atual.video && atual.material && <LinkDoPdf material={atual.material} className="mt-1" />}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {acompanha && !atual.questao && !(atual.video?.bloqueado) && (
                <Botao
                  variante={concluido(atual) ? "secundario" : "neutro"}
                  aria-pressed={concluido(atual)}
                  onClick={() => void marcar(atual, !concluido(atual))}
                  title={concluido(atual) ? "Clique para desmarcar" : undefined}
                >
                  {concluido(atual) && <CircleCheck aria-hidden="true" className="size-[18px] text-sucesso-vivo" strokeWidth={2.4} />}
                  {concluido(atual) ? (atual.video ? "Assistida" : "Lido") : atual.video ? "Marcar como assistida" : "Marcar como lido"}
                </Botao>
              )}
              <Botao disabled={indice === 0} onClick={() => ir(indice - 1)}>Anterior</Botao>
              <Botao variante="primario" disabled={indice >= itens.length - 1} onClick={() => ir(indice + 1)}>Próximo</Botao>
            </div>
          </div>}
          {erroAoMarcar && <Aviso tom="erro">{erroAoMarcar}</Aviso>}
        </div>

        <aside aria-label="Aulas do módulo" className="flex flex-col gap-3 lg:sticky lg:top-20 lg:max-h-[calc(100dvh-6rem)] lg:overflow-y-auto">
          {acompanha && itens.length > 0 && (
            <div className="rounded-cartao border border-borda bg-papel px-4 py-3">
              <p className="flex items-baseline justify-between gap-2 text-sm">
                <span className="font-semibold text-tinta">{feitos === itens.length ? "Capítulo concluído" : "Seu progresso"}</span>
                <span className="tabular-nums text-suave">{feitos} de {itens.length}</span>
              </p>
              <Progresso feitos={feitos} total={itens.length} rotulo="Progresso neste capítulo" className="mt-2" />
            </div>
          )}
          {modulo.submodulos.map((sub) => (
            <details key={sub.id} open className="rounded-cartao border border-borda bg-papel">
              <summary className="flex cursor-pointer items-center justify-between px-4 py-3 font-semibold text-tinta">
                {sub.nome}
                <span className="text-sm font-normal tabular-nums text-suave">
                  {acompanha && sub.itens.length > 0 ? `${sub.itens.filter(concluido).length}/${sub.itens.length}` : sub.itens.length}
                </span>
              </summary>
              <ol className="border-t border-borda py-1">
                {sub.itens.map((item) => {
                  const selecionado = item.id === atual?.id;
                  const bloqueado = item.questao ? false : item.video ? item.video.bloqueado : !item.material;
                  // undefined: ainda não respondeu.
                  const acertou = !item.questao ? undefined : item.id in feitasAgora ? feitasAgora[item.id] : item.questao.respondida ? item.questao.correta === true : undefined;
                  return (
                    <li key={item.id}>
                      <Link
                        href={`/curso/aula/?modulo=${modulo.id}&item=${item.id}`}
                        scroll={false}
                        aria-current={selecionado ? "true" : undefined}
                        className={`flex items-center gap-2 px-4 py-2 text-[15px] ${selecionado ? "bg-lilas font-semibold text-acento-forte" : "text-tinta hover:bg-canvas"}`}
                      >
                        <span aria-hidden="true" className="flex w-4 shrink-0 justify-center text-xs text-suave">
                          {bloqueado ? (
                            <svg width="12" height="12" viewBox="0 0 24 24"><path d="M7 10V7a5 5 0 0 1 10 0v3" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" /><rect x="4.5" y="10" width="15" height="11" rx="2.5" fill="currentColor" /></svg>
                          ) : acertou !== undefined ? (
                            <span className={`font-bold ${acertou ? "text-sucesso" : "text-erro"}`}>{acertou ? "✓" : "✗"}</span>
                          ) : !item.questao && concluido(item) ? (
                            <Check className="size-4 text-sucesso-vivo" strokeWidth={3} />
                          ) : selecionado ? (
                            "▶"
                          ) : item.questao ? (
                            <span className="text-[11px] font-bold">?</span>
                          ) : !item.video ? (
                            <span className="text-[10px] font-bold">PDF</span>
                          ) : null}
                        </span>
                        <span className="truncate">{item.nome}</span>
                        {bloqueado && <span className="sr-only">(bloqueado)</span>}
                        {!item.questao && concluido(item) && <span className="sr-only">({item.video ? "assistida" : "lido"})</span>}
                        {item.questao && <span className="sr-only">{acertou === undefined ? "(questão para responder)" : acertou ? "(questão: você acertou)" : "(questão: você errou)"}</span>}
                      </Link>
                    </li>
                  );
                })}
                {sub.aulas?.map((aula) => <AulaAoVivo key={`aula-${aula.aula_id}`} aula={aula} />)}
              </ol>
            </details>
          ))}
        </aside>
      </div>
    </Pagina>
  );
}

/**
 * O vídeo da aula, que continua de onde o aluno parou e conta ao backend até onde ele foi. Quem usa
 * dá uma `key` por aula: cada aula tem o seu player e o seu ponto de retomada.
 */
function VideoDaAula({ item, acompanha, aoMudar }: { item: ItemCurso; acompanha: boolean; aoMudar: (concluido: boolean) => void }) {
  const video = item.video!;
  const registra = acompanha && !video.bloqueado && !!video.embed_url;
  // Sem o ponto de retomada o vídeo abre do começo: não é motivo para não tocar.
  const parou = useDados(() => (registra ? api.progressoDoItem(item.id).catch(() => null) : Promise.resolve(null)), [item.id, registra]);

  if (!registra) return <Player video={video} />;
  if (parou.carregando) return <div aria-busy="true" aria-label="Carregando o vídeo" className="aspect-video w-full animate-pulse rounded-cartao bg-tinta/10" />;

  const total = video.duracao_segundos ?? 0;
  const p = parou.dados;
  // Quem já terminou, ou parou nos últimos instantes, recomeça do início.
  const inicio = p && !p.concluido && p.posicao_segundos >= 10 && (!total || p.posicao_segundos < total * 0.95) ? p.posicao_segundos : 0;
  return (
    <Player
      video={video}
      inicio={inicio}
      aoAssistir={(posicao, duracaoDoVideo) => void api.registrarProgresso(item.id, posicao, duracaoDoVideo).then((r) => aoMudar(r.concluido), () => {})}
    />
  );
}

/** A aula ao vivo no capítulo, até a gravação chegar e tomar o lugar dela. */
function AulaAoVivo({ aula }: { aula: AulaNoCurso }) {
  const [erro, setErro] = useState("");
  const [abrindo, setAbrindo] = useState(false);
  const salaAberta = aula.estado === "ABERTA" || aula.estado === "AGUARDANDO";

  const entrar = async () => {
    setErro("");
    setAbrindo(true);
    try {
      await abrirEmNovaAba(() => api.entrarNaAula(aula.aula_id));
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível entrar na aula.");
    } finally {
      setAbrindo(false);
    }
  };

  return (
    <li className="flex flex-col gap-1.5 px-4 py-2 text-[15px]">
      <span className="flex items-center gap-2">
        <span aria-hidden="true" className="flex w-4 shrink-0 justify-center">
          {aula.estado === "ENCERRADA" ? (
            <span className="size-3 rounded-full border-2 border-borda border-t-acento motion-safe:animate-spin" />
          ) : aula.estado === "ABERTA" ? (
            <span className="relative flex size-2.5">
              <span className="absolute inline-flex size-full rounded-full bg-erro opacity-75 motion-safe:animate-ping" />
              <span className="relative inline-flex size-2.5 rounded-full bg-erro" />
            </span>
          ) : (
            <span className={`size-2.5 rounded-full ${aula.estado === "AGUARDANDO" ? "bg-atencao" : "bg-apagado"}`} />
          )}
        </span>
        <span className="truncate text-tinta">{aula.titulo}</span>
      </span>
      <span className="pl-6 text-[13px] text-suave">
        {aula.estado === "AGENDADA" && `Ao vivo em ${emBrasilia(aula.inicio_em)}`}
        {aula.estado === "AGUARDANDO" && "A sala abriu; a aula já vai começar"}
        {aula.estado === "ABERTA" && "Ao vivo agora"}
        {aula.estado === "ENCERRADA" && "Gravação processando: aparece aqui em breve"}
      </span>
      {aula.material && <LinkDoPdf material={aula.material} className="ml-6" />}
      {salaAberta && (
        <Botao tamanho="pequeno" variante="primario" className="ml-6 w-fit" disabled={abrindo} onClick={() => void entrar()}>
          {abrindo ? "Abrindo…" : "Entrar na aula"}
        </Botao>
      )}
      {erro && <p role="alert" className="pl-6 text-[13px] text-erro">{erro}</p>}
    </li>
  );
}
