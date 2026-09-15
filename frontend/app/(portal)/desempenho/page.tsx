"use client";

import Link from "next/link";
import { Cartao, Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { emBrasilia, porcento } from "@/lib/formato";
import { OndeRevisar } from "@/components/Resultado";

export default function MeuDesempenho() {
  const historico = useDados(() => api.historico());

  return (
    <Pagina titulo="Meu desempenho" legenda="Os simulados que você fez e que já fecharam.">
      <Estado {...historico} linhas={3}>
        {(h) =>
          h.simulados.length === 0 ? (
            <Vazio titulo="Nenhum resultado ainda">O desempenho aparece aqui quando um simulado que você fez fechar.</Vazio>
          ) : (
            <>
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
              {h.topicos.length > 0 ? (
                <OndeRevisar analise={h.topicos} />
              ) : (
                <>
                  <TituloDeSecao>Onde revisar</TituloDeSecao>
                  <Vazio titulo="Sem erros classificados">Os erros entram aqui quando as questões têm assunto.</Vazio>
                </>
              )}
            </>
          )
        }
      </Estado>
    </Pagina>
  );
}
