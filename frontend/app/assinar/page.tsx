"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { Moldura } from "@/components/Moldura";
import { Aviso, Botao, Carregando } from "@/components/ui";
import { api, useDados, type PlanoPublico } from "@/lib/api";
import { dataCurta, dia, plural, reais } from "@/lib/formato";

// O link divulgado é /assinar/<plano>; o backend redireciona para cá com ?plano=.

export default function PaginaAssinar() {
  return (
    <Suspense>
      <Assinar />
    </Suspense>
  );
}

function Assinar() {
  const link = useSearchParams().get("plano") ?? "";
  const plano = useDados(() => (link ? api.planoPublico(link) : Promise.reject(new Error("Link sem plano."))), [link]);

  return (
    <Moldura>
      {plano.carregando ? (
        <Carregando linhas={2} />
      ) : plano.erro || !plano.dados ? (
        <div className="rounded-cartao border border-borda bg-papel p-6">
          <h1 className="text-xl font-semibold text-tinta">Plano indisponível</h1>
          <p className="mt-1 text-[15px] text-suave">{plano.erro || "Este plano não está à venda."}</p>
        </div>
      ) : (
        <Formulario plano={plano.dados} />
      )}
    </Moldura>
  );
}

type Forma = "PIX" | "CARTAO";

/** As formas que o plano oferece hoje (pagamento único). */
function formasDoDia(p: PlanoPublico): Forma[] {
  if (p.tipo !== "UNICO") return [];
  const formas: Forma[] = [];
  if (p.cartao_centavos) formas.push("CARTAO");
  if (p.pix_centavos) formas.push("PIX");
  return formas;
}

function parcelaDoCartao(p: PlanoPublico): string {
  const n = p.cartao_parcelas ?? 1;
  return n > 1 ? `${n}x de ${reais(Math.round((p.cartao_centavos ?? 0) / n))}` : reais(p.cartao_centavos ?? 0);
}

function precoPorExtenso(p: PlanoPublico): { valor: string; detalhe: string } {
  if (p.tipo === "MENSAL")
    return p.mensalidades
      ? {
          valor: `${p.mensalidades}x de ${reais(p.preco_centavos)}`,
          detalhe: "uma por mês, no cartão de crédito",
        }
      : { valor: reais(p.preco_centavos), detalhe: "por mês, no cartão de crédito" };
  if (p.parcelas_max > 1)
    return {
      valor: reais(p.preco_centavos),
      detalhe: `à vista no Pix ou em até ${p.parcelas_max}x de ${reais(Math.ceil(p.preco_centavos / p.parcelas_max))} no cartão`,
    };
  return { valor: reais(p.preco_centavos), detalhe: "à vista, no Pix ou no cartão" };
}

/** 52998224725 → 529.982.247-25, enquanto digita. */
function mascaraCpf(texto: string): string {
  const d = texto.replace(/\D/g, "").slice(0, 11);
  return d
    .replace(/^(\d{3})(\d)/, "$1.$2")
    .replace(/^(\d{3})\.(\d{3})(\d)/, "$1.$2.$3")
    .replace(/\.(\d{3})(\d)/, ".$1-$2");
}

function mascaraCep(texto: string): string {
  const d = texto.replace(/\D/g, "").slice(0, 8);
  return d.length > 5 ? `${d.slice(0, 5)}-${d.slice(5)}` : d;
}

function mascaraCelular(texto: string): string {
  const d = texto.replace(/\D/g, "").slice(0, 11);
  if (d.length <= 2) return d.length ? `(${d}` : "";
  if (d.length <= 7) return `(${d.slice(0, 2)}) ${d.slice(2)}`;
  return `(${d.slice(0, 2)}) ${d.slice(2, d.length - 4)}-${d.slice(-4)}`;
}

function Formulario({ plano }: { plano: PlanoPublico }) {
  const [nome, setNome] = useState("");
  const [email, setEmail] = useState("");
  const [cpf, setCpf] = useState("");
  const [celular, setCelular] = useState("");
  const [cep, setCep] = useState("");
  const [numero, setNumero] = useState("");
  const [complemento, setComplemento] = useState("");
  const formas = formasDoDia(plano);
  const [forma, setForma] = useState<Forma | null>(formas[0] ?? null);
  const [aceite, setAceite] = useState(false);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);
  const preco = precoPorExtenso(plano);
  const matricula = plano.matricula_centavos ?? 0;
  const botao =
    forma === "PIX"
      ? `Pagar ${reais((plano.pix_centavos ?? 0) + matricula)} no Pix`
      : forma === "CARTAO" && matricula
        ? `Pagar a matrícula (${reais(matricula)})`
        : "Ir para o pagamento";

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setEnviando(true);
    try {
      const { checkout } = await api.comprar(plano.link, {
        nome: nome.trim(),
        email: email.trim(),
        cpf,
        celular: celular || undefined,
        cep,
        numero: numero.trim(),
        complemento: complemento.trim() || undefined,
        forma: forma ?? undefined,
        aceite_termos: aceite,
      });
      window.location.assign(checkout);
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível seguir para o pagamento.");
      setEnviando(false);
    }
  }

  return (
    <div className="rounded-cartao border border-borda bg-papel p-6 shadow-suave sm:p-7">
      <h1 className="text-2xl font-semibold text-tinta [text-wrap:balance]">{plano.nome}</h1>
      {plano.tipo === "UNICO" ? (
        <EscolhaDaForma plano={plano} formas={formas} forma={forma} aoEscolher={setForma} />
      ) : (
        <p className="mt-3">
          <span className="text-3xl font-semibold tabular-nums text-tinta">{preco.valor}</span>
          <span className="ml-2 text-[15px] text-suave">{preco.detalhe}</span>
        </p>
      )}

      <ul className="mt-4 flex flex-col gap-1.5 border-t border-borda pt-4">
        {plano.turmas.map((t) => (
          <li key={t.nome} className="text-[15px] text-tinta-2">
            <span className="font-semibold text-tinta">{t.nome}</span>
            <span className="text-suave">
              {" "}
              · {plural(t.modulos, "módulo")} · {plural(t.aulas, "vídeo")}
            </span>
          </li>
        ))}
        <li className="text-[13px] text-suave">
          Simulados, aulas ao vivo e materiais da turma incluídos
          {plano.acesso_ate ? `, com acesso até ${dataCurta(plano.acesso_ate + "T12:00:00")}.` : "."}
        </li>
      </ul>

      <form onSubmit={enviar} className="mt-6 flex flex-col gap-4">
        <CampoPublico id="nome" rotulo="Nome completo">
          <input id="nome" className="campo" autoComplete="name" required value={nome} maxLength={200}
            onChange={(e) => setNome(e.target.value)} />
        </CampoPublico>
        <CampoPublico id="email" rotulo="E-mail" dica="É o seu login na plataforma.">
          <input id="email" type="email" inputMode="email" className="campo" autoComplete="email" required value={email}
            maxLength={200} onChange={(e) => setEmail(e.target.value)} />
        </CampoPublico>
        <div className="grid gap-4 sm:grid-cols-2">
          <CampoPublico id="cpf" rotulo="CPF">
            <input id="cpf" inputMode="numeric" className="campo tabular-nums" required value={cpf}
              placeholder="000.000.000-00" onChange={(e) => setCpf(mascaraCpf(e.target.value))} />
          </CampoPublico>
          <CampoPublico id="celular" rotulo="Celular (opcional)">
            <input id="celular" type="tel" inputMode="tel" className="campo tabular-nums" autoComplete="tel" value={celular}
              placeholder="(81) 99999-9999" onChange={(e) => setCelular(mascaraCelular(e.target.value))} />
          </CampoPublico>
          <CampoPublico id="cep" rotulo="CEP">
            <input id="cep" inputMode="numeric" className="campo tabular-nums" autoComplete="postal-code" required value={cep}
              placeholder="00000-000" onChange={(e) => setCep(mascaraCep(e.target.value))} />
          </CampoPublico>
          <CampoPublico id="numero" rotulo="Número" dica="Do seu endereço; se não tiver, S/N.">
            <input id="numero" className="campo" required value={numero} maxLength={10}
              onChange={(e) => setNumero(e.target.value)} />
          </CampoPublico>
        </div>
        {plano.tipo === "UNICO" && (
          <CampoPublico id="complemento" rotulo="Complemento (opcional)" dica="O material impresso vai para este endereço.">
            <input id="complemento" className="campo" autoComplete="address-line2" value={complemento} maxLength={60}
              placeholder="apto 302, bloco B" onChange={(e) => setComplemento(e.target.value)} />
          </CampoPublico>
        )}

        <label className="flex cursor-pointer items-start gap-2.5 text-[15px] text-tinta">
          <input type="checkbox" className="mt-1" required checked={aceite} onChange={(e) => setAceite(e.target.checked)} />
          <span>
            Li e aceito os{" "}
            <a href="/termos/" target="_blank" rel="noopener" className="font-semibold text-acento underline">
              termos de matrícula
            </a>
            .
          </span>
        </label>

        {erro && <Aviso tom="erro">{erro}</Aviso>}

        <Botao type="submit" variante="primario" ocupado={enviando} className="w-full">
          {enviando ? "Abrindo o pagamento…" : botao}
        </Botao>
        {forma === "CARTAO" && matricula > 0 && (
          <p className="text-[13px] text-tinta-2">
            Paga a matrícula, você volta para cá e paga o curso no cartão: {parcelaDoCartao(plano)}.
          </p>
        )}
        <p className="text-[13px] text-suave">
          O pagamento é feito na página segura do Asaas: o cartão é digitado lá, nunca aqui. Pago, você cria sua senha e já
          entra na turma.
        </p>
      </form>
    </div>
  );
}

/** Como o bloco "Escolha como pagar" da página de vendas: cartão e Pix lado a lado, e a matrícula embaixo. */
function EscolhaDaForma({
  plano,
  formas,
  forma,
  aoEscolher,
}: {
  plano: PlanoPublico;
  formas: Forma[];
  forma: Forma | null;
  aoEscolher: (f: Forma) => void;
}) {
  const economia =
    plano.pix_centavos && plano.cartao_centavos && plano.cartao_centavos > plano.pix_centavos
      ? plano.cartao_centavos - plano.pix_centavos
      : 0;
  return (
    <fieldset className="mt-4">
      <legend className="mb-2 text-sm font-semibold text-tinta-2">Escolha como pagar</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {formas.map((f) => {
          const marcada = forma === f;
          return (
            <label
              key={f}
              className={`flex cursor-pointer flex-col gap-0.5 rounded-lg border p-4 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                marcada ? "border-acento bg-lilas" : "border-borda bg-papel"
              }`}
            >
              <input type="radio" name="forma" className="sr-only" checked={marcada} onChange={() => aoEscolher(f)} />
              <span className="flex flex-wrap items-center gap-2 text-[13px] text-suave">
                {f === "CARTAO" ? "Cartão de crédito" : "Pix à vista"}
                {f === "PIX" && economia > 0 && (
                  <span className="rounded-full bg-acento px-2 py-0.5 text-[12px] font-semibold text-white">
                    economize {reais(economia)}
                  </span>
                )}
              </span>
              <span className="text-2xl font-semibold tabular-nums text-tinta">
                {f === "CARTAO" ? parcelaDoCartao(plano) : reais(plano.pix_centavos ?? 0)}
              </span>
              <span className="text-[13px] text-suave">
                {f === "CARTAO" ? ((plano.cartao_parcelas ?? 1) > 1 ? "sem juros" : "à vista") : "pagamento único"}
              </span>
            </label>
          );
        })}
      </div>
      {plano.matricula_centavos ? (
        <p className="mt-2 text-[15px] text-tinta-2">
          + Matrícula: <strong className="tabular-nums text-tinta">{reais(plano.matricula_centavos)}</strong>
          <span className="text-suave">
            {" "}
            · Pix{plano.matricula_parcelas_max > 1 ? ` ou até ${plano.matricula_parcelas_max}x no cartão` : " ou cartão"}
          </span>
        </p>
      ) : null}
      {plano.preco_vale_ate && (
        <p className="mt-1 text-[13px] text-suave">Estes valores valem até {dia(plano.preco_vale_ate)}.</p>
      )}
    </fieldset>
  );
}

function CampoPublico({ id, rotulo, dica, children }: { id: string; rotulo: string; dica?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-semibold text-tinta-2">
        {rotulo}
      </label>
      {children}
      {dica && <p className="text-[13px] text-suave">{dica}</p>}
    </div>
  );
}
