"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { AssuntosEmDetalhe } from "@/components/AssuntosEmDetalhe";
import { Abas, Cartao, Estado, LinkDeTitulo, Numero, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Devolutiva, type Historico, type LinhaDaDevolutiva } from "@/lib/api";
import { emBrasilia, plural, porcento } from "@/lib/formato";

export default function PaginaDoDesempenho() {
  return (
    <Suspense>
      <MeuDesempenho />
    </Suspense>
  );
}

// A página era uma coluna só: todos os assuntos, depois tudo o que revisar, depois os simulados.
// Agora o aluno escolhe um assunto e vê, ao lado, os sub-assuntos dele e o que estudar em cada um.
type Aba = "assuntos" | "simulados";

function MeuDesempenho() {
  const router = useRouter();
  const parametros = useSearchParams();
  const aba: Aba = parametros.get("aba") === "simulados" ? "simulados" : "assuntos";
  const escolhido = Number(parametros.get("assunto")) || null;
  const dados = useDados(async () => {
    const [historico, devolutiva] = await Promise.all([api.historico(), api.minhaDevolutiva()]);
    return { historico, devolutiva };
  });
  // A aba e o assunto moram no endereço: voltar no navegador funciona.
  const ir = (nova: Aba, assunto?: number | null) =>
    router.replace(`/desempenho/${nova === "simulados" ? "?aba=simulados" : assunto ? `?assunto=${assunto}` : ""}`, { scroll: false });

  return (
    <Pagina titulo="Meu desempenho" legenda="Como você está indo em cada assunto, juntando as questões das aulas e os simulados com resultado liberado, e o que estudar em cada um.">
      <Estado {...dados} linhas={3}>
        {({ historico: h, devolutiva: d }) =>
          d.respostas === 0 && h.simulados.length === 0 ? (
            <Vazio titulo="Nada para mostrar ainda">Responda as questões das aulas ou faça um simulado: o seu desempenho por assunto aparece aqui.</Vazio>
          ) : (
            <>
              <Resumo d={d} h={h} />
              <Abas
                abas={[
                  { valor: "assuntos", rotulo: "Por assunto" },
                  { valor: "simulados", rotulo: h.simulados.length ? `Simulados (${h.simulados.length})` : "Simulados" },
                ]}
                atual={aba}
                aoTrocar={(nova) => ir(nova)}
              />
              {aba === "assuntos" &&
                (d.assuntos.length > 0 ? (
                  <AssuntosEmDetalhe d={d} escolhido={escolhido} aoEscolher={(id) => ir("assuntos", id)} />
                ) : (
                  <Vazio titulo="Suas questões ainda não têm assunto">Assim que o professor classificar as questões, o desempenho aparece separado por assunto.</Vazio>
                ))}
              {aba === "simulados" && <Simulados h={h} />}
            </>
          )
        }
      </Estado>
    </Pagina>
  );
}

/** Os pontos que o aluno estuda: o sub-assunto, ou o assunto que não tem nenhum. */
const pontos = (d: Devolutiva): LinhaDaDevolutiva[] => d.assuntos.flatMap((a) => (a.subassuntos.length ? a.subassuntos : [a]));

function Resumo({ d, h }: { d: Devolutiva; h: Historico }) {
  const temas = pontos(d);
  const atencao = temas.filter((t) => t.nivel === "ATENCAO").length;
  const media = h.simulados.length ? h.simulados.reduce((soma, s) => soma + s.percentual, 0) / h.simulados.length : null;
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <Numero rotulo="Questões respondidas" valor={String(d.respostas)} detalhe={d.respostas ? `${porcento(d.percentual ?? 0)} de acerto, entre aulas e simulados` : "nenhuma ainda"} />
      <Numero rotulo="Simulados" valor={String(h.simulados.length)} detalhe={media === null ? "nenhum com resultado" : `média de ${porcento(Math.round(media))}`} />
      <Numero rotulo="Pedem atenção" valor={String(atencao)} detalhe={temas.length ? `de ${plural(temas.length, "tema respondido", "temas respondidos")}` : "sem temas ainda"} />
    </div>
  );
}

function Simulados({ h }: { h: Historico }) {
  if (!h.simulados.length) {
    return <Vazio titulo="Nenhum simulado com resultado ainda">Depois que um simulado fecha e o professor libera o resultado, ele aparece aqui, com a sua nota e a sua posição.</Vazio>;
  }
  return (
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
                <LinkDeTitulo href={`/simulados/resultado/?id=${s.simulado_id}`}>{s.titulo}</LinkDeTitulo>
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
  );
}
