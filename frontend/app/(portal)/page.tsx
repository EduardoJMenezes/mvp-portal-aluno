"use client";

import Link from "next/link";
import { BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, useDados, type ConteudoDaTurma, type SimuladoResumo } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { useUsuario } from "@/lib/sessao";

export default function Inicio() {
  const usuario = useUsuario();
  const dados = useDados(async () => {
    const [simulados, conteudo] = await Promise.all([api.simulados(), api.conteudo()]);
    return { simulados, conteudo };
  });
  const primeiroNome = usuario.nome.split(" ")[0];

  return (
    <Pagina titulo={`Olá, ${primeiroNome}`} legenda={usuario.turmas.length ? `Sua turma: ${usuario.turmas.join(", ")}` : undefined}>
      <Estado {...dados} linhas={4}>
        {({ simulados, conteudo }) => (
          <>
            <Simulados simulados={simulados} />
            <Curso conteudo={conteudo} />
          </>
        )}
      </Estado>
    </Pagina>
  );
}

function Simulados({ simulados }: { simulados: SimuladoResumo[] }) {
  const abertos = simulados.filter((s) => s.situacao === "ABERTO" && !s.minha_prova?.entregue);
  const resultados = simulados.filter((s) => s.resultado_disponivel).slice(0, 2);
  const proximos = simulados.filter((s) => s.situacao === "AGENDADO").slice(0, 2);
  const nada = !abertos.length && !resultados.length && !proximos.length;

  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-simulados">
      <TituloDeSecao acao={<Link href="/simulados/" className="text-sm font-semibold text-acento hover:underline">Todos os simulados</Link>}>
        <span id="titulo-simulados">Simulados</span>
      </TituloDeSecao>
      {nada && <Vazio titulo="Nenhum simulado por agora">Quando o professor publicar um simulado para a sua turma, ele aparece aqui.</Vazio>}
      <div className="grid gap-3 md:grid-cols-2">
        {abertos.map((s) => (
          <Cartao key={s.simulado_id} className="flex flex-col gap-3 border-acento p-5">
            <div className="flex items-start justify-between gap-3">
              <div>
                <Etiqueta tom="info">{s.minha_prova?.iniciada ? "Em andamento" : "Aberto agora"}</Etiqueta>
                <h3 className="mt-2 text-lg font-semibold text-tinta">{s.titulo}</h3>
              </div>
            </div>
            <p className="text-[15px] text-suave">
              {plural(s.total_questoes, "questão", "questões")} · {s.duracao_minutos} min de prova · fecha {emBrasilia(s.fecha_em)}
            </p>
            <div>
              {s.minha_prova?.iniciada ? (
                <BotaoLink variante="primario" href={`/simulados/prova/?id=${s.simulado_id}`}>Continuar a prova</BotaoLink>
              ) : (
                <BotaoLink variante="primario" href={`/simulados/inicio/?id=${s.simulado_id}`}>Ver e começar</BotaoLink>
              )}
            </div>
          </Cartao>
        ))}
        {resultados.map((s) => (
          <Cartao key={s.simulado_id} className="flex flex-col gap-3 p-5">
            <div>
              <Etiqueta tom="sucesso">Resultado disponível</Etiqueta>
              <h3 className="mt-2 text-lg font-semibold text-tinta">{s.titulo}</h3>
            </div>
            <p className="text-[15px] text-suave">Fechou {emBrasilia(s.fecha_em)}</p>
            <div>
              <BotaoLink variante="secundario" href={`/simulados/resultado/?id=${s.simulado_id}`}>Ver resultado</BotaoLink>
            </div>
          </Cartao>
        ))}
        {proximos.map((s) => (
          <Cartao key={s.simulado_id} className="flex flex-col gap-2 p-5">
            <Etiqueta tom="neutro" className="self-start">Agendado</Etiqueta>
            <h3 className="text-lg font-semibold text-tinta">{s.titulo}</h3>
            <p className="text-[15px] text-suave">Abre {emBrasilia(s.abre_em)} · {plural(s.total_questoes, "questão", "questões")}</p>
          </Cartao>
        ))}
      </div>
    </section>
  );
}

function Curso({ conteudo }: { conteudo: ConteudoDaTurma[] }) {
  const modulos = conteudo.flatMap((t) => t.modulos);
  return (
    <section className="mt-4 flex flex-col gap-3" aria-labelledby="titulo-curso">
      <TituloDeSecao acao={<Link href="/curso/" className="text-sm font-semibold text-acento hover:underline">Curso completo</Link>}>
        <span id="titulo-curso">Seu curso</span>
      </TituloDeSecao>
      {modulos.length === 0 ? (
        <Vazio titulo="Nada publicado ainda">As aulas e resoluções aparecem aqui assim que o professor publicar.</Vazio>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {modulos.slice(0, 6).map((m) => {
            const videos = m.submodulos.reduce((n, s) => n + s.itens.length, 0);
            return (
              <li key={m.id}>
                <Link href={`/curso/aula/?modulo=${m.id}`} className="flex h-full flex-col gap-1 rounded-cartao border border-borda bg-papel p-4 transition-shadow hover:shadow-suave">
                  <span className="font-semibold text-tinta">{m.nome}</span>
                  <span className="text-sm text-suave">{plural(videos, "vídeo")} · {m.submodulos.map((s) => s.nome).join(" · ")}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
