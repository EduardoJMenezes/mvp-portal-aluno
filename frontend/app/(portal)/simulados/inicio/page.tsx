"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";
import { Aviso, Botao, BotaoLink, Cartao, Estado, Pagina } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";

export default function PaginaAntesDeComecar() {
  return (
    <Suspense>
      <AntesDeComecar />
    </Suspense>
  );
}

// Monta com os dados da lista, de propósito: abrir /aluno/simulados/{id} já é
// começar, e é ali que o tempo passa a contar no backend.
function AntesDeComecar() {
  const router = useRouter();
  const id = Number(useSearchParams().get("id"));
  const lista = useDados(() => api.simulados());
  const simulado = lista.dados?.find((s) => s.simulado_id === id);

  useEffect(() => {
    if (simulado?.minha_prova?.iniciada && !simulado.minha_prova.entregue) router.replace(`/simulados/prova/?id=${id}`);
  }, [simulado, id, router]);

  return (
    <Pagina titulo={simulado?.titulo ?? "Simulado"} voltar={{ href: "/simulados/", rotulo: "Simulados" }} estreita>
      <Estado {...lista} linhas={2}>
        {() =>
          !simulado ? (
            <Aviso tom="atencao">Este simulado não está disponível para a sua turma.</Aviso>
          ) : simulado.situacao !== "ABERTO" || simulado.minha_prova?.entregue ? (
            <Aviso tom="info">
              {simulado.situacao === "AGENDADO"
                ? `A prova abre ${emBrasilia(simulado.abre_em)}.`
                : simulado.minha_prova?.entregue
                  ? `Prova entregue. O resultado sai ${emBrasilia(simulado.fecha_em)}.`
                  : "Este simulado já fechou."}
            </Aviso>
          ) : (
            <Cartao className="flex flex-col gap-5 p-6">
              <dl className="grid grid-cols-3 gap-3 text-center">
                <div className="rounded-cartao bg-canvas px-3 py-3">
                  <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Questões</dt>
                  <dd className="mt-1 text-2xl font-semibold tabular-nums text-tinta">{simulado.total_questoes}</dd>
                </div>
                <div className="rounded-cartao bg-canvas px-3 py-3">
                  <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Tempo</dt>
                  <dd className="mt-1 text-2xl font-semibold tabular-nums text-tinta">{simulado.duracao_minutos} min</dd>
                </div>
                <div className="rounded-cartao bg-canvas px-3 py-3">
                  <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Fecha</dt>
                  <dd className="mt-1 text-sm font-semibold text-tinta">{emBrasilia(simulado.fecha_em)}</dd>
                </div>
              </dl>

              <ul className="flex flex-col gap-2.5 text-[15px] text-tinta-2">
                <li className="flex gap-2"><span aria-hidden="true" className="text-acento">●</span> O tempo de {simulado.duracao_minutos} minutos começa quando você clicar em começar — e não para se você sair da página.</li>
                <li className="flex gap-2"><span aria-hidden="true" className="text-acento">●</span> Cada resposta é salva na hora. Se o tempo acabar, a prova é entregue com o que estiver marcado.</li>
                <li className="flex gap-2"><span aria-hidden="true" className="text-acento">●</span> Questão em branco conta como erro.</li>
                <li className="flex gap-2"><span aria-hidden="true" className="text-acento">●</span> O resultado, com gabarito e resoluções, sai quando o simulado fechar.</li>
              </ul>

              <div className="flex flex-wrap gap-2">
                <Botao variante="primario" onClick={() => router.push(`/simulados/prova/?id=${simulado.simulado_id}`)}>
                  Começar agora · {plural(simulado.total_questoes, "questão", "questões")}
                </Botao>
                <BotaoLink href="/simulados/">Voltar depois</BotaoLink>
              </div>
            </Cartao>
          )
        }
      </Estado>
    </Pagina>
  );
}
