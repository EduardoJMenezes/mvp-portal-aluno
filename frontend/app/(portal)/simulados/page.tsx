"use client";

import { Suspense } from "react";
import { useCategoria } from "@/components/Categoria";
import { BotaoLink, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio, type Tom } from "@/components/ui";
import { api, casaCategoria, useDados, type SimuladoResumo } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";

type Grupo = { titulo: string; filtro: (s: SimuladoResumo) => boolean };

// Quem fez a prova espera o resultado enquanto ela está aberta e, depois que fecha, até o professor liberar.
const aguardando = (s: SimuladoResumo) =>
  (s.situacao === "ABERTO" && !!s.minha_prova?.entregue) || (s.situacao === "ENCERRADO" && !!s.minha_prova?.iniciada && !s.resultado_disponivel);

const GRUPOS: Grupo[] = [
  { titulo: "Abertos", filtro: (s) => s.situacao === "ABERTO" && !s.minha_prova?.entregue },
  { titulo: "Entregues, aguardando o resultado", filtro: aguardando },
  { titulo: "Agendados", filtro: (s) => s.situacao === "AGENDADO" },
  { titulo: "Encerrados", filtro: (s) => s.situacao === "ENCERRADO" && !aguardando(s) },
];

export default function PaginaDosSimulados() {
  return (
    <Suspense>
      <Simulados />
    </Suspense>
  );
}

function Simulados() {
  const categoria = useCategoria();
  const simulados = useDados(() => api.simulados());

  return (
    <Pagina titulo={categoria ? `Simulados ${categoria}` : "Simulados"} legenda="Os simulados publicados para a sua turma. O resultado de cada um sai depois que ele fecha, quando o professor liberar.">
      <Estado {...simulados} linhas={4} forma="lista">
        {(todos) => {
          const lista = todos.filter((s) => casaCategoria(categoria, s.categoria));
          return lista.length === 0 ? (
            <Vazio titulo="Nenhum simulado disponível">Quando o professor publicar um simulado para a sua turma, ele aparece aqui.</Vazio>
          ) : (
            GRUPOS.map((grupo) => {
              const itens = lista.filter(grupo.filtro);
              if (!itens.length) return null;
              return (
                <section key={grupo.titulo} className="flex flex-col gap-3">
                  <TituloDeSecao>{grupo.titulo}</TituloDeSecao>
                  <ul className="flex flex-col gap-3">
                    {itens.map((s) => (
                      <Linha key={s.simulado_id} s={s} />
                    ))}
                  </ul>
                </section>
              );
            })
          );
        }}
      </Estado>
    </Pagina>
  );
}

function Linha({ s }: { s: SimuladoResumo }) {
  let etiqueta: [Tom, string];
  let detalhe: string;
  let acao: React.ReactNode = null;

  if (s.situacao === "AGENDADO") {
    etiqueta = ["neutro", "Agendado"];
    detalhe = `Abre ${emBrasilia(s.abre_em)}`;
  } else if (s.situacao === "ABERTO" && s.minha_prova?.entregue) {
    etiqueta = ["info", "Entregue"];
    detalhe = `Fecha ${emBrasilia(s.fecha_em)} · o resultado sai depois, quando o professor liberar`;
  } else if (s.situacao === "ABERTO") {
    etiqueta = ["info", s.minha_prova?.iniciada ? "Em andamento" : "Aberto"];
    detalhe = `Fecha ${emBrasilia(s.fecha_em)}`;
    acao = s.minha_prova?.iniciada ? (
      <BotaoLink variante="primario" href={`/simulados/prova/?id=${s.simulado_id}`}>Continuar</BotaoLink>
    ) : (
      <BotaoLink variante="primario" href={`/simulados/inicio/?id=${s.simulado_id}`}>Ver e começar</BotaoLink>
    );
  } else if (s.resultado_disponivel) {
    etiqueta = ["sucesso", "Resultado disponível"];
    detalhe = `Fechou ${emBrasilia(s.fecha_em)}`;
    acao = <BotaoLink variante="secundario" href={`/simulados/resultado/?id=${s.simulado_id}`}>Ver resultado</BotaoLink>;
  } else if (s.minha_prova?.iniciada) {
    // Fechou e ele fez: o resultado inteiro espera o professor liberar.
    etiqueta = ["atencao", "Aguardando resultado"];
    detalhe = `Fechou ${emBrasilia(s.fecha_em)} · o resultado sai quando o professor liberar`;
  } else {
    etiqueta = ["neutro", "Encerrado"];
    detalhe = "Você não fez esta prova";
  }

  return (
    <Cartao como="li" className="flex flex-wrap items-center justify-between gap-4 p-5">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-lg font-semibold text-tinta">{s.titulo}</h3>
          <Etiqueta tom={etiqueta[0]}>{etiqueta[1]}</Etiqueta>
        </div>
        <p className="mt-1 text-[15px] text-suave">
          {plural(s.total_questoes, "questão", "questões")} · {s.duracao_minutos ?? "—"} min de prova · {detalhe}
        </p>
      </div>
      {acao}
    </Cartao>
  );
}
