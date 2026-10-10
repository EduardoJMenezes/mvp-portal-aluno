"use client";

import Link from "next/link";
import { PorAssunto, ResumoDaDevolutiva } from "@/components/Devolutiva";
import { OndeRevisar } from "@/components/Resultado";
import { Cartao, Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { emBrasilia, porcento } from "@/lib/formato";

export default function MeuDesempenho() {
  const dados = useDados(async () => {
    const [historico, devolutiva] = await Promise.all([api.historico(), api.minhaDevolutiva()]);
    return { historico, devolutiva };
  });

  return (
    <Pagina titulo="Meu desempenho" legenda="Como você está indo em cada assunto, juntando as questões das aulas e os simulados que já fecharam.">
      <Estado {...dados} linhas={3}>
        {({ historico: h, devolutiva: d }) =>
          d.respostas === 0 && h.simulados.length === 0 ? (
            <Vazio titulo="Nada para mostrar ainda">Responda as questões das aulas ou faça um simulado: o seu desempenho por assunto aparece aqui.</Vazio>
          ) : (
            <>
              <section className="flex flex-col gap-3" aria-labelledby="titulo-por-assunto">
                <TituloDeSecao>
                  <span id="titulo-por-assunto">Por assunto</span>
                </TituloDeSecao>
                <div className="-mt-1">
                  <ResumoDaDevolutiva dados={d} quem="Você" />
                </div>
                {d.assuntos.length > 0 ? (
                  <PorAssunto dados={d} />
                ) : (
                  d.respostas > 0 && <Vazio titulo="Suas questões ainda não têm assunto">Assim que o professor classificar as questões, o desempenho aparece separado por assunto.</Vazio>
                )}
              </section>

              {d.onde_revisar.length > 0 && <OndeRevisar analise={d.onde_revisar} legenda="Os pontos em que você mais errou, com o que há no seu curso sobre cada um: o que assistir, ler, praticar e rever." />}

              {h.simulados.length > 0 && (
                <section className="flex flex-col gap-3" aria-labelledby="titulo-simulados">
                  <TituloDeSecao>
                    <span id="titulo-simulados">Simulados</span>
                  </TituloDeSecao>
                  <Cartao className="overflow-x-auto">
                    <table className="tabela min-w-[36rem]">
                      <thead>
                        <tr>
                          <th scope="col">Simulado</th>
                          <th scope="col">Fechou</th>
                          <th scope="col">Acertos</th>
                          <th scope="col" className="w-2/5">Nota</th>
                          <th scope="col">Posição</th>
                        </tr>
                      </thead>
                      <tbody>
                        {h.simulados.map((s) => (
                          <tr key={s.simulado_id}>
                            <td>
                              <Link href={`/simulados/resultado/?id=${s.simulado_id}`} className="font-semibold text-acento hover:underline">
                                {s.titulo}
                              </Link>
                            </td>
                            <td className="whitespace-nowrap text-suave">{emBrasilia(s.fechou_em)}</td>
                            <td className="whitespace-nowrap">{s.acertos} de {s.total}</td>
                            <td>
                              <div className="flex items-center gap-2">
                                <div className="h-2 flex-1 overflow-hidden rounded-full bg-lilas" aria-hidden="true">
                                  <div className="h-full rounded-full bg-acento" style={{ width: `${s.percentual}%` }} />
                                </div>
                                <span className="w-14 text-right font-semibold">{porcento(s.percentual)}</span>
                              </div>
                            </td>
                            <td className="whitespace-nowrap">{s.posicao}º de {s.participantes}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </Cartao>
                </section>
              )}
            </>
          )
        }
      </Estado>
    </Pagina>
  );
}
