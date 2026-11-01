"use client";

import { KeyRound, UserMinus } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AnaliseDoSimuladoParaOProfessor, AnaliseGeral } from "@/components/Analise";
import { AssuntosEmDetalhe } from "@/components/AssuntosEmDetalhe";
import { CursoDoAluno } from "@/components/CursoDoAluno";
import { Matricular, type Segredo } from "@/components/Matricular";
import { Menu } from "@/components/Menu";
import { AssuntosAoLadoDosColegas, EvolucaoNosSimulados, NumerosDoPanorama } from "@/components/PanoramaDoAluno";
import { OndeRevisar } from "@/components/Resultado";
import { Anel } from "@/components/Resultado";
import { Abas, Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, LinkDeTitulo, Pagina, Progresso, SegredoUmaVez, TituloDeSecao, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type AlunoNaBusca } from "@/lib/api";
import { dataCurta, emBrasilia, haQuantoTempo, plural } from "@/lib/formato";

export default function PaginaDoDesempenho() {
  return (
    <Suspense>
      <DesempenhoDoAluno />
    </Suspense>
  );
}

const semAcento = (texto: string) => texto.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/** O filtro da turma na URL: o id da turma, "sem" para quem não tem nenhuma, ou nada para todos. */
const SEM_TURMA = "sem";

/**
 * Todos os alunos da plataforma, com ou sem turma. Com uma turma no filtro, a mesma tabela vira a
 * tela da turma: matricular, tirar da turma e quanto cada um já fez do curso dela.
 */
function BuscaDeAlunos() {
  const router = useRouter();
  const filtro = useSearchParams().get("turma") ?? "";
  const turmaId = filtro && filtro !== SEM_TURMA ? Number(filtro) : null;
  const alunos = useDados(() => api.alunos());
  const turmas = useDados(() => api.turmas());
  // O progresso vem à parte e só com uma turma: se falhar, a lista de alunos continua de pé.
  const progresso = useDados(() => (turmaId ? api.progressoDaTurma(turmaId).catch(() => null) : Promise.resolve(null)), [turmaId]);
  const noCurso = new Map(progresso.dados?.alunos.map((a) => [a.id, a]));
  const turma = turmas.dados?.find((t) => t.id === turmaId);
  const [busca, setBusca] = useState("");
  const [segredo, setSegredo] = useState<Segredo | null>(null);
  const [matriculando, setMatriculando] = useState(false);
  const [erro, setErro] = useState("");
  const [dialogo, confirmar] = useConfirmar();
  const termo = semAcento(busca.trim());

  const naTurma = (a: AlunoNaBusca) => (filtro === SEM_TURMA ? !a.turmas.length : turma ? a.turmas.includes(turma.nome) : !turmaId);
  const naBusca = (a: AlunoNaBusca) => !termo || semAcento(a.nome).includes(termo) || semAcento(a.email).includes(termo);
  const escolher = (valor: string) => router.replace(valor ? `/admin/alunos/?turma=${valor}` : "/admin/alunos/", { scroll: false });

  async function redefinir(aluno: AlunoNaBusca) {
    const sim = await confirmar({
      titulo: `Nova senha para ${aluno.nome}?`,
      texto: "A senha atual deixa de valer e as sessões abertas caem. A nova é temporária: o aluno troca no primeiro acesso.",
      confirmar: "Gerar nova senha",
    });
    if (!sim) return;
    setErro("");
    try {
      const r = await api.redefinirSenha(aluno.id);
      setSegredo({ titulo: `Senha temporária de ${aluno.nome}`, valor: r.senha_temporaria, para: aluno.email });
      void alunos.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  async function tirar(aluno: AlunoNaBusca) {
    if (!turma) return;
    const sim = await confirmar({
      titulo: `Tirar ${aluno.nome} da ${turma.nome}?`,
      texto: "O aluno perde o acesso ao curso e aos simulados desta turma na hora. A conta continua, e as provas que já fez ficam no histórico.",
      confirmar: "Tirar da turma",
      perigo: true,
    });
    if (!sim) return;
    setErro("");
    try {
      await api.desmatricular(turma.id, aluno.id);
      void alunos.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <Pagina
      titulo={turma ? `Alunos · ${turma.nome}` : "Alunos"}
      legenda={
        turma
          ? `Quem está matriculado vê o curso publicado e os simulados desta turma.${progresso.dados ? ` Hoje o curso dela tem ${plural(progresso.dados.total, "item publicado", "itens publicados")}.` : ""}`
          : "Todos os alunos da plataforma, inclusive os que não estão em nenhuma turma. Abra um para ver o que ele já fez do curso, como está em cada assunto, os simulados e a análise geral."
      }
      acoes={
        turma && (
          <>
            <BotaoLink href={`/admin/turmas/desempenho/?turma=${turma.id}`}>Desempenho da turma</BotaoLink>
            <Botao variante="primario" onClick={() => setMatriculando(true)}>Matricular aluno</Botao>
          </>
        )
      }
    >
      {dialogo}
      {matriculando && turma && (
        <Matricular turma={turma.id} aoMatricular={(r) => { if (r) setSegredo(r); void alunos.recarregar(); }} aoFechar={() => setMatriculando(false)} />
      )}
      {segredo && (
        <SegredoUmaVez titulo={segredo.titulo} valor={segredo.valor}>
          Passe para {segredo.para} por um canal seguro. Ela aparece só agora e o aluno troca no primeiro acesso.
        </SegredoUmaVez>
      )}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <div className="flex flex-wrap items-end gap-3">
        <Campo rotulo="Aluno" className="w-full max-w-md">
          {(id) => <input id={id} type="search" autoFocus value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Nome ou e-mail" className="campo" />}
        </Campo>
        <Campo rotulo="Turma" className="w-full sm:w-64">
          {(id) => (
            <select id={id} value={filtro} onChange={(e) => escolher(e.target.value)} className="campo">
              <option value="">Todas</option>
              {turmas.dados?.map((t) => (
                <option key={t.id} value={String(t.id)}>{t.nome}</option>
              ))}
              <option value={SEM_TURMA}>Sem turma</option>
            </select>
          )}
        </Campo>
      </div>
      <Estado {...alunos} linhas={4} forma="tabela">
        {(todos) => {
          const daTurma = todos.filter(naTurma);
          const lista = daTurma.filter(naBusca);
          if (!todos.length) return <Vazio titulo="Nenhum aluno cadastrado">Os alunos entram matriculados numa turma: escolha a turma no filtro e use &quot;Matricular aluno&quot;.</Vazio>;
          if (turmaId && !turma) return turmas.carregando ? null : <Aviso tom="atencao">Esta turma não existe mais. Escolha outra no filtro.</Aviso>;
          if (!lista.length) {
            return termo ? (
              <Vazio titulo="Ninguém com esse nome">Confira a grafia, ou procure pelo e-mail.</Vazio>
            ) : turma ? (
              <Vazio titulo="Nenhum aluno nesta turma">Matricule o primeiro em &quot;Matricular aluno&quot;.</Vazio>
            ) : (
              <Vazio titulo="Ninguém sem turma">Todo aluno da plataforma está matriculado em alguma turma.</Vazio>
            );
          }
          const semTurma = todos.filter((a) => !a.turmas.length).length;
          return (
            <>
              <p className="text-sm text-suave">
                {plural(lista.length, "aluno", "alunos")}
                {termo ? ` de ${daTurma.length}` : ""}
                {!filtro && semTurma ? ` · ${plural(semTurma, "sem turma", "sem turma")}` : ""}
              </p>
              <Cartao className="overflow-x-auto">
                <table className="tabela min-w-[60rem]">
                  <thead>
                    <tr>
                      <th scope="col">Aluno</th>
                      {!turma && <th scope="col">Turmas</th>}
                      {turma && <th scope="col">No curso</th>}
                      <th scope="col">Cadastro</th>
                      {!turma && <th scope="col">Última atividade</th>}
                      <th scope="col" className="text-right">Aulas</th>
                      <th scope="col" className="text-right">Questões</th>
                      <th scope="col" className="text-right">Simulados</th>
                      <th scope="col">Acesso</th>
                      <th scope="col">Análise geral</th>
                      <th scope="col"><span className="sr-only">Ações</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {lista.map((a) => (
                      <tr key={a.id}>
                        <td>
                          <LinkDeTitulo href={`/admin/alunos/?aluno=${a.id}`}>{a.nome}</LinkDeTitulo>
                          <span className="block whitespace-nowrap text-[13px] text-suave">{a.email}</span>
                        </td>
                        {!turma && <td>{a.turmas.length ? a.turmas.join(", ") : <Etiqueta tom="atencao">Sem turma</Etiqueta>}</td>}
                        {turma && (
                          <td className="min-w-44">
                            <NoCurso aluno={noCurso.get(a.id)} carregando={progresso.carregando} />
                          </td>
                        )}
                        <td className="whitespace-nowrap text-suave">{dataCurta(a.cadastrado_em) || "—"}</td>
                        {!turma && <td className="whitespace-nowrap text-suave">{a.ultima_atividade ? dataCurta(a.ultima_atividade) : "Nunca"}</td>}
                        <td className="text-right tabular-nums">{a.aulas_concluidas}</td>
                        <td className="text-right tabular-nums">{a.questoes_respondidas}</td>
                        <td className="text-right tabular-nums">{a.simulados_feitos}</td>
                        <td>{a.senha_temporaria ? <Etiqueta tom="atencao">Senha temporária</Etiqueta> : <Etiqueta tom="sucesso">Ativo</Etiqueta>}</td>
                        <td>{a.analise_geral_em ? <Etiqueta tom="sucesso">Escrita em {dataCurta(a.analise_geral_em)}</Etiqueta> : <span className="text-suave">—</span>}</td>
                        <td>
                          <div className="flex items-center justify-end gap-1">
                            <BotaoLink tamanho="mini" href={`/admin/alunos/?aluno=${a.id}`}>Acompanhar</BotaoLink>
                            <Menu
                              rotulo={`Mais ações de ${a.nome}`}
                              itens={[
                                { rotulo: "Gerar nova senha", icone: KeyRound, aoEscolher: () => void redefinir(a) },
                                ...(turma ? ["divisor" as const, { rotulo: "Tirar da turma", icone: UserMinus, perigo: true, aoEscolher: () => void tirar(a) }] : []),
                              ]}
                            />
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Cartao>
            </>
          );
        }}
      </Estado>
    </Pagina>
  );
}

/** Quanto do curso da turma o aluno já fez e quando apareceu pela última vez. */
function NoCurso({ aluno, carregando }: { aluno?: { nome: string; concluidos: number; total: number; ultima_atividade: string | null }; carregando: boolean }) {
  if (!aluno) return <span className="text-suave">{carregando ? "…" : "—"}</span>;
  if (aluno.total === 0) return <span className="text-suave">Sem itens publicados</span>;
  return (
    <div className="flex flex-col gap-1">
      <span className="flex items-baseline justify-between gap-2">
        <span className="font-medium tabular-nums text-tinta">{aluno.concluidos} de {aluno.total}</span>
        <span className="text-[13px] tabular-nums text-suave">{Math.round((aluno.concluidos / aluno.total) * 100)}%</span>
      </span>
      <Progresso feitos={aluno.concluidos} total={aluno.total} rotulo={`Progresso de ${aluno.nome} no curso`} />
      <span className="text-[13px] text-suave" title={aluno.ultima_atividade ? emBrasilia(aluno.ultima_atividade) : undefined}>
        {aluno.ultima_atividade ? `Última atividade ${haQuantoTempo(aluno.ultima_atividade)}` : "Ainda não começou"}
      </span>
    </div>
  );
}

function DesempenhoDoAluno() {
  const aluno = useSearchParams().get("aluno") ?? "";
  return aluno ? <Acompanhamento aluno={aluno} /> : <BuscaDeAlunos />;
}

// O acompanhamento cabia numa página só e pedia rolagem demais: cada pergunta do professor tem a sua aba.
type Aba = "geral" | "assuntos" | "curso" | "simulados";
const ABAS: { valor: Aba; rotulo: string }[] = [
  { valor: "geral", rotulo: "Visão geral" },
  { valor: "assuntos", rotulo: "Por assunto" },
  { valor: "curso", rotulo: "No curso" },
  { valor: "simulados", rotulo: "Simulados" },
];

function Acompanhamento({ aluno }: { aluno: string }) {
  const router = useRouter();
  const parametros = useSearchParams();
  const simulado = parametros.get("simulado") ?? undefined;
  const pedida = parametros.get("aba") as Aba | null;
  // Quem chega com um simulado escolhido quer ver o simulado.
  const aba: Aba = pedida && ABAS.some((a) => a.valor === pedida) ? pedida : simulado ? "simulados" : "geral";
  const assunto = Number(parametros.get("assunto")) || null;
  const endereco = (nova: Aba, prova?: string, tema?: number) =>
    `/admin/alunos/?aluno=${encodeURIComponent(aluno)}${nova === "geral" ? "" : `&aba=${nova}`}${prova ? `&simulado=${prova}` : ""}${tema ? `&assunto=${tema}` : ""}`;
  const desempenho = useDados(() => api.desempenho(aluno, simulado), [aluno, simulado]);
  const simulados = useDados(() => api.simuladosDoProfessor());
  const assuntos = useDados(() => api.devolutivaDoAluno(aluno), [aluno]);
  const geral = useDados(() => api.analiseDoAluno(aluno).catch(() => null), [aluno]);
  const panorama = useDados(() => api.panorama(aluno), [aluno]);
  // A análise do simulado que está na tela: o escolhido, ou o último que ele começou.
  const idDoSimulado = desempenho.dados?.simulado_id ?? null;
  const doSimulado = useDados(() => (idDoSimulado ? api.analiseDoAluno(aluno, idDoSimulado).catch(() => null) : Promise.resolve(null)), [aluno, idDoSimulado]);

  return (
    <Pagina titulo={panorama.dados?.aluno ?? desempenho.dados?.aluno ?? "Desempenho do aluno"} legenda={panorama.dados?.turmas.length ? panorama.dados.turmas.join(", ") : "A análise geral, como está em cada assunto, o que já fez do curso e como foi em cada simulado."} voltar={{ href: "/admin/alunos/", rotulo: "Alunos" }}>
      <Abas abas={ABAS} atual={aba} aoTrocar={(nova) => router.replace(endereco(nova, nova === "simulados" ? simulado : undefined), { scroll: false })} />
      {aba === "curso" && <CursoDoAluno aluno={aluno} />}
      {aba === "geral" && (
        <Estado {...panorama} linhas={4}>
          {(p) => (
            <>
              <NumerosDoPanorama p={p} />
              <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
                {geral.dados && (
                  <AnaliseGeral
                    dados={geral.dados}
                    novas={p.respostas_novas}
                    aoAtualizar={() => {
                      geral.recarregar();
                      panorama.recarregar();
                    }}
                  />
                )}
                <section className="flex flex-col gap-3" aria-labelledby="titulo-numeros">
                  <TituloDeSecao>
                    <span id="titulo-numeros">Os números por trás</span>
                  </TituloDeSecao>
                  <EvolucaoNosSimulados simulados={p.simulados} quem="o aluno" />
                  <AssuntosAoLadoDosColegas
                    p={p}
                    limite={6}
                    verTodos={<Botao tamanho="mini" onClick={() => router.replace(endereco("assuntos"), { scroll: false })}>Ver todos os assuntos</Botao>}
                  />
                </section>
              </div>
            </>
          )}
        </Estado>
      )}
      {aba === "assuntos" && (
        <Estado {...assuntos} linhas={4}>
          {(d) =>
            d.assuntos.length === 0 ? (
              <Vazio titulo="Sem respostas por assunto ainda">Assim que o aluno responder questões classificadas, o desempenho dele aparece aqui, separado por assunto.</Vazio>
            ) : (
              <AssuntosEmDetalhe d={d} escolhido={assunto} quem="professor" aoEscolher={(id) => router.replace(endereco("assuntos", undefined, id), { scroll: false })} />
            )
          }
        </Estado>
      )}
      {aba === "simulados" && (
        <>
          <Campo rotulo="Simulado" className="max-w-md">
            {(id) => (
              <select
                id={id}
                className="campo"
                value={simulado ?? ""}
                onChange={(e) => router.replace(endereco("simulados", e.target.value || undefined), { scroll: false })}
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

                  {idDoSimulado && doSimulado.dados && (
                    <AnaliseDoSimuladoParaOProfessor aluno={aluno} simuladoId={idDoSimulado} dados={doSimulado.dados} aoMudar={doSimulado.setDados} />
                  )}

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

                  {d.recomendacoes && d.recomendacoes.length > 0 && (
                    <OndeRevisar analise={d.recomendacoes} legenda="Os assuntos em que o aluno mais errou e os vídeos que explicam cada um." />
                  )}
                </>
              )
            }
          </Estado>
        </>
      )}
    </Pagina>
  );
}
