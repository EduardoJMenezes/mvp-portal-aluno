"use client";

import { Suspense, useState } from "react";
import { useCategoria } from "@/components/Categoria";
import { LinkDoPdf } from "@/components/Pdf";
import { Player } from "@/components/Player";
import { Aviso, Botao, BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { abrirEmNovaAba, api, casaCategoria, useDados, type Aula } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";

export default function AulasDoAluno() {
  return (
    <Suspense>
      <Lives />
    </Suspense>
  );
}

/**
 * As lives: a que está no ar em destaque, as próximas, e as que já aconteceram com a gravação —
 * no capítulo do curso, se a aula mora num, ou tocando aqui mesmo, se não mora.
 */
function Lives() {
  const categoria = useCategoria();
  // Relida a cada minuto: a aula que começa ou termina muda aqui sem recarregar.
  const lista = useDados(() => api.aulas(), [], 60);
  const [erro, setErro] = useState("");
  const [entrando, setEntrando] = useState(0);
  const [tocando, setTocando] = useState(0);

  const entrar = async (aula: Aula) => {
    setErro("");
    setEntrando(aula.aula_id);
    try {
      await abrirEmNovaAba(() => api.entrarNaAula(aula.aula_id));
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível entrar na aula.");
    } finally {
      setEntrando(0);
    }
  };

  return (
    <Pagina
      titulo={categoria ?? "Aulas ao vivo"}
      legenda="A aula ao vivo aparece em destaque quando a sala abre, 15 minutos antes. Embaixo, as próximas e as que já aconteceram."
    >
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <Estado {...lista} linhas={3} forma="lista">
        {(aulas) => {
          const daqui = aulas.filter((a) => casaCategoria(categoria, a.categoria));
          const noAr = daqui.filter((a) => a.estado === "ABERTA" || a.estado === "AGUARDANDO");
          const passadas = daqui.filter((a) => a.estado === "ENCERRADA");
          const proximas = daqui.filter((a) => a.estado === "AGENDADA").reverse();
          if (!noAr.length && !passadas.length && !proximas.length) {
            return (
              <Vazio titulo="Nenhuma aula por aqui ainda">
                Quando o professor marcar uma aula, ela aparece aqui com o horário. Depois, fica a gravação.
              </Vazio>
            );
          }
          return (
            <>
              {noAr.length > 0 && (
                <section className="flex flex-col gap-3" aria-label="Ao vivo agora">
                  {noAr.map((aula) => (
                    <Cartao key={aula.aula_id} className="border-erro-borda p-6">
                      <div className="flex flex-wrap items-center justify-between gap-4">
                        <div className="min-w-0">
                          <span className="flex items-center gap-2 text-sm font-semibold text-erro">
                            <span className="relative flex size-2.5">
                              <span aria-hidden="true" className="absolute inline-flex size-full rounded-full bg-erro opacity-75 motion-safe:animate-ping" />
                              <span aria-hidden="true" className="relative inline-flex size-2.5 rounded-full bg-erro" />
                            </span>
                            {aula.estado === "ABERTA" ? "Ao vivo agora" : "A sala abriu: a aula já vai começar"}
                          </span>
                          <h2 className="mt-2 text-xl font-semibold text-tinta">{aula.titulo}</h2>
                          <p className="text-[13px] text-suave">
                            {emBrasilia(aula.inicio_em)} · {aula.minutos} min
                          </p>
                          {aula.descricao && <p className="mt-1 text-sm text-suave">{aula.descricao}</p>}
                          {aula.material && <LinkDoPdf material={aula.material} className="mt-2" />}
                        </div>
                        <Botao variante="primario" onClick={() => entrar(aula)} ocupado={entrando === aula.aula_id}>
                          {entrando === aula.aula_id ? "Abrindo…" : "Entrar na aula"}
                        </Botao>
                      </div>
                    </Cartao>
                  ))}
                </section>
              )}
              {proximas.length > 0 && (
                <section className="flex flex-col gap-3">
                  <TituloDeSecao>Próximas</TituloDeSecao>
                  <ul className="grid gap-3">
                    {proximas.map((aula) => (
                      <Cartao key={aula.aula_id} como="li" className="p-5">
                        <h3 className="text-lg font-semibold text-tinta">{aula.titulo}</h3>
                        <p className="text-[13px] text-suave">
                          Ao vivo em {emBrasilia(aula.inicio_em)} · {aula.minutos} min · a sala abre 15 minutos antes
                        </p>
                        {aula.descricao && <p className="mt-1 text-sm text-suave">{aula.descricao}</p>}
                        {aula.material && <LinkDoPdf material={aula.material} className="mt-2" />}
                      </Cartao>
                    ))}
                  </ul>
                </section>
              )}
              {passadas.length > 0 && (
                <section className="flex flex-col gap-3">
                  <TituloDeSecao>Aulas que já aconteceram</TituloDeSecao>
                  <ul className="grid gap-3">
                    {passadas.map((aula) => (
                      <Cartao key={aula.aula_id} como="li" className="flex flex-col gap-3 p-5">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                          <div className="min-w-0">
                            <h3 className="text-lg font-semibold text-tinta">{aula.titulo}</h3>
                            <p className="text-[13px] text-suave">
                              {emBrasilia(aula.inicio_em)} · {aula.minutos} min
                            </p>
                            {aula.material && <LinkDoPdf material={aula.material} className="mt-1" />}
                          </div>
                          {aula.assistir ? (
                            <BotaoLink variante="secundario" href={`/curso/aula/?modulo=${aula.assistir.modulo_id}&item=${aula.assistir.item_id}`}>
                              Assistir à gravação
                            </BotaoLink>
                          ) : aula.video ? (
                            <Botao variante="secundario" aria-expanded={tocando === aula.aula_id} onClick={() => setTocando(tocando === aula.aula_id ? 0 : aula.aula_id)}>
                              {tocando === aula.aula_id ? "Fechar" : "Assistir à gravação"}
                            </Botao>
                          ) : aula.grava ? (
                            <Etiqueta>Gravação processando</Etiqueta>
                          ) : null}
                        </div>
                        {tocando === aula.aula_id && aula.video && <Player video={aula.video} />}
                      </Cartao>
                    ))}
                  </ul>
                </section>
              )}
            </>
          );
        }}
      </Estado>
    </Pagina>
  );
}
