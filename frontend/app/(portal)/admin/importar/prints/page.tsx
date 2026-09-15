"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useRef, useState, type PointerEvent } from "react";
import { EscolherPrints } from "@/components/Envio";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import { api, tokenDoLink, useDados, type LinkDeEnvio } from "@/lib/api";
import { LETRAS } from "@/lib/rotulos";

export default function PaginaDosPrints() {
  return (
    <Suspense>
      <ImportarPrints />
    </Suspense>
  );
}

function ImportarPrints() {
  const importacao = Number(useSearchParams().get("importacao")) || null;
  return importacao ? <Prints id={importacao} /> : <Envio />;
}

function Envio() {
  const router = useRouter();
  const [link, setLink] = useState<LinkDeEnvio | null>(null);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const [abrir, setAbrir] = useState("");

  async function gerar() {
    setErro("");
    setOcupado(true);
    try {
      setLink(await api.linkPrints());
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  async function enviar(prints: File[]) {
    if (!link) return;
    setErro("");
    setOcupado(true);
    try {
      await api.enviarPrints(tokenDoLink(link.link), prints);
      router.replace(`/admin/importar/prints/?importacao=${link.importacao_id}`);
    } catch (ex) {
      setErro((ex as Error).message);
      setOcupado(false);
    }
  }

  return (
    <Pagina
      titulo="Prints de questões"
      legenda="Os prints ficam no servidor. Crie as questões em rascunho (à mão ou pelo Claude) e recorte cada figura de dentro do print original."
      voltar={{ href: "/admin/importar/", rotulo: "Importar" }}
      estreita
    >
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {link ? (
        <Cartao className="flex flex-col gap-4 p-5">
          <TituloDeSecao>Envie os prints</TituloDeSecao>
          <EscolherPrints enviando={ocupado} aoEnviar={(prints) => void enviar(prints)} />
          <p className="text-[13px] text-suave">
            Outra pessoa vai enviar? Link de uso único, vale até {link.expira_em}: <code className="select-all break-all rounded-campo bg-canvas px-1.5 py-0.5">{link.link}</code>
          </p>
        </Cartao>
      ) : (
        <Cartao className="flex flex-col gap-4 p-5">
          <p className="text-[15px]">Cada envio ganha um número de importação; é por ele que os prints são achados depois.</p>
          <div>
            <Botao variante="primario" disabled={ocupado} onClick={() => void gerar()}>{ocupado ? "Preparando…" : "Enviar prints agora"}</Botao>
          </div>
          <form
            className="flex flex-wrap items-end gap-2 border-t border-borda pt-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (Number(abrir) > 0) router.push(`/admin/importar/prints/?importacao=${Number(abrir)}`);
            }}
          >
            <Campo rotulo="Já enviou? Abra pelo número da importação" className="min-w-56 flex-1">
              {(id) => <input id={id} type="number" min={1} value={abrir} onChange={(e) => setAbrir(e.target.value)} className="campo" />}
            </Campo>
            <Botao type="submit">Abrir</Botao>
          </form>
        </Cartao>
      )}
    </Pagina>
  );
}

function Prints({ id }: { id: number }) {
  const total = useDados(() => api.totalDePrints(id), [id]);
  const [atual, setAtual] = useState(1);

  return (
    <Pagina titulo={`Prints · importação #${id}`} legenda="Arraste sobre a figura para marcar o retângulo. O recorte sai do print original e as bordas se estendem até o desenho acabar." voltar={{ href: "/admin/importar/prints/", rotulo: "Prints" }}>
      <Estado {...total} linhas={2}>
        {(t) =>
          t.total_prints === 0 ? (
            <Vazio titulo="Nenhum print chegou ainda">Quando o envio terminar, recarregue esta página.</Vazio>
          ) : (
            <div className="grid gap-4 lg:grid-cols-[10rem_1fr]">
              <ol className="flex gap-2 overflow-x-auto lg:max-h-[75vh] lg:flex-col lg:overflow-y-auto">
                {Array.from({ length: t.total_prints }, (_, i) => i + 1).map((n) => (
                  <li key={n} className="shrink-0">
                    <button
                      type="button"
                      onClick={() => setAtual(n)}
                      aria-current={n === atual}
                      className={`block w-32 rounded-cartao border-2 bg-papel p-1 lg:w-full ${n === atual ? "border-acento" : "border-borda hover:border-suave"}`}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={`/api/admin/importacoes/${id}/prints/${n}`} alt="" loading="lazy" className="h-20 w-full object-contain" />
                      <span className="text-sm font-semibold tabular-nums">Print {n}</span>
                    </button>
                  </li>
                ))}
              </ol>
              <Recorte key={atual} importacao={id} numero={atual} />
            </div>
          )
        }
      </Estado>
    </Pagina>
  );
}

type Retangulo = [number, number, number, number];

function Recorte({ importacao, numero }: { importacao: number; numero: number }) {
  const rascunhos = useDados(() => api.questoes({ status: "RASCUNHO", limite: 200 }));
  const imagem = useRef<HTMLImageElement>(null);
  const inicio = useRef<[number, number] | null>(null);
  const [tamanho, setTamanho] = useState<[number, number] | null>(null);
  const [ret, setRet] = useState<Retangulo | null>(null);
  const [questao, setQuestao] = useState("");
  const [parte, setParte] = useState("ENUNCIADO");
  const [letra, setLetra] = useState("A");
  const [estender, setEstender] = useState(true);
  const [feitos, setFeitos] = useState<{ figura_id: number; questao: string }[]>([]);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  // Coordenadas na escala da vista servida (a do retângulo), não na da tela.
  const ponto = (e: PointerEvent): [number, number] => {
    const img = imagem.current!;
    const caixa = img.getBoundingClientRect();
    const x = Math.min(Math.max(e.clientX - caixa.left, 0), caixa.width) * (img.naturalWidth / caixa.width);
    const y = Math.min(Math.max(e.clientY - caixa.top, 0), caixa.height) * (img.naturalHeight / caixa.height);
    return [Math.round(x), Math.round(y)];
  };

  const arrastar = (e: PointerEvent) => {
    if (!inicio.current) return;
    const [x, y] = ponto(e);
    const [x0, y0] = inicio.current;
    setRet([Math.min(x0, x), Math.min(y0, y), Math.max(x0, x), Math.max(y0, y)]);
  };

  async function recortar() {
    if (!ret || !questao) return;
    setErro("");
    setOcupado(true);
    try {
      const r = await api.recortar(importacao, {
        print: numero,
        questao: Number(questao),
        retangulo: ret,
        parte,
        alternativa: parte === "ALTERNATIVA" ? letra : undefined,
        estender,
      });
      setFeitos((f) => [{ figura_id: r.figura_id, questao }, ...f]);
      setRet(null);
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  const valido = ret && ret[2] - ret[0] >= 4 && ret[3] - ret[1] >= 4;

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Cartao className="overflow-auto p-3">
        <div
          className="relative mx-auto w-fit cursor-crosshair touch-none select-none"
          onPointerDown={(e) => {
            (e.target as Element).setPointerCapture(e.pointerId);
            inicio.current = ponto(e);
            setRet(null);
          }}
          onPointerMove={arrastar}
          onPointerUp={(e) => {
            arrastar(e);
            inicio.current = null;
          }}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            ref={imagem}
            src={`/api/admin/importacoes/${importacao}/prints/${numero}`}
            alt={`Print ${numero}`}
            draggable={false}
            onLoad={(e) => setTamanho([e.currentTarget.naturalWidth, e.currentTarget.naturalHeight])}
            className="block max-h-[75vh] max-w-full"
          />
          {ret && tamanho && (
            <div
              aria-hidden="true"
              className="pointer-events-none absolute border-2 border-acento bg-acento/15"
              style={{
                left: `${(ret[0] / tamanho[0]) * 100}%`,
                top: `${(ret[1] / tamanho[1]) * 100}%`,
                width: `${((ret[2] - ret[0]) / tamanho[0]) * 100}%`,
                height: `${((ret[3] - ret[1]) / tamanho[1]) * 100}%`,
              }}
            />
          )}
        </div>
      </Cartao>

      <Cartao className="flex flex-col gap-4 p-5">
        <fieldset className="flex flex-wrap items-end gap-2">
          <legend className="mb-1.5 text-sm font-semibold text-tinta-2">
            Retângulo [x0, y0, x1, y1]{tamanho ? ` · print de ${tamanho[0]}×${tamanho[1]} px` : ""}
          </legend>
          {(["x0", "y0", "x1", "y1"] as const).map((nome, i) => (
            <label key={nome} className="flex flex-col text-[13px] text-suave">
              {nome}
              <input
                type="number"
                min={0}
                value={ret?.[i] ?? ""}
                onChange={(e) => {
                  const novo = [...(ret ?? [0, 0, 0, 0])] as Retangulo;
                  novo[i] = Number(e.target.value);
                  setRet(novo);
                }}
                className="campo w-24 tabular-nums"
              />
            </label>
          ))}
        </fieldset>
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr_auto]">
          <Campo rotulo="Questão em rascunho">
            {(cid) => (
              <select id={cid} value={questao} onChange={(e) => setQuestao(e.target.value)} className="campo">
                <option value="">Escolha</option>
                {rascunhos.dados?.map((q) => (
                  <option key={q.questao_id} value={q.questao_id}>
                    #{q.questao_id} {q.imagem_pendente ? "· imagem pendente · " : "· "}{q.enunciado.replace(/[#*_$\\!\[\]()]/g, "").slice(0, 70)}
                  </option>
                ))}
              </select>
            )}
          </Campo>
          <Campo rotulo="Parte">
            {(cid) => (
              <select id={cid} value={parte} onChange={(e) => setParte(e.target.value)} className="campo">
                <option value="ENUNCIADO">Enunciado</option>
                <option value="ALTERNATIVA">Alternativa</option>
                <option value="RESOLUCAO">Resolução</option>
              </select>
            )}
          </Campo>
          {parte === "ALTERNATIVA" && (
            <Campo rotulo="Letra">
              {(cid) => (
                <select id={cid} value={letra} onChange={(e) => setLetra(e.target.value)} className="campo">
                  {LETRAS.map((l) => <option key={l} value={l}>{l}</option>)}
                </select>
              )}
            </Campo>
          )}
        </div>
        {rascunhos.dados?.length === 0 && (
          <Aviso tom="atencao">
            Nenhuma questão em rascunho. Crie em <Link href="/admin/questoes/editar/" className="font-semibold underline">Nova questão</Link> ou num simulado novo, com a marca ![](figura:pendente) onde a figura entra.
          </Aviso>
        )}
        <label className="flex items-center gap-2 text-[15px]">
          <input type="checkbox" checked={estender} onChange={(e) => setEstender(e.target.checked)} className="size-4 accent-acento" />
          Estender as bordas até o desenho acabar
        </label>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <div className="flex flex-wrap gap-2">
          <Botao variante="primario" disabled={!valido || !questao || ocupado} onClick={() => void recortar()}>{ocupado ? "Recortando…" : "Recortar para a questão"}</Botao>
          {ret && <Botao onClick={() => setRet(null)}>Limpar seleção</Botao>}
        </div>
      </Cartao>

      {feitos.length > 0 && (
        <Cartao className="flex flex-col gap-3 p-5">
          <TituloDeSecao>Recortes deste print</TituloDeSecao>
          <ul className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
            {feitos.map((f) => (
              <li key={f.figura_id} className="flex flex-col gap-2 rounded-cartao border border-borda p-2">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={`/api/aluno/figuras/${f.figura_id}`} alt={`Figura ${f.figura_id}`} className="h-28 w-full object-contain" />
                <BotaoLink tamanho="pequeno" href={`/admin/questoes/editar/?id=${f.questao}`}>Questão #{f.questao}</BotaoLink>
              </li>
            ))}
          </ul>
        </Cartao>
      )}
    </div>
  );
}
