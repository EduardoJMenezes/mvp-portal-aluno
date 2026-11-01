"use client";

import { CircleCheck, CircleX, PlayCircle, SquarePen } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { Anel } from "@/components/Resultado";
import { GradeQuestoes } from "@/components/GradeQuestoes";
import { Aviso, BotaoLink, Cartao, Estado, Pagina, TituloDeSecao } from "@/components/ui";
import { api, useDados, type AnaliseDaSecao } from "@/lib/api";
import { enderecoDaSecao, enderecoDoCapitulo } from "@/lib/curso";
import { plural, porcento } from "@/lib/formato";

// A análise de quem terminou as questões de uma seção, como o professor desenhou: a grade verde e
// vermelha, o desempenho por assunto contra a média da turma, e os blocos de acertos e erros com a
// pizza de cada um. Os textos são curtos de propósito — "errou isto, estude isto". A leitura
// aprofundada é do professor, não da tela.

export default function PaginaDaAnalise() {
  return (
    <Suspense>
      <Analise />
    </Suspense>
  );
}

function Analise() {
  const parametros = useSearchParams();
  const modulo = Number(parametros.get("modulo"));
  const secao = Number(parametros.get("secao"));
  const analise = useDados(() => api.analiseDaSecao(secao), [secao]);
  return (
    <Estado {...analise} linhas={6}>
      {(a) => <Conteudo a={a} moduloId={modulo} />}
    </Estado>
  );
}

// As cores das fatias: as mesmas do restante do portal, na ordem dos assuntos.
const CORES = ["var(--color-acento)", "var(--color-ceu)", "var(--color-violeta)", "var(--color-atencao-vivo)", "var(--color-sucesso-vivo)", "var(--color-erro)"];

function Conteudo({ a, moduloId }: { a: AnaliseDaSecao; moduloId: number }) {
  const router = useRouter();
  const voltar = { href: enderecoDoCapitulo(moduloId || a.modulo_id), rotulo: "Capítulo" };

  if (!a.pronta) {
    return (
      <Pagina titulo="Sua análise" legenda={`${a.secao} · ${a.modulo}`} voltar={voltar}>
        <Aviso tom="info" titulo="A análise sai quando você terminar">
          {a.faltam === 1 ? "Falta 1 questão" : `Faltam ${a.faltam} questões`} para fechar este bloco. Responda todas e volte aqui.
          <div className="mt-3">
            <BotaoLink variante="primario" href={enderecoDaSecao(a.modulo_id, a.secao_id)}>Continuar respondendo</BotaoLink>
          </div>
        </Aviso>
      </Pagina>
    );
  }

  const corDoAssunto = new Map(a.por_assunto.map((p, i) => [p.assunto, CORES[i % CORES.length]]));
  const fatias = (certas: boolean) =>
    a.por_assunto
      .map((p) => ({ rotulo: p.assunto, valor: certas ? p.acertos : p.questoes - p.acertos, cor: corDoAssunto.get(p.assunto)! }))
      .filter((f) => f.valor > 0);

  return (
    <Pagina titulo="Sua análise" legenda={`${a.secao} · ${a.modulo}`} voltar={voltar}>
      <Cartao como="div" className="flex flex-wrap items-center gap-5 p-5">
        <Anel percentual={a.percentual} tamanho={96} />
        <div className="min-w-0">
          <p className="font-titulo text-2xl font-bold text-tinta">
            {a.acertos} de {a.total} <span className="text-base font-normal text-tinta-2">acertos</span>
          </p>
          <p className="mt-1 text-[15px] text-tinta-2">Clique numa questão para ver a resolução.</p>
        </div>
      </Cartao>

      <section className="flex flex-col gap-3" aria-labelledby="titulo-grade">
        <TituloDeSecao>
          <span id="titulo-grade">Questão por questão</span>
        </TituloDeSecao>
        <Cartao como="div" className="p-4">
          <GradeQuestoes
            rotulo="Acertos e erros por questão"
            estados={a.questoes.map((q) => (q.correta ? "acertou" : "errou"))}
            aoEscolher={(i) => router.push(enderecoDaSecao(a.modulo_id, a.secao_id, a.questoes[i].item_id))}
          />
        </Cartao>
      </section>

      {a.por_assunto.length > 0 && (
        <section className="flex flex-col gap-3" aria-labelledby="titulo-desempenho">
          <TituloDeSecao>
            <span id="titulo-desempenho">Desempenho por assunto</span>
          </TituloDeSecao>
          <Cartao como="div" className="flex flex-col gap-3 p-5">
            <p className="flex items-center gap-3 text-[13px] text-suave">
              <span className="flex items-center gap-1.5"><span className="h-2 w-4 rounded-full bg-acento" aria-hidden="true" />você</span>
              <span className="flex items-center gap-1.5"><span className="h-3.5 w-0.5 bg-tinta" aria-hidden="true" />média da turma</span>
            </p>
            <ul className="flex flex-col gap-3">
              {a.por_assunto.map((p) => (
                <li key={p.assunto} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto]">
                  <span className="truncate text-[15px] font-medium text-tinta">{p.assunto}</span>
                  <span className="relative col-span-2 block h-2.5 rounded-full bg-gelo sm:col-span-1 sm:col-start-2 sm:row-start-1" aria-hidden="true">
                    <span className="block h-full rounded-full bg-acento" style={{ width: `${Math.max(p.percentual, 2)}%` }} />
                    <span className="absolute -top-1 h-[18px] w-0.5 bg-tinta" style={{ left: `calc(${Math.min(p.media_da_turma, 99.5)}% - 1px)` }} />
                  </span>
                  <span className="col-start-2 row-start-1 text-right text-[13px] tabular-nums text-suave sm:col-start-3">
                    <span className="font-semibold text-tinta">{p.acertos} de {p.questoes}</span> · turma {porcento(p.media_da_turma)}
                  </span>
                </li>
              ))}
            </ul>
            <p className="text-[13px] text-suave">
              A média é de {plural(Math.max(...a.por_assunto.map((p) => p.alunos_na_media)), "aluno", "alunos")} da sua turma nestas mesmas questões.
            </p>
          </Cartao>
        </section>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <Bloco titulo="Acertos" icone={<CircleCheck aria-hidden="true" className="size-5 text-sucesso-vivo" />} texto={a.sobre_os_acertos} fatias={fatias(true)} vazio="Nenhum acerto neste bloco." />
        <Bloco titulo="Erros" icone={<CircleX aria-hidden="true" className="size-5 text-erro" />} texto={a.sobre_os_erros} fatias={fatias(false)} vazio="Nenhum erro neste bloco.">
          {a.para_estudar.length > 0 && (
            <ul className="flex flex-col gap-3 border-t border-borda pt-3">
              {a.para_estudar.map((p) => (
                <li key={p.ponto} className="flex flex-col gap-1.5">
                  <span className="text-[15px] font-semibold text-tinta">{p.ponto}</span>
                  <span className="flex flex-wrap gap-x-3 gap-y-1">
                    {p.questoes.map((q) => (
                      <Link key={q.item_id} href={enderecoDaSecao(a.modulo_id, q.secao_id, q.item_id)} className="inline-flex items-center gap-1 text-sm font-medium text-acento underline-offset-2 hover:underline">
                        <SquarePen aria-hidden="true" className="size-4" /> Resolução da {q.nome}
                      </Link>
                    ))}
                    {p.videos.map((v) => (
                      <Link key={v.item_id} href={enderecoDaSecao(a.modulo_id, v.secao_id, v.item_id)} className="inline-flex items-center gap-1 text-sm font-medium text-acento underline-offset-2 hover:underline">
                        <PlayCircle aria-hidden="true" className="size-4" /> {v.nome}
                      </Link>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Bloco>
      </div>
    </Pagina>
  );
}

type Fatia = { rotulo: string; valor: number; cor: string };

function Bloco({ titulo, icone, texto, fatias, vazio, children }: { titulo: string; icone: React.ReactNode; texto: string | null; fatias: Fatia[]; vazio: string; children?: React.ReactNode }) {
  return (
    <Cartao como="section" className="flex flex-col gap-4 p-5">
      <h2 className="flex items-center gap-2 font-titulo text-lg font-semibold text-tinta">
        {icone}
        {titulo}
      </h2>
      <div className="flex flex-wrap items-center gap-5">
        {fatias.length > 0 ? <Pizza fatias={fatias} rotulo={`${titulo} por assunto`} /> : null}
        <p className="min-w-0 flex-1 basis-56 text-[15px] leading-relaxed text-tinta-2">{texto ?? vazio}</p>
      </div>
      {children}
    </Cartao>
  );
}

/** A pizza por assunto, com a legenda ao lado: "60% Termoquímica". */
function Pizza({ fatias, rotulo }: { fatias: Fatia[]; rotulo: string }) {
  const total = fatias.reduce((s, f) => s + f.valor, 0);
  let inicio = 0;
  const gradiente = fatias
    .map((f) => {
      const fim = inicio + (360 * f.valor) / total;
      const parte = `${f.cor} ${inicio}deg ${fim}deg`;
      inicio = fim;
      return parte;
    })
    .join(", ");
  return (
    <div className="flex items-center gap-4" role="img" aria-label={`${rotulo}: ${fatias.map((f) => `${f.rotulo} ${Math.round((100 * f.valor) / total)}%`).join(", ")}`}>
      <span className="size-24 shrink-0 rounded-full" style={{ background: `conic-gradient(${gradiente})` }} aria-hidden="true" />
      <ul className="flex flex-col gap-1 text-[13px]" aria-hidden="true">
        {fatias.map((f) => (
          <li key={f.rotulo} className="flex items-center gap-1.5">
            <span className="size-2.5 shrink-0 rounded-sm" style={{ background: f.cor }} />
            <span className="font-semibold tabular-nums text-tinta">{Math.round((100 * f.valor) / total)}%</span>
            <span className="text-suave">{f.rotulo}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
