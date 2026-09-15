"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Aviso, Botao, BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type Rascunho } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { DIFICULDADE, LETRAS, ORIGEM, TIPO_DE_RASCUNHO } from "@/lib/rotulos";
import { TextoFormatado } from "@/lib/texto";

export default function PaginaDeRevisao() {
  return (
    <Suspense>
      <Revisar />
    </Suspense>
  );
}

// A tela que prova a regra da POC: o que a IA propôs fica aqui, parado, até
// uma pessoa aprovar no navegador.
function Revisar() {
  const id = Number(useSearchParams().get("id"));
  const rascunho = useDados(() => api.rascunho(id), [id]);

  return (
    <Pagina titulo={rascunho.dados ? rascunho.dados.resumo || `Rascunho #${id}` : "Rascunho"} voltar={{ href: "/admin/rascunhos/", rotulo: "Rascunhos" }}>
      <Estado {...rascunho} linhas={4}>
        {(r) => <Conteudo r={r} recarregar={rascunho.recarregar} />}
      </Estado>
    </Pagina>
  );
}

function Conteudo({ r, recarregar }: { r: Rascunho; recarregar: () => Promise<void> }) {
  const router = useRouter();
  const [dialogo, confirmar] = useConfirmar();
  const [selecionados, setSelecionados] = useState<number[]>(() => r.itens.filter((i) => i.status === "RASCUNHO").map((i) => i.item_id));
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const pendencias = r.simulado?.pendencias_para_publicar ?? [];
  const aberto = r.status === "RASCUNHO";
  const parcial = r.itens.length > 0 && selecionados.length < r.itens.filter((i) => i.status === "RASCUNHO").length;
  const paraQuem = r.simulado ? r.simulado.turmas.join(", ") : r.turma ?? "os alunos das turmas envolvidas";

  async function publicar() {
    const quantos = r.itens.length ? plural(selecionados.length, "vídeo") : r.simulado ? `o simulado "${r.simulado.titulo}"` : plural(r.questoes.length, "questão", "questões");
    const sim = await confirmar({
      titulo: "Aprovar e publicar?",
      texto: (
        <>
          <p>
            {r.questoes.length && !r.simulado
              ? `${quantos} ${r.questoes.length === 1 ? "entra" : "entram"} no banco de questões e pode${r.questoes.length === 1 ? "" : "m"} ser usada${r.questoes.length === 1 ? "" : "s"} em simulados.`
              : `${quantos} ${r.simulado ? "fica visível" : "ficam visíveis"} para ${paraQuem}${r.simulado ? " na agenda dele" : " na hora"}.`}
          </p>
          <p className="mt-2 text-suave">A aprovação fica registrada em seu nome.</p>
        </>
      ),
      confirmar: "Aprovar e publicar",
    });
    if (!sim) return;
    setOcupado(true);
    setErro("");
    try {
      const resposta = await api.publicar(r.rascunho_id, parcial ? selecionados : undefined);
      setAviso(resposta.mensagem ?? "Publicado.");
      await recarregar();
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  async function descartar() {
    const sim = await confirmar({
      titulo: "Descartar esta proposta?",
      texto: "O rascunho é apagado de vez, com o que nasceu nele. Nada chega aos alunos.",
      confirmar: "Descartar",
      perigo: true,
    });
    if (!sim) return;
    setOcupado(true);
    try {
      await api.descartar(r.rascunho_id);
      router.replace("/admin/rascunhos/");
    } catch (e) {
      setErro((e as Error).message);
      setOcupado(false);
    }
  }

  return (
    <>
      {dialogo}
      <Cartao className="flex flex-wrap items-center justify-between gap-4 p-5">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Etiqueta tom="info">{TIPO_DE_RASCUNHO[r.tipo] ?? r.tipo}</Etiqueta>
            {aberto ? <Etiqueta tom="atencao">Esperando aprovação</Etiqueta> : <Etiqueta tom="sucesso">Publicado</Etiqueta>}
          </div>
          <p className="mt-2 text-[15px] text-tinta-2">
            Criado por <strong>{r.criado_por}</strong> via {ORIGEM[r.origem] ?? r.origem} em {emBrasilia(r.criado_em)}
            {r.turma && <> · destino: {[r.turma, r.modulo, r.submodulo].filter(Boolean).join(" › ")}</>}
          </p>
          {!aberto && (
            <p className="mt-1 text-[15px] text-suave">
              Aprovado por {r.aprovado_por ?? "—"} via {r.aprovado_via ?? "—"} · publicado {emBrasilia(r.publicado_em)}
            </p>
          )}
        </div>
        {aberto && (
          <div className="flex flex-wrap gap-2">
            <Botao variante="perigo" disabled={ocupado} onClick={() => void descartar()}>Descartar</Botao>
            <Botao variante="primario" disabled={ocupado || pendencias.length > 0 || (r.itens.length > 0 && selecionados.length === 0)} onClick={() => void publicar()}>
              {parcial ? `Publicar ${plural(selecionados.length, "vídeo")}` : "Aprovar e publicar"}
            </Botao>
          </div>
        )}
      </Cartao>

      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
      {aberto && pendencias.length > 0 && (
        <Aviso tom="atencao" titulo="Ainda não dá para publicar">
          <ul className="mt-1 list-disc pl-5">
            {pendencias.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </Aviso>
      )}

      {r.simulado && (
        <section className="flex flex-col gap-3">
          <TituloDeSecao acao={<BotaoLink tamanho="pequeno" href={`/admin/simulados/ver/?id=${r.simulado.simulado_id}`}>Editar agenda e questões</BotaoLink>}>
            {r.simulado.titulo}
          </TituloDeSecao>
          <Cartao className="grid gap-4 p-5 sm:grid-cols-4">
            <Dado rotulo="Turmas" valor={r.simulado.turmas.join(", ") || "—"} />
            <Dado rotulo="Abre" valor={r.simulado.abre_em ?? "—"} />
            <Dado rotulo="Fecha" valor={r.simulado.fecha_em ?? "—"} />
            <Dado rotulo="Tempo de prova" valor={r.simulado.duracao_minutos ? `${r.simulado.duracao_minutos} min` : "—"} />
          </Cartao>
          <Cartao>
            <ol className="divide-y divide-borda">
              {r.simulado.questoes.map((q) => (
                <li key={q.questao_id} className="flex flex-wrap items-start justify-between gap-3 px-5 py-4">
                  <div className="min-w-0 flex-1">
                    <div className="mb-1 flex flex-wrap items-center gap-2">
                      <span className="font-mono text-sm font-semibold">Q{String(q.ordem).padStart(2, "0")}</span>
                      <Etiqueta tom="sucesso">Gabarito {q.gabarito}</Etiqueta>
                      <Etiqueta>{q.nova ? "Nova" : "Do acervo"}</Etiqueta>
                      {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                      <Etiqueta tom={q.resolucao ? "info" : "neutro"}>{q.resolucao ? `Vídeo: ${q.resolucao}` : "Sem vídeo de resolução"}</Etiqueta>
                    </div>
                    <div className="line-clamp-3">
                      <TextoFormatado texto={q.enunciado} compacto />
                    </div>
                  </div>
                  <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="text-sm font-semibold text-acento hover:underline">Editar</Link>
                </li>
              ))}
            </ol>
          </Cartao>
        </section>
      )}

      {r.itens.length > 0 && (
        <section className="flex flex-col gap-3">
          <TituloDeSecao>{plural(r.itens.length, "vídeo")}</TituloDeSecao>
          <Cartao className="overflow-x-auto">
            <table className="tabela min-w-[36rem]">
              <thead>
                <tr>
                  {aberto && (
                    <th scope="col" className="w-10">
                      <input
                        type="checkbox"
                        aria-label="Marcar todos"
                        className="size-4 accent-acento"
                        checked={selecionados.length === r.itens.filter((i) => i.status === "RASCUNHO").length}
                        onChange={(e) => setSelecionados(e.target.checked ? r.itens.filter((i) => i.status === "RASCUNHO").map((i) => i.item_id) : [])}
                      />
                    </th>
                  )}
                  <th scope="col">Nome</th>
                  <th scope="col">Vídeo no Vimeo</th>
                  <th scope="col">Assuntos</th>
                  <th scope="col">Situação</th>
                </tr>
              </thead>
              <tbody>
                {r.itens.map((item) => (
                  <tr key={item.item_id}>
                    {aberto && (
                      <td>
                        {item.status === "RASCUNHO" && (
                          <input
                            type="checkbox"
                            aria-label={`Publicar ${item.nome}`}
                            className="size-4 accent-acento"
                            checked={selecionados.includes(item.item_id)}
                            onChange={(e) => setSelecionados((s) => (e.target.checked ? [...s, item.item_id] : s.filter((x) => x !== item.item_id)))}
                          />
                        )}
                      </td>
                    )}
                    <td className="font-medium">{item.nome}</td>
                    <td className="text-suave">
                      <span className="font-mono text-[13px]">{item.video.vimeo_id}</span> · {item.video.titulo}
                    </td>
                    <td className="text-suave">{item.assuntos.map((a) => (a.subassunto ? `${a.assunto} › ${a.subassunto}` : a.assunto)).join(", ") || "—"}</td>
                    <td>{item.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicado</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Cartao>
        </section>
      )}

      {r.questoes.length > 0 && !r.simulado && (
        <section className="flex flex-col gap-3">
          <TituloDeSecao>{plural(r.questoes.length, "questão", "questões")}</TituloDeSecao>
          {r.questoes.map((q) => (
            <Cartao key={q.questao_id} className="flex flex-col gap-3 p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm text-suave">#{q.questao_id}</span>
                  {q.completa ? <Etiqueta tom="sucesso">Completa</Etiqueta> : <Etiqueta tom="erro">Sem as alternativas A–E</Etiqueta>}
                  <Etiqueta>{DIFICULDADE[q.dificuldade] ?? q.dificuldade}</Etiqueta>
                  {q.imagem_pendente && <Etiqueta tom="atencao">Imagem pendente</Etiqueta>}
                </div>
                <Link href={`/admin/questoes/editar/?id=${q.questao_id}`} className="text-sm font-semibold text-acento hover:underline">Editar e anexar figura</Link>
              </div>
              <TextoFormatado texto={q.enunciado} />
              {q.completa && (
                <ul className="flex flex-col gap-1.5">
                  {LETRAS.map((letra) => (
                    <li key={letra} className={`flex items-start gap-2 rounded-md border px-3 py-2 ${letra === q.gabarito ? "border-sucesso-borda bg-sucesso-fundo" : "border-borda"}`}>
                      <span className="font-semibold">{letra}</span>
                      <TextoFormatado texto={q.alternativas[letra] ?? ""} compacto className="min-w-0 flex-1" />
                    </li>
                  ))}
                </ul>
              )}
              {q.resolucao_comentada && (
                <div className="rounded-md bg-lilas p-3">
                  <p className="mb-1 text-[13px] font-semibold text-acento-forte">Resolução comentada</p>
                  <TextoFormatado texto={q.resolucao_comentada} compacto />
                </div>
              )}
              <p className="text-[13px] text-suave">
                {q.classificacao.map((c) => (c.subassunto ? `${c.assunto} › ${c.subassunto}` : c.assunto)).join(", ") || "Sem classificação"}
                {q.video && ` · vídeo ${q.video.vimeo_id} — ${q.video.titulo}`}
              </p>
            </Cartao>
          ))}
        </section>
      )}
    </>
  );
}

function Dado({ rotulo, valor }: { rotulo: string; valor: string }) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wide text-suave">{rotulo}</p>
      <p className="mt-0.5 text-[15px] text-tinta">{valor}</p>
    </div>
  );
}
