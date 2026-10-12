"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AnaliseDoSimuladoParaOProfessor, AnaliseGeral } from "@/components/Analise";
import { CursoDoAluno } from "@/components/CursoDoAluno";
import { PorAssunto, ResumoDaDevolutiva } from "@/components/Devolutiva";
import { OndeRevisar } from "@/components/Resultado";
import { Anel } from "@/components/Resultado";
import { Aviso, BotaoLink, Campo, Cartao, Estado, Etiqueta, LinkDeTitulo, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type AlunoNaBusca } from "@/lib/api";
import { dataCurta, plural } from "@/lib/formato";

export default function PaginaDoDesempenho() {
  return (
    <Suspense>
      <DesempenhoDoAluno />
    </Suspense>
  );
}

const semAcento = (texto: string) => texto.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/** A porta de entrada: o professor acha o aluno pelo nome e abre o acompanhamento dele. */
function BuscaDeAlunos() {
  const alunos = useDados(() => api.alunos());
  const [busca, setBusca] = useState("");
  const termo = semAcento(busca.trim());
  const filtrar = (lista: AlunoNaBusca[]) => (termo ? lista.filter((a) => semAcento(a.nome).includes(termo) || semAcento(a.email).includes(termo)) : lista);

  return (
    <Pagina titulo="Desempenho por aluno" legenda="Procure um aluno para ver o que ele já fez do curso, como está em cada assunto, como foi nos simulados e a análise geral dele.">
      <Campo rotulo="Aluno" className="max-w-md">
        {(id) => <input id={id} type="search" autoFocus value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Nome ou e-mail" className="campo" />}
      </Campo>
      <Estado {...alunos} linhas={4}>
        {(todos) => {
          const lista = filtrar(todos);
          if (!todos.length) return <Vazio titulo="Nenhum aluno cadastrado">Os alunos entram pelas turmas, em Turmas e alunos.</Vazio>;
          if (!lista.length) return <Vazio titulo="Ninguém com esse nome">Confira a grafia, ou procure pelo e-mail.</Vazio>;
          return (
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[36rem]">
                <thead>
                  <tr>
                    <th scope="col">Aluno</th>
                    <th scope="col">Turmas</th>
                    <th scope="col">Análise geral</th>
                    <th scope="col"><span className="sr-only">Abrir</span></th>
                  </tr>
                </thead>
                <tbody>
                  {lista.map((a) => (
                    <tr key={a.id}>
                      <td>
                        <LinkDeTitulo href={`/admin/alunos/?aluno=${a.id}`}>{a.nome}</LinkDeTitulo>
                        <span className="block text-[13px] text-suave">{a.email}</span>
                      </td>
                      <td className="text-suave">{a.turmas.join(", ") || "Sem turma"}</td>
                      <td>{a.analise_geral_em ? <Etiqueta tom="sucesso">Escrita em {dataCurta(a.analise_geral_em)}</Etiqueta> : <span className="text-suave">—</span>}</td>
                      <td className="text-right">
                        <BotaoLink tamanho="mini" href={`/admin/alunos/?aluno=${a.id}`}>Acompanhar</BotaoLink>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Cartao>
          );
        }}
      </Estado>
    </Pagina>
  );
}

function DesempenhoDoAluno() {
  const aluno = useSearchParams().get("aluno") ?? "";
  return aluno ? <Acompanhamento aluno={aluno} /> : <BuscaDeAlunos />;
}

function Acompanhamento({ aluno }: { aluno: string }) {
  const router = useRouter();
  const parametros = useSearchParams();
  const simulado = parametros.get("simulado") ?? undefined;
  const desempenho = useDados(() => api.desempenho(aluno, simulado), [aluno, simulado]);
  const simulados = useDados(() => api.simuladosDoProfessor());
  const assuntos = useDados(() => (aluno ? api.devolutivaDoAluno(aluno).catch(() => null) : Promise.resolve(null)), [aluno]);
  const geral = useDados(() => api.analiseDoAluno(aluno).catch(() => null), [aluno]);
  // A análise do simulado que está na tela: o escolhido, ou o último que ele começou.
  const idDoSimulado = desempenho.dados?.simulado_id ?? null;
  const doSimulado = useDados(() => (idDoSimulado ? api.analiseDoAluno(aluno, idDoSimulado).catch(() => null) : Promise.resolve(null)), [aluno, idDoSimulado]);

  return (
    <Pagina titulo={desempenho.dados?.aluno ?? "Desempenho do aluno"} legenda="O que já fez do curso, como está em cada assunto e como foi em cada simulado, a qualquer momento, inclusive antes de fechar." voltar={{ href: "/admin/alunos/", rotulo: "Desempenho por aluno" }}>
      <>
          {geral.dados && <AnaliseGeral dados={geral.dados} aoAtualizar={geral.recarregar} />}
          <CursoDoAluno aluno={aluno} />
          {assuntos.dados && (
            <section className="flex flex-col gap-3" aria-labelledby="titulo-por-assunto">
              <TituloDeSecao>
                <span id="titulo-por-assunto">Por assunto</span>
              </TituloDeSecao>
              <div className="-mt-1">
                <ResumoDaDevolutiva dados={assuntos.dados} quem="O aluno" />
              </div>
              <PorAssunto dados={assuntos.dados} />
              {assuntos.dados.sem_assunto > 0 && (
                <p className="text-[13px] text-suave">
                  {assuntos.dados.sem_assunto === 1 ? "1 resposta ficou" : `${assuntos.dados.sem_assunto} respostas ficaram`} de fora desta leitura: {assuntos.dados.questoes_sem_assunto === 1 ? "a questão não tem" : "as questões não têm"} assunto.
                </p>
              )}
            </section>
          )}
          <TituloDeSecao>Nos simulados</TituloDeSecao>
          <Campo rotulo="Simulado" className="max-w-md">
            {(id) => (
              <select
                id={id}
                className="campo"
                value={simulado ?? ""}
                onChange={(e) => router.replace(`/admin/alunos/?aluno=${encodeURIComponent(aluno)}${e.target.value ? `&simulado=${e.target.value}` : ""}`)}
              >
                <option value="">O último que ele começou</option>
                {simulados.dados?.map((s) => (
                  <option key={s.simulado_id} value={s.simulado_id}>{s.titulo}</option>
                ))}
              </select>
            )}
          </Campo>
          <Estado {...desempenho} linhas={3}>
            {(d) =>
              !d.encontrou_dados ? (
                <Aviso tom="info">{d.mensagem}</Aviso>
              ) : (
                <>
                  <Cartao className="flex flex-col gap-5 p-6 sm:flex-row sm:items-center">
                    <Anel percentual={d.percentual ?? 0} tamanho={96} />
                    <div className="min-w-0">
                      <h2 className="text-lg font-semibold text-tinta">{d.simulado}</h2>
                      <p className="text-[15px] text-suave">{d.turmas?.join(", ")}</p>
                      <div className="mt-3 flex flex-wrap gap-2">
                        <Etiqueta tom="sucesso">{d.acertos} de {d.total_questoes} acertos</Etiqueta>
                        <Etiqueta tom="atencao">{plural(d.em_branco ?? 0, "em branco", "em branco")}</Etiqueta>
                        <Etiqueta>{d.entregue ? (d.entregue_automaticamente ? "Entregue quando o tempo acabou" : "Entregue") : "Ainda fazendo"}</Etiqueta>
                      </div>
                    </div>
                  </Cartao>

                  <TituloDeSecao>Questão a questão</TituloDeSecao>
                  <Cartao className="overflow-x-auto">
                    <table className="tabela min-w-[36rem]">
                      <thead>
                        <tr>
                          <th scope="col">Questão</th>
                          <th scope="col">Assunto</th>
                          <th scope="col">Marcou</th>
                          <th scope="col">Gabarito</th>
                          <th scope="col">Resultado</th>
                        </tr>
                      </thead>
                      <tbody>
                        {d.questoes?.map((q) => (
                          <tr key={q.questao_id}>
                            <td className="font-mono">Q{String(q.ordem).padStart(2, "0")}</td>
                            <td className="text-suave">{q.topico ?? "—"}</td>
                            <td className="font-semibold">{q.marcada ?? "—"}</td>
                            <td className="font-semibold">{q.gabarito}</td>
                            <td>{q.correta ? <Etiqueta tom="sucesso">Acertou</Etiqueta> : q.marcada ? <Etiqueta tom="erro">Errou</Etiqueta> : <Etiqueta tom="atencao">Em branco</Etiqueta>}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </Cartao>

                  {idDoSimulado && doSimulado.dados && (
                    <AnaliseDoSimuladoParaOProfessor aluno={aluno} simuladoId={idDoSimulado} dados={doSimulado.dados} aoMudar={doSimulado.setDados} />
                  )}

                  {d.recomendacoes && d.recomendacoes.length > 0 && (
                    <OndeRevisar analise={d.recomendacoes} legenda="Os assuntos em que o aluno mais errou e os vídeos que explicam cada um." />
                  )}
                </>
              )
            }
          </Estado>
      </>
    </Pagina>
  );
}
