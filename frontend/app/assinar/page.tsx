"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { Moldura } from "@/components/Moldura";
import { Aviso, Botao } from "@/components/ui";
import { api, useDados, type PlanoPublico } from "@/lib/api";
import { dataCurta, plural, reais } from "@/lib/formato";

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
        <p className="text-suave">Carregando…</p>
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

function precoPorExtenso(p: PlanoPublico): { valor: string; detalhe: string } {
  if (p.tipo === "MENSAL") return { valor: reais(p.preco_centavos), detalhe: "por mês, no cartão de crédito" };
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
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);
  const preco = precoPorExtenso(plano);

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
      <p className="mt-3">
        <span className="text-3xl font-semibold tabular-nums text-tinta">{preco.valor}</span>
        <span className="ml-2 text-[15px] text-suave">{preco.detalhe}</span>
      </p>

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

        {erro && <Aviso tom="erro">{erro}</Aviso>}

        <Botao type="submit" variante="primario" disabled={enviando} className="w-full">
          {enviando ? "Abrindo o pagamento…" : "Ir para o pagamento"}
        </Botao>
        <p className="text-[13px] text-suave">
          O pagamento é feito na página segura do Asaas: o cartão é digitado lá, nunca aqui. Pago, você cria sua senha e já
          entra na turma.
        </p>
      </form>
    </div>
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
