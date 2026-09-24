"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { Moldura } from "@/components/Moldura";
import { Aviso, Botao, BotaoLink } from "@/components/ui";
import { api, type SituacaoDoPedido } from "@/lib/api";

// A volta do checkout do Asaas: ?pedido=<token>. O aviso de pago pode chegar depois do aluno,
// então a página pergunta de novo até ele chegar.

export default function PaginaPronto() {
  return (
    <Suspense>
      <Pronto />
    </Suspense>
  );
}

const INTERVALO_MS = 3000;

function Pronto() {
  const token = useSearchParams().get("pedido") ?? "";
  const [situacao, setSituacao] = useState<SituacaoDoPedido | null>(null);
  const [erro, setErro] = useState("");

  useEffect(() => {
    if (!token) {
      setErro("Link sem pedido.");
      return;
    }
    let ativo = true;
    let espera: ReturnType<typeof setTimeout>;
    async function perguntar() {
      try {
        const s = await api.situacaoDoPedido(token);
        if (!ativo) return;
        setSituacao(s);
        if (s.status === "AGUARDANDO") espera = setTimeout(perguntar, INTERVALO_MS);
      } catch (ex) {
        if (ativo) setErro(ex instanceof Error ? ex.message : "Não foi possível consultar o pedido.");
      }
    }
    void perguntar();
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [token]);

  return (
    <Moldura>
      <div className="rounded-cartao border border-borda bg-papel p-6 shadow-suave sm:p-7" aria-live="polite">
        {erro ? (
          <>
            <h1 className="text-xl font-semibold text-tinta">Pedido não encontrado</h1>
            <p className="mt-1 text-[15px] text-suave">{erro}</p>
          </>
        ) : !situacao || situacao.status === "AGUARDANDO" ? (
          <>
            <h1 className="text-xl font-semibold text-tinta">Confirmando seu pagamento…</h1>
            <p className="mt-1 text-[15px] text-suave">
              Costuma levar alguns segundos. No Pix, pode levar um pouco mais. Deixe esta página aberta: ela avança sozinha.
            </p>
          </>
        ) : situacao.status === "PAGO" && situacao.pode_criar_senha ? (
          <CriarSenha token={token} situacao={situacao} />
        ) : situacao.status === "PAGO" ? (
          <>
            <h1 className="text-xl font-semibold text-tinta">Pagamento confirmado!</h1>
            <p className="mt-1 text-[15px] text-suave">
              {situacao.ja_tinha_conta
                ? `Você já tinha conta com ${situacao.email}: a turma nova já está nela. Entre com sua senha de sempre.`
                : `Sua conta ${situacao.email} já tem senha. É só entrar.`}
            </p>
            <BotaoLink variante="primario" href="/entrar/" className="mt-5 w-full">
              Entrar
            </BotaoLink>
          </>
        ) : (
          <>
            <h1 className="text-xl font-semibold text-tinta">Pagamento não concluído</h1>
            <p className="mt-1 text-[15px] text-suave">
              Este pedido não foi pago. Se quiser tentar de novo, volte ao link do plano.
            </p>
          </>
        )}
      </div>
    </Moldura>
  );
}

function CriarSenha({ token, situacao }: { token: string; situacao: SituacaoDoPedido }) {
  const [senha, setSenha] = useState("");
  const [mostrar, setMostrar] = useState(false);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setEnviando(true);
    try {
      await api.criarSenhaDoPedido(token, senha);
      // Recarrega de verdade: a sessão nova chega pelo cookie, e o portal lê quem entrou na abertura.
      window.location.assign("/");
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível criar a senha.");
      setEnviando(false);
    }
  }

  return (
    <>
      <h1 className="text-xl font-semibold text-tinta">Pronto! Pagamento confirmado.</h1>
      <p className="mt-1 text-[15px] text-suave">
        Crie a senha da sua conta <strong className="text-tinta">{situacao.email}</strong> para entrar em{" "}
        {situacao.plano}.
      </p>
      <form onSubmit={enviar} className="mt-5 flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center justify-between">
            <label htmlFor="senha" className="text-sm font-semibold text-tinta-2">
              Nova senha
            </label>
            <button type="button" onClick={() => setMostrar(!mostrar)} className="text-sm font-medium text-acento hover:underline" aria-controls="senha">
              {mostrar ? "Ocultar" : "Mostrar"}
            </button>
          </div>
          <input id="senha" type={mostrar ? "text" : "password"} autoComplete="new-password" required minLength={10}
            value={senha} onChange={(e) => setSenha(e.target.value)} className="campo" />
          <p className="text-[13px] text-suave">Pelo menos 10 caracteres. Evite seu nome e seu e-mail.</p>
        </div>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <Botao type="submit" variante="primario" disabled={enviando || !senha} className="w-full">
          {enviando ? "Entrando…" : "Criar senha e entrar"}
        </Botao>
      </form>
    </>
  );
}
