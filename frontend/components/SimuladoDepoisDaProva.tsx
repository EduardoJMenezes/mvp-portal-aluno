"use client";

// O simulado depois que fechou, para o professor. A pergunta deixa de ser "como está a prova?"
// (isso é a agenda e as questões, que valem antes e durante) e passa a ser "como a turma foi?" e
// "o que cada aluno recebeu?". São três peças: o resultado da turma, os alunos pelo ranking e o
// resultado de um aluno, que é a própria tela dele.

import { ArrowLeft, ArrowRight, Users } from "lucide-react";
import { useState } from "react";
import { AnaliseDoSimuladoParaOProfessor } from "@/components/Analise";
import { PainelDoResultado, type AbaDoResultado } from "@/components/ResultadoDoSimulado";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type AlunoNoSimulado, type VisaoDoSimulado } from "@/lib/api";
import { emBrasilia, plural, porcento } from "@/lib/formato";
import { TextoFormatado } from "@/lib/texto";

const semAcento = (texto: string) => texto.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

const corDoAcerto = (percentual: number) => (percentual < 40 ? "bg-erro" : percentual < 70 ? "bg-atencao" : "bg-sucesso");

function Numero({ rotulo, valor, detalhe }: { rotulo: string; valor: string; detalhe?: string }) {
  return (
    <Cartao como="div" className="p-4">
      <p className="text-[13px] font-semibold uppercase tracking-wide text-suave">{rotulo}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-tinta">{valor}</p>
      {detalhe && <p className="truncate text-[13px] text-suave">{detalhe}</p>}
    </Cartao>
  );
}

/** A primeira tela do simulado encerrado: como a turma foi e onde mais errou. */
export function ResultadoDaTurma({ v, aoVerAlunos, aoVerQuestoes }: { v: VisaoDoSimulado; aoVerAlunos: () => void; aoVerQuestoes: () => void }) {
  if (v.participantes === 0) {
    return <Vazio titulo="Ninguém fez esta prova">O simulado fechou sem nenhuma prova entregue, então não há resultado para mostrar.</Vazio>;
  }
  return (
    <>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Numero rotulo="Fizeram a prova" valor={`${v.participantes} de ${v.matriculados}`} detalhe="alunos das turmas do simulado" />
        <Numero rotulo="Média da turma" valor={porcento(v.media ?? 0)} detalhe={`${v.questoes} questões`} />
        <Numero
          rotulo="Análises escritas"
          valor={`${v.com_analise} de ${v.participantes}`}
          detalhe={v.questoes_sem_comentario ? `${plural(v.questoes_sem_comentario, "questão sem comentário", "questões sem comentário")}` : "todas as questões comentadas"}
        />
        <Numero rotulo="Resultado" valor={v.resultado_liberado_em ? "Liberado" : "A liberar"} detalhe={v.resultado_liberado_em ? `em ${emBrasilia(v.resultado_liberado_em)}` : "os alunos ainda não veem"} />
      </div>

      <section className="flex flex-col gap-3" aria-labelledby="titulo-turma-por-assunto">
        <TituloDeSecao>
          <span id="titulo-turma-por-assunto">A turma por assunto</span>
        </TituloDeSecao>
        <Cartao como="div" className="flex flex-col gap-3 p-5">
          <ul className="flex flex-col gap-3">
            {v.por_assunto.map((a) => (
              <li key={a.assunto} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto]">
                <span className="truncate text-[15px] font-medium text-tinta">{a.assunto}</span>
                <span className="col-span-2 block h-2.5 overflow-hidden rounded-full bg-gelo sm:col-span-1 sm:col-start-2 sm:row-start-1" aria-hidden="true">
                  <span className={`block h-full rounded-full ${corDoAcerto(a.percentual)}`} style={{ width: `${Math.max(a.percentual, 2)}%` }} />
                </span>
                <span className="col-start-2 row-start-1 text-right text-[13px] tabular-nums text-suave sm:col-start-3">
                  <span className="font-semibold text-tinta">{porcento(Math.round(a.percentual))}</span> · {plural(a.questoes, "questão", "questões")}
                </span>
              </li>
            ))}
          </ul>
          <p className="text-[13px] text-suave">O acerto de todos os que fizeram a prova, do assunto que foi pior para o que foi melhor. Em branco conta como erro.</p>
        </Cartao>
      </section>

      {v.mais_erradas.length > 0 && (
        <section className="flex flex-col gap-3" aria-labelledby="titulo-mais-erradas">
          <TituloDeSecao acao={<Botao tamanho="mini" onClick={aoVerQuestoes}>Ver todas as questões</Botao>}>
            <span id="titulo-mais-erradas">Onde a turma mais errou</span>
          </TituloDeSecao>
          <Cartao como="div" className="overflow-hidden">
            <ul className="divide-y divide-borda">
              {v.mais_erradas.map((q) => (
                <li key={q.questao_id} className="flex flex-col gap-1.5 px-5 py-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold text-tinta">Questão {q.ordem}</span>
                    <Etiqueta tom={q.percentual_acerto < 40 ? "erro" : q.percentual_acerto < 70 ? "atencao" : "neutro"}>{porcento(Math.round(q.percentual_acerto))} de acerto</Etiqueta>
                    {(q.subassunto ?? q.assunto) && <span className="text-[13px] text-suave">{[q.assunto, q.subassunto].filter(Boolean).join(" › ")}</span>}
                  </div>
                  <p className="truncate text-[15px] text-suave">{q.enunciado}</p>
                  {q.erro_mais_comum ? (
                    <div className="text-[15px] text-tinta-2">
                      <p>
                        O erro mais comum foi marcar <span className="font-semibold text-tinta">{q.erro_mais_comum}</span> ({plural(q.quantos_marcaram ?? 0, "aluno", "alunos")}); o gabarito é {q.gabarito}.
                        {q.em_branco > 0 && ` ${plural(q.em_branco, "deixou", "deixaram")} em branco.`}
                      </p>
                      {q.comentario_do_erro && (
                        <div className="mt-1.5 rounded-campo bg-canvas px-3 py-2">
                          <p className="text-[13px] font-semibold text-suave">Seu comentário para a alternativa {q.erro_mais_comum}</p>
                          <TextoFormatado texto={q.comentario_do_erro} compacto />
                        </div>
                      )}
                    </div>
                  ) : (
                    <p className="text-[15px] text-tinta-2">Quem não acertou deixou em branco ({plural(q.em_branco, "aluno", "alunos")}); o gabarito é {q.gabarito}.</p>
                  )}
                </li>
              ))}
            </ul>
          </Cartao>
        </section>
      )}

      <Cartao como="div" className="flex flex-wrap items-center justify-between gap-3 p-5">
        <div className="flex min-w-0 items-start gap-3">
          <Users aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-acento" strokeWidth={2} />
          <div>
            <h3 className="font-semibold text-tinta">O resultado de cada aluno</h3>
            <p className="text-[15px] text-suave">Abra a tela que cada um vê, com a nota, a análise escrita para ele, os gráficos e a prova corrigida.</p>
          </div>
        </div>
        <Botao variante="secundario" tamanho="pequeno" onClick={aoVerAlunos}>Ver os alunos</Botao>
      </Cartao>
    </>
  );
}

function SeloDaAnalise({ a }: { a: AlunoNoSimulado }) {
  if (!a.tem_analise) return <span className="text-suave">Sem análise</span>;
  return a.analise_oculta ? <Etiqueta tom="atencao">Oculta</Etiqueta> : <Etiqueta tom="sucesso">Escrita</Etiqueta>;
}

/** Os alunos pelo ranking, com busca: é de onde o professor abre o resultado de cada um. */
export function AlunosDoSimulado({ v, aoAbrir }: { v: VisaoDoSimulado; aoAbrir: (aluno: number) => void }) {
  const [busca, setBusca] = useState("");
  const termo = semAcento(busca.trim());
  const lista = termo ? v.alunos.filter((a) => semAcento(a.aluno).includes(termo)) : v.alunos;

  if (v.alunos.length === 0) return <Vazio titulo="Ninguém fez esta prova" />;
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <Campo rotulo="Procurar aluno" className="w-full max-w-md">
          {(id) => <input id={id} type="search" value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Nome do aluno" className="campo" />}
        </Campo>
        <p className="text-[13px] text-suave">
          {plural(v.participantes, "aluno fez", "alunos fizeram")} a prova · {v.com_analise} com análise escrita. O ranking completo é só seu.
        </p>
      </div>
      {lista.length === 0 ? (
        <Vazio titulo="Ninguém com esse nome nesta prova">Confira a grafia. Só aparece aqui quem fez o simulado.</Vazio>
      ) : (
        <Cartao className="overflow-x-auto">
          <table className="tabela min-w-[40rem]">
            <thead>
              <tr>
                <th scope="col" className="w-12">#</th>
                <th scope="col">Aluno</th>
                <th scope="col" className="text-right">Acertos</th>
                <th scope="col" className="text-right">%</th>
                <th scope="col" className="text-right">Em branco</th>
                <th scope="col">Análise</th>
                <th scope="col"><span className="sr-only">Abrir</span></th>
              </tr>
            </thead>
            <tbody>
              {lista.map((a) => (
                <tr key={a.aluno_id}>
                  <td className="font-semibold tabular-nums">{a.posicao}º</td>
                  <td>
                    {a.aluno}
                    {a.entregue_automaticamente && <Etiqueta className="ml-2">Entregue pelo tempo</Etiqueta>}
                  </td>
                  <td className="text-right tabular-nums">{a.acertos}/{v.questoes}</td>
                  <td className="text-right tabular-nums">{porcento(a.percentual)}</td>
                  <td className="text-right tabular-nums text-suave">{a.em_branco}</td>
                  <td><SeloDaAnalise a={a} /></td>
                  <td className="text-right">
                    <Botao tamanho="mini" onClick={() => aoAbrir(a.aluno_id)}>Ver resultado</Botao>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Cartao>
      )}
    </>
  );
}

/**
 * O resultado de um aluno, aberto pelo professor: a mesma tela que o aluno vê, com o bloco da
 * análise trocado pelo do professor (que a mostra mesmo oculta e deixa ocultar) e os vizinhos do
 * ranking a um clique.
 */
export function ResultadoDeUmAluno({
  v,
  alunoId,
  aba,
  aoTrocarAba,
  aoAbrir,
  aoVoltar,
  aoMudarAnalise,
}: {
  v: VisaoDoSimulado;
  alunoId: number;
  aba: AbaDoResultado;
  aoTrocarAba: (nova: AbaDoResultado) => void;
  aoAbrir: (aluno: number) => void;
  aoVoltar: () => void;
  aoMudarAnalise: () => void;
}) {
  const resultado = useDados(() => api.resultadoDoAluno(v.simulado_id, alunoId), [v.simulado_id, alunoId]);
  const analise = useDados(() => api.analiseDoAluno(alunoId, v.simulado_id).catch(() => null), [v.simulado_id, alunoId]);
  const indice = v.alunos.findIndex((a) => a.aluno_id === alunoId);
  const linha = indice >= 0 ? v.alunos[indice] : null;
  const anterior = indice > 0 ? v.alunos[indice - 1] : null;
  const proximo = indice >= 0 && indice < v.alunos.length - 1 ? v.alunos[indice + 1] : null;

  return (
    <>
      <Cartao como="div" className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="flex min-w-0 flex-wrap items-center gap-3">
          <Botao tamanho="pequeno" onClick={aoVoltar}>
            <ArrowLeft aria-hidden="true" className="size-4" /> Todos os alunos
          </Botao>
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold text-tinta">{linha?.aluno ?? resultado.dados?.aluno ?? "Aluno"}</h2>
            {linha && <p className="text-[13px] text-suave">{linha.posicao}º de {v.participantes} · é a tela que o aluno vê</p>}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Botao tamanho="pequeno" disabled={!anterior} onClick={() => anterior && aoAbrir(anterior.aluno_id)} aria-label="Aluno anterior no ranking">
            <ArrowLeft aria-hidden="true" className="size-4" /> Anterior
          </Botao>
          <Botao tamanho="pequeno" disabled={!proximo} onClick={() => proximo && aoAbrir(proximo.aluno_id)} aria-label="Próximo aluno no ranking">
            Próximo <ArrowRight aria-hidden="true" className="size-4" />
          </Botao>
          <BotaoLink tamanho="pequeno" href={`/admin/alunos/?aluno=${alunoId}`}>Acompanhamento</BotaoLink>
        </div>
      </Cartao>

      {!v.resultado_liberado_em && (
        <Aviso tom="atencao" titulo="Prévia">O resultado deste simulado ainda não foi liberado: o aluno só vai ver esta tela quando você liberar.</Aviso>
      )}

      <Estado {...resultado} linhas={4}>
        {(r) => (
          <PainelDoResultado
            // Aluno novo, tela nova: a questão aberta na prova corrigida não passa de um para o outro.
            key={alunoId}
            r={r}
            aba={aba}
            aoTrocar={aoTrocarAba}
            analise={
              analise.dados ? (
                <AnaliseDoSimuladoParaOProfessor
                  aluno={String(alunoId)}
                  simuladoId={v.simulado_id}
                  dados={analise.dados}
                  aoMudar={(novo) => {
                    analise.setDados(novo);
                    aoMudarAnalise();
                  }}
                />
              ) : null
            }
          />
        )}
      </Estado>
    </>
  );
}
