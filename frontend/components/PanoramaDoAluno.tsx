"use client";

// Os números por trás da análise geral, sem IA: quanto o aluno respondeu e acertou, como foi em
// cada prova ao lado de quem fez, e cada assunto ao lado dos colegas de turma. É o que o professor
// tem na frente quando lê a análise, e o que vai junto na página para imprimir.

import { NIVEL } from "@/components/Devolutiva";
import { Cartao, Numero } from "@/components/ui";
import type { AssuntoNoPanorama, Panorama, SimuladoNoPanorama } from "@/lib/api";
import { dataCurta, haQuantoTempo, plural, porcento } from "@/lib/formato";

export function NumerosDoPanorama({ p }: { p: Panorama }) {
  const media = p.simulados.length ? p.simulados.reduce((soma, s) => soma + s.percentual, 0) / p.simulados.length : null;
  const deQuemFez = p.simulados.length ? p.simulados.reduce((soma, s) => soma + s.media, 0) / p.simulados.length : null;
  const { curso } = p;
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Numero
        rotulo="Questões respondidas"
        valor={String(p.respostas)}
        detalhe={p.respostas ? `${porcento(p.percentual ?? 0)} de acerto · ${p.da_aula} de aula, ${p.de_simulado} de simulado` : "nenhuma ainda"}
      />
      <Numero
        rotulo="Em branco"
        valor={String(p.em_branco)}
        detalhe={
          !p.respostas
            ? "sem respostas"
            : p.em_branco > 0 && p.acerto_no_que_respondeu !== null
              ? `no que respondeu, ${porcento(p.acerto_no_que_respondeu)} de acerto (${p.acertos} de ${p.acertos + p.erros})`
              : `${plural(p.erros, "marcada errada", "marcadas erradas")} · ${plural(p.acertos, "acerto", "acertos")}`
        }
      />
      <Numero
        rotulo="Simulados"
        valor={String(p.simulados.length)}
        detalhe={media === null ? "nenhum entregue" : `média de ${porcento(Math.round(media))} · quem fez: ${porcento(Math.round(deQuemFez ?? 0))}`}
      />
      <Numero
        rotulo="No curso"
        valor={curso.total ? porcento(Math.round((100 * curso.concluidos) / curso.total)) : "—"}
        detalhe={curso.total ? `${curso.concluidos} de ${curso.total} · ${curso.ultima_atividade ? `última atividade ${haQuantoTempo(curso.ultima_atividade)}` : "ainda não começou"}` : "nada publicado para as turmas dele"}
      />
    </div>
  );
}

function Legenda({ dele, outro }: { dele: string; outro: string }) {
  return (
    <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-suave">
      <span className="flex items-center gap-1.5"><span className="h-2 w-4 rounded-full bg-acento" aria-hidden="true" />{dele}</span>
      <span className="flex items-center gap-1.5"><span className="h-0.5 w-4 bg-tinta" aria-hidden="true" />{outro}</span>
    </p>
  );
}

/** Uma coluna por prova, da mais antiga para a mais nova, com a média de quem fez riscada em cima. */
export function EvolucaoNosSimulados({ simulados, quem }: { simulados: SimuladoNoPanorama[]; quem: string }) {
  return (
    <Cartao como="div" className="flex break-inside-avoid flex-col gap-3 p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-tinta">Nos simulados, do mais antigo para o mais novo</h3>
        {simulados.length > 0 && <Legenda dele={quem} outro="média de quem fez" />}
      </div>
      {simulados.length === 0 ? (
        <p className="text-[15px] text-suave">Ainda não entregou nenhum simulado.</p>
      ) : (
        <ol className="flex items-stretch gap-2 overflow-x-auto pb-1" aria-label="Acerto em cada simulado">
          {simulados.map((s) => (
            <li
              key={s.simulado_id}
              className="flex min-w-[4.5rem] max-w-28 flex-1 flex-col items-center gap-1.5"
              title={`${s.titulo}: ${s.acertos} de ${s.total} (${plural(s.em_branco, "em branco", "em branco")}), ${s.posicao}º de ${s.participantes}; média de quem fez ${porcento(Math.round(s.media))}`}
            >
              <span className="text-sm font-semibold tabular-nums text-tinta">{porcento(Math.round(s.percentual))}</span>
              <span className="relative flex h-28 w-full items-end justify-center rounded-md bg-gelo/60" aria-hidden="true">
                <span className="w-3/5 rounded-t-md bg-acento" style={{ height: `${Math.max(s.percentual, 2)}%` }} />
                <span className="absolute inset-x-1 h-0.5 bg-tinta" style={{ bottom: `calc(${Math.min(s.media, 99)}% - 1px)` }} />
              </span>
              <span className="line-clamp-2 text-center text-[12px] leading-tight text-tinta-2">{s.titulo}</span>
              <span className="text-center text-[12px] tabular-nums leading-tight text-suave">
                {s.posicao}º de {s.participantes}
                {!s.encerrado && " (parcial)"}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Cartao>
  );
}

function LinhaDoAssunto({ a }: { a: AssuntoNoPanorama }) {
  return (
    <li className="grid break-inside-avoid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_auto]">
      <span className="truncate text-[15px] font-medium text-tinta">{a.nome}</span>
      <span className="relative col-span-2 block h-2.5 rounded-full bg-gelo sm:col-span-1 sm:col-start-2 sm:row-start-1" aria-hidden="true">
        <span className={`block h-full rounded-full ${NIVEL[a.nivel].barra}`} style={{ width: `${Math.max(a.percentual, 2)}%` }} />
        {a.colegas !== null && <span className="absolute -top-1 h-[18px] w-0.5 bg-tinta" style={{ left: `calc(${Math.min(a.colegas, 99.5)}% - 1px)` }} />}
      </span>
      <span className="col-start-2 row-start-1 text-right text-[13px] tabular-nums text-suave sm:col-start-3">
        <span className="font-semibold text-tinta">{a.acertos} de {a.respondidas}</span>
        {a.em_branco > 0 && <> · {a.em_branco} em branco</>}
        {a.colegas !== null && <> · colegas {porcento(Math.round(a.colegas))}</>}
      </span>
    </li>
  );
}

/**
 * O acerto em cada assunto, do que mais pede atenção para o que vai melhor, com o acerto dos
 * colegas nas mesmas questões. `limite`: quantos mostrar; o resto fica para a aba dos assuntos.
 */
export function AssuntosAoLadoDosColegas({ p, limite, verTodos }: { p: Panorama; limite?: number; verTodos?: React.ReactNode }) {
  const assuntos = limite ? p.por_assunto.slice(0, limite) : p.por_assunto;
  const escondidos = p.por_assunto.length - assuntos.length;
  return (
    <Cartao como="div" className="flex flex-col gap-3 p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-tinta">Por assunto{limite && escondidos > 0 ? ": os que mais pedem atenção" : ""}</h3>
        {p.colegas > 0 && <Legenda dele="o aluno" outro="colegas de turma" />}
      </div>
      {assuntos.length === 0 ? (
        <p className="text-[15px] text-suave">As questões que ele respondeu ainda não têm assunto.</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {assuntos.map((a) => (
            <LinhaDoAssunto key={a.id} a={a} />
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[13px] text-suave">
          {p.colegas > 0
            ? `Acertos sobre as questões que ele respondeu; as em branco vêm ao lado. A barra e a comparação com ${plural(p.colegas, "colega", "colegas")} de turma, nas mesmas questões, contam o em branco como erro.`
            : "Acertos sobre as questões que ele respondeu; as em branco vêm ao lado. Nenhum colega de turma respondeu estas questões ainda."}
        </p>
        {escondidos > 0 && verTodos}
      </div>
    </Cartao>
  );
}

/** As provas em tabela: é o que acompanha o gráfico na página para imprimir. */
export function TabelaDeSimulados({ simulados }: { simulados: SimuladoNoPanorama[] }) {
  if (!simulados.length) return null;
  return (
    <table className="tabela">
      <thead>
        <tr>
          <th scope="col">Simulado</th>
          <th scope="col">Fechou</th>
          <th scope="col">Acertos</th>
          <th scope="col">Em branco</th>
          <th scope="col">Média de quem fez</th>
          <th scope="col">Posição</th>
        </tr>
      </thead>
      <tbody>
        {simulados.map((s) => (
          <tr key={s.simulado_id} className="break-inside-avoid">
            <td className="font-medium text-tinta">{s.titulo}</td>
            <td className="whitespace-nowrap text-suave">{dataCurta(s.fechou_em)}</td>
            <td className="whitespace-nowrap tabular-nums">{s.acertos} de {s.total} ({porcento(Math.round(s.percentual))})</td>
            <td className="tabular-nums">{s.em_branco}</td>
            <td className="tabular-nums">{porcento(Math.round(s.media))}</td>
            <td className="whitespace-nowrap tabular-nums">{s.posicao}º de {s.participantes}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
