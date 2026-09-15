"use client";

import Link from "next/link";
import { BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type RascunhoResumo, type SimuladoResumo, type Turma } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { useUsuario } from "@/lib/sessao";
import { ORIGEM, TIPO_DE_RASCUNHO } from "@/lib/rotulos";

export default function Painel() {
  const usuario = useUsuario();
  const dados = useDados(async () => {
    const [rascunhos, simulados, turmas] = await Promise.all([api.rascunhos("RASCUNHO"), api.simuladosDoProfessor(), api.turmas()]);
    return { rascunhos, simulados, turmas };
  });

  return (
    <Pagina
      titulo={`Olá, ${usuario.nome.split(" ")[0]}`}
      legenda="O que espera por você, os simulados da semana e as turmas."
      acoes={
        <>
          <BotaoLink href="/admin/importar/">Importar</BotaoLink>
          <BotaoLink variante="primario" href="/admin/simulados/novo/">Montar simulado</BotaoLink>
        </>
      }
    >
      <Estado {...dados} linhas={4}>
        {({ rascunhos, simulados, turmas }) => (
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
            <div className="flex flex-col gap-5">
              <Rascunhos rascunhos={rascunhos} />
              <Simulados simulados={simulados} />
            </div>
            <Turmas turmas={turmas} />
          </div>
        )}
      </Estado>
    </Pagina>
  );
}

function Rascunhos({ rascunhos }: { rascunhos: RascunhoResumo[] }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-rascunhos">
      <TituloDeSecao acao={<Link href="/admin/rascunhos/" className="text-sm font-semibold text-acento hover:underline">Ver todos</Link>}>
        <span id="titulo-rascunhos">Esperando aprovação</span>
        {rascunhos.length > 0 && <Etiqueta tom="atencao" className="ml-2 align-middle">{rascunhos.length}</Etiqueta>}
      </TituloDeSecao>
      {rascunhos.length === 0 ? (
        <Vazio titulo="Nada esperando aprovação">O que o Claude propuser pelo MCP aparece aqui antes de chegar aos alunos.</Vazio>
      ) : (
        <Cartao>
          <ul className="divide-y divide-borda">
            {rascunhos.slice(0, 5).map((r) => (
              <li key={r.rascunho_id}>
                <Link href={`/admin/rascunhos/revisar/?id=${r.rascunho_id}`} className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-canvas">
                  <span className="min-w-0">
                    <span className="block truncate font-medium text-tinta">{r.resumo || `Rascunho #${r.rascunho_id}`}</span>
                    <span className="block truncate text-[13px] text-suave">
                      {TIPO_DE_RASCUNHO[r.tipo]} · {r.criado_por} via {ORIGEM[r.origem] ?? r.origem} · {emBrasilia(r.criado_em)}
                    </span>
                  </span>
                  <span className="shrink-0 text-sm font-semibold text-acento">Revisar</span>
                </Link>
              </li>
            ))}
          </ul>
        </Cartao>
      )}
    </section>
  );
}

function Simulados({ simulados }: { simulados: SimuladoResumo[] }) {
  const abertos = simulados.filter((s) => s.situacao === "ABERTO");
  const proximos = simulados.filter((s) => s.situacao === "AGENDADO");
  const encerrados = simulados.filter((s) => s.situacao === "ENCERRADO").slice(0, 3);
  const linha = (s: SimuladoResumo, detalhe: string, tom: "info" | "neutro" | "sucesso", rotulo: string) => (
    <li key={s.simulado_id}>
      <Link href={`/admin/simulados/ver/?id=${s.simulado_id}`} className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-canvas">
        <span className="min-w-0">
          <span className="block truncate font-medium text-tinta">{s.titulo}</span>
          <span className="block truncate text-[13px] text-suave">{s.turmas.join(", ")} · {detalhe}</span>
        </span>
        <Etiqueta tom={tom}>{rotulo}</Etiqueta>
      </Link>
    </li>
  );

  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-simulados">
      <TituloDeSecao acao={<Link href="/admin/simulados/" className="text-sm font-semibold text-acento hover:underline">Ver todos</Link>}>
        <span id="titulo-simulados">Simulados</span>
      </TituloDeSecao>
      {!abertos.length && !proximos.length && !encerrados.length ? (
        <Vazio titulo="Nenhum simulado publicado">Monte um pelo portal ou peça ao Claude.</Vazio>
      ) : (
        <Cartao>
          <ul className="divide-y divide-borda">
            {abertos.map((s) => linha(s, `${plural(s.tentativas ?? 0, "aluno começou", "alunos começaram")} · fecha ${emBrasilia(s.fecha_em)}`, "info", "Aberto"))}
            {proximos.map((s) => linha(s, `abre ${emBrasilia(s.abre_em)}`, "neutro", "Agendado"))}
            {encerrados.map((s) => linha(s, `${plural(s.tentativas ?? 0, "participante")} · fechou ${emBrasilia(s.fecha_em)}`, "sucesso", "Encerrado"))}
          </ul>
        </Cartao>
      )}
    </section>
  );
}

function Turmas({ turmas }: { turmas: Turma[] }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-turmas">
      <TituloDeSecao acao={<Link href="/admin/turmas/" className="text-sm font-semibold text-acento hover:underline">Gerenciar</Link>}>
        <span id="titulo-turmas">Turmas</span>
      </TituloDeSecao>
      {turmas.length === 0 ? (
        <Vazio titulo="Nenhuma turma">Crie a primeira em Turmas.</Vazio>
      ) : (
        <ul className="flex flex-col gap-3">
          {turmas.map((t) => (
            <Cartao key={t.id} como="li" className="p-4">
              <div className="flex items-start justify-between gap-2">
                <Link href={`/admin/turmas/curso/?turma=${t.id}`} className="font-semibold text-tinta hover:text-acento">{t.nome}</Link>
                {(t.itens_em_rascunho ?? 0) > 0 && <Etiqueta tom="atencao">{plural(t.itens_em_rascunho ?? 0, "em rascunho", "em rascunho")}</Etiqueta>}
              </div>
              <p className="mt-1 text-[13px] text-suave">
                {plural(t.alunos, "aluno")} · {plural(t.modulos, "módulo")} · {plural(t.itens_publicados, "vídeo publicado", "vídeos publicados")}
              </p>
              <div className="mt-2 flex gap-3 text-sm font-semibold">
                <Link href={`/admin/turmas/curso/?turma=${t.id}`} className="text-acento hover:underline">Curso</Link>
                <Link href={`/admin/turmas/alunos/?turma=${t.id}`} className="text-acento hover:underline">Alunos</Link>
              </div>
            </Cartao>
          ))}
        </ul>
      )}
    </section>
  );
}
