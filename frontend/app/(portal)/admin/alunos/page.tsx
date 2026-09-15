"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { OndeRevisar } from "@/components/Resultado";
import { Anel } from "@/components/Resultado";
import { Aviso, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { plural } from "@/lib/formato";

export default function PaginaDoDesempenho() {
  return (
    <Suspense>
      <DesempenhoDoAluno />
    </Suspense>
  );
}

function DesempenhoDoAluno() {
  const router = useRouter();
  const parametros = useSearchParams();
  const aluno = parametros.get("aluno") ?? "";
  const simulado = parametros.get("simulado") ?? undefined;
  const desempenho = useDados(() => api.desempenho(aluno, simulado), [aluno, simulado]);
  const simulados = useDados(() => api.simuladosDoProfessor());

  return (
    <Pagina titulo={desempenho.dados?.aluno ?? "Desempenho do aluno"} legenda="Como foi em cada simulado — a qualquer momento, inclusive antes de fechar." voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}>
      {!aluno ? (
        <Aviso tom="atencao">Abra o desempenho a partir da lista de alunos ou do ranking.</Aviso>
      ) : (
        <>
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
