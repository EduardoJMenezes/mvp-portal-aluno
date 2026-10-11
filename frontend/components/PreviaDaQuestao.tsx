"use client";

// A questão como o professor a confere: o enunciado, as alternativas com o gabarito marcado e a
// resolução. Serve a quem monta o curso (a linha de questão aberta ao lado) e a quem escolhe uma
// questão do banco. Não é a tela do aluno: aqui o gabarito aparece de saída.

import { useState } from "react";
import { TelaDoVideo } from "@/components/PreviaDoVideo";
import { Aviso, Carregamento, Esqueleto, Etiqueta, Revelar } from "@/components/ui";
import { api, useDados, type QuestaoDetalhada } from "@/lib/api";
import { DIFICULDADE, LETRAS } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

/** As alternativas, com a certa em verde e, quando há, o comentário de cada uma. */
export function AlternativasDaQuestao({ alternativas, gabarito, comentarios }: { alternativas: Record<string, string>; gabarito?: string | null; comentarios?: Record<string, string> }) {
  return (
    <ul className="flex flex-col gap-1.5">
      {LETRAS.filter((letra) => alternativas[letra]?.trim()).map((letra) => {
        const certa = gabarito === letra;
        return (
          <li key={letra} className={`flex items-start gap-3 rounded-cartao border px-3 py-2 ${certa ? "border-sucesso-borda bg-sucesso-fundo" : "border-borda bg-papel"}`}>
            <span className={`mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold ${certa ? "border-sucesso bg-sucesso text-white" : "border-borda-campo text-tinta-2"}`}>{letra}</span>
            <div className="min-w-0 flex-1">
              <TextoFormatado texto={alternativas[letra]} compacto />
              {comentarios?.[letra] && <p className="mt-1 border-t border-borda pt-1 text-[13px] text-suave">{comentarios[letra]}</p>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** A resolução: o texto comentado e o vídeo, que só é carregado se o professor pedir para ver. */
export function ResolucaoDaQuestao({ detalhe }: { detalhe: QuestaoDetalhada }) {
  const [vendo, setVendo] = useState(false);
  return (
    <>
      {detalhe.resolucao_comentada?.trim() ? (
        <div className="rounded-md bg-lilas p-3">
          <p className="mb-1 text-[13px] font-semibold text-acento-forte">Resolução comentada</p>
          <TextoFormatado texto={detalhe.resolucao_comentada} compacto />
        </div>
      ) : (
        <p className="text-[13px] text-suave">Sem resolução comentada.</p>
      )}
      {detalhe.resolucao ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
            <p className="min-w-0 text-[13px] text-suave">Vídeo de resolução: {detalhe.resolucao.titulo}</p>
            <Revelar aberto={vendo} aoAlternar={() => setVendo(!vendo)} className="text-[13px]">{vendo ? "Recolher o vídeo" : "Ver o vídeo"}</Revelar>
          </div>
          {vendo && <TelaDoVideo video={{ vimeo_id: detalhe.resolucao.vimeo_id, titulo: detalhe.resolucao.titulo }} className="surge" />}
        </div>
      ) : (
        <p className="text-[13px] text-suave">Sem vídeo de resolução.</p>
      )}
    </>
  );
}

/** O que fica no lugar da resolução enquanto ela não chega. */
export function EsperandoAResolucao() {
  return (
    <Carregamento rotulo="Lendo a resolução" className="flex flex-col gap-2">
      <Esqueleto className="h-3 w-28" />
      <Esqueleto className="h-4 w-4/5" />
    </Carregamento>
  );
}

/** A questão inteira, lida pelo número. Quem usa troca a `key` ao trocar de questão. */
export function QuestaoParaVer({ questaoId }: { questaoId: number }) {
  const questao = useDados(() => api.questao(questaoId), [questaoId]);
  const q = questao.dados;

  if (questao.erro) return <Aviso tom="erro">{questao.erro}</Aviso>;
  if (!q) {
    return (
      <Carregamento rotulo="Lendo a questão" className="flex flex-col gap-3">
        <Esqueleto className="h-4 w-full" />
        <Esqueleto className="h-4 w-11/12" />
        <Esqueleto className="h-4 w-3/5" />
        {["w-2/5", "w-1/2", "w-1/3", "w-2/5"].map((largura, i) => (
          <div key={i} className="flex items-center gap-3 rounded-cartao border border-borda px-3 py-2.5">
            <Esqueleto className="size-6 shrink-0 rounded-full" />
            <Esqueleto className={`h-4 ${largura}`} />
          </div>
        ))}
      </Carregamento>
    );
  }

  const assunto = q.classificacao[0];
  return (
    <div className="surge flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-1.5">
        <Etiqueta>#{q.questao_id}</Etiqueta>
        {q.status !== "PUBLICADO" && <Etiqueta tom="atencao">Rascunho</Etiqueta>}
        {assunto ? <Etiqueta tom="info">{assunto.subassunto ? `${assunto.assunto} › ${assunto.subassunto}` : assunto.assunto}</Etiqueta> : <Etiqueta>Sem assunto</Etiqueta>}
        <Etiqueta>{DIFICULDADE[q.dificuldade] ?? q.dificuldade}</Etiqueta>
        {q.gabarito && <Etiqueta tom="sucesso">Gabarito {q.gabarito}</Etiqueta>}
        {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
      </div>
      <TextoFormatado texto={q.enunciado} />
      <AlternativasDaQuestao alternativas={q.alternativas} gabarito={q.gabarito} comentarios={q.comentarios} />
      <ResolucaoDaQuestao detalhe={q} />
    </div>
  );
}
