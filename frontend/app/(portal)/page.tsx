"use client";

import Link from "next/link";
import { CartaoDoModulo } from "@/components/CartaoDoModulo";
import { BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { LinhaDoEvento } from "@/components/Agenda";
import { api, useDados, type ConteudoDaTurma, type EventoDaAgenda, type SimuladoResumo } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { useUsuario } from "@/lib/sessao";

export default function Inicio() {
  const usuario = useUsuario();
  const dados = useDados(async () => {
    const [simulados, conteudo, agenda] = await Promise.all([api.simulados(), api.conteudo(), api.agenda()]);
    return { simulados, conteudo, agenda };
  });
  const primeiroNome = usuario.nome.split(" ")[0];

  return (
    <Pagina titulo={`Olá, ${primeiroNome}`} legenda={usuario.turmas.length ? `Sua turma: ${usuario.turmas.join(", ")}` : undefined}>
      <Estado {...dados} linhas={4}>
        {({ simulados, conteudo, agenda }) => (
          <>
            <EstaSemana agenda={agenda} />
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
  const aluno = useUsuario().papel === "ALUNO";
  const modulos = conteudo.flatMap((t) => t.modulos);
  return (
    <section className="mt-4 flex flex-col gap-3" aria-labelledby="titulo-curso">
      <TituloDeSecao acao={<Link href="/curso/" className="text-sm font-semibold text-acento hover:underline">Curso completo</Link>}>
        <span id="titulo-curso">Seu curso</span>
      </TituloDeSecao>
      {modulos.length === 0 ? (
        <Vazio titulo="Nada publicado ainda">As aulas e resoluções aparecem aqui assim que o professor publicar.</Vazio>
      ) : (
        <ul className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {modulos.slice(0, 6).map((m) => (
            <li key={m.id}>
              <CartaoDoModulo modulo={m} comProgresso={aluno} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Os eventos da semana até hoje (a agenda não mostra o futuro), com o link para estudar. */
function EstaSemana({ agenda }: { agenda: EventoDaAgenda[] }) {
  const hoje = new Date();
  const segunda = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() - ((hoje.getDay() + 6) % 7));
  const daSemana = agenda.filter((e) => new Date(e.inicio_em) >= segunda);
  if (daSemana.length === 0) return null;
  return (
    <section className="flex flex-col gap-3" aria-labelledby="titulo-semana">
      <TituloDeSecao acao={<Link href="/agenda/" className="text-sm font-semibold text-acento hover:underline">Agenda completa</Link>}>
        <span id="titulo-semana">Esta semana</span>
      </TituloDeSecao>
      <Cartao>
        <ul className="divide-y divide-borda">
          {daSemana.map((e) => (
            <LinhaDoEvento key={e.evento_id} evento={e} />
          ))}
        </ul>
      </Cartao>
    </section>
  );
}
