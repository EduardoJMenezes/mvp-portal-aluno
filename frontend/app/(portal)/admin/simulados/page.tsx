"use client";

import Link from "next/link";
import { useState } from "react";
import { BotaoLink, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { SITUACAO } from "@/lib/rotulos";

export default function SimuladosDoProfessor() {
  const turmas = useDados(() => api.turmas());
  const [turma, setTurma] = useState("");
  const lista = useDados(() => api.simuladosDoProfessor(turma || undefined), [turma]);

  return (
    <Pagina
      titulo="Simulados"
      legenda="A prova trava quando abre: dali em diante só o título muda e o fechamento só pode ser estendido."
      acoes={
        <>
          <BotaoLink href="/admin/importar/docx/">Importar .docx</BotaoLink>
          <BotaoLink variante="primario" href="/admin/simulados/novo/">Novo simulado</BotaoLink>
        </>
      }
    >
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="filtro-turma" className="text-sm font-semibold text-tinta-2">Turma</label>
        <select id="filtro-turma" value={turma} onChange={(e) => setTurma(e.target.value)} className="campo w-auto">
          <option value="">Todas</option>
          {turmas.dados?.map((t) => (
            <option key={t.id} value={t.id}>{t.nome}</option>
          ))}
        </select>
      </div>
      <Estado {...lista} linhas={4}>
        {(simulados) =>
          simulados.length === 0 ? (
            <Vazio titulo="Nenhum simulado">Monte um aqui, importe o .docx da equipe ou peça ao Claude.</Vazio>
          ) : (
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[48rem]">
                <thead>
                  <tr>
                    <th scope="col">Simulado</th>
                    <th scope="col">Situação</th>
                    <th scope="col">Abre</th>
                    <th scope="col">Fecha</th>
                    <th scope="col" className="text-right">Provas</th>
                  </tr>
                </thead>
                <tbody>
                  {simulados.map((s) => {
                    const [tom, rotulo] = SITUACAO[s.situacao];
                    return (
                      <tr key={s.simulado_id}>
                        <td>
                          <Link href={`/admin/simulados/ver/?id=${s.simulado_id}`} className="font-semibold text-tinta hover:text-acento hover:underline">{s.titulo}</Link>
                          <p className="text-[13px] text-suave">
                            {s.turmas.join(", ") || "Sem turma"} · {plural(s.total_questoes, "questão", "questões")}
                            {s.duracao_minutos ? ` · ${s.duracao_minutos} min` : ""}
                          </p>
                        </td>
                        <td><Etiqueta tom={tom}>{rotulo}</Etiqueta></td>
                        <td className="whitespace-nowrap tabular-nums">{emBrasilia(s.abre_em)}</td>
                        <td className="whitespace-nowrap tabular-nums">{emBrasilia(s.fecha_em)}</td>
                        <td className="text-right tabular-nums">{s.tentativas ?? 0}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </Cartao>
          )
        }
      </Estado>
    </Pagina>
  );
}
