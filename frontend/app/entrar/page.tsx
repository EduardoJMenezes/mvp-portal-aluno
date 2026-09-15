"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { Aviso, Botao } from "@/components/ui";
import { api, useDados, type Usuario } from "@/lib/api";
import { destinoSeguro } from "@/lib/formato";
import { inicioDe, useSessao } from "@/lib/sessao";

export default function PaginaEntrar() {
  return (
    <Suspense>
      <Entrar />
    </Suspense>
  );
}

const PAPEL: Record<string, string> = { ADMIN: "Administrador", GERENCIADOR: "Gerenciador", ALUNO: "Aluno" };

function Entrar() {
  const router = useRouter();
  const volta = useSearchParams().get("volta");
  const { usuario, entrou } = useSessao();
  const config = useDados(() => api.sessaoConfig());
  const [email, setEmail] = useState("");
  const [senha, setSenha] = useState("");
  const [mostrarSenha, setMostrarSenha] = useState(false);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  const seguir = (u: Usuario) => {
    entrou(u);
    router.replace(u.trocar_senha ? "/conta/?trocar=1" : destinoSeguro(volta, inicioDe(u)));
  };

  useEffect(() => {
    if (usuario) router.replace(inicioDe(usuario));
  }, [usuario, router]);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setEnviando(true);
    try {
      seguir((await api.entrar(email.trim(), senha)).usuario);
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível entrar. Tente de novo.");
      setEnviando(false);
    }
  }

  async function entrarComoDemo(conta: string) {
    setErro("");
    setEnviando(true);
    try {
      seguir((await api.entrarDemo(conta)).usuario);
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível entrar.");
      setEnviando(false);
    }
  }

  const demo = config.dados?.modo_demo ? config.dados.contas_demo : [];

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <span className="flex size-9 items-center justify-center rounded-lg bg-acento font-bold text-white" aria-hidden="true">P</span>
          <span className="font-semibold text-tinta">Plataforma Educacional</span>
        </div>

        <div className="rounded-cartao border border-borda bg-papel p-6 shadow-suave sm:p-7">
          <h1 className="text-2xl font-semibold text-tinta">Entrar</h1>
          <p className="mt-1 text-[15px] text-suave">Use o e-mail cadastrado pela sua escola.</p>

          <form onSubmit={enviar} className="mt-6 flex flex-col gap-4" noValidate={false}>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="email" className="text-sm font-semibold text-tinta-2">E-mail</label>
              <input id="email" name="email" type="email" inputMode="email" autoComplete="username" required
                value={email} onChange={(e) => setEmail(e.target.value)} className="campo" />
            </div>
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="senha" className="text-sm font-semibold text-tinta-2">Senha</label>
                <button type="button" onClick={() => setMostrarSenha(!mostrarSenha)} className="text-sm font-medium text-acento hover:underline" aria-controls="senha">
                  {mostrarSenha ? "Ocultar" : "Mostrar"}
                </button>
              </div>
              <input id="senha" name="senha" type={mostrarSenha ? "text" : "password"} autoComplete="current-password" required
                value={senha} onChange={(e) => setSenha(e.target.value)} className="campo" />
            </div>

            {erro && <Aviso tom="erro">{erro}</Aviso>}

            <Botao type="submit" variante="primario" disabled={enviando || !email || !senha} className="mt-1 w-full">
              {enviando ? "Entrando…" : "Entrar"}
            </Botao>
          </form>

          <p className="mt-5 text-[13px] text-suave">Esqueceu a senha? Peça ao professor para gerar uma nova.</p>
        </div>

        {demo.length > 0 && (
          <section className="mt-5 rounded-cartao border border-atencao-borda bg-atencao-fundo p-4" aria-labelledby="titulo-demo">
            <h2 id="titulo-demo" className="text-sm font-semibold text-atencao">Modo demonstração</h2>
            <p className="mt-0.5 text-[13px] text-atencao">Entra direto nas contas de exemplo. Desligado em produção.</p>
            <ul className="mt-3 flex flex-col gap-2">
              {demo.map((conta) => (
                <li key={conta.email}>
                  <button type="button" disabled={enviando} onClick={() => void entrarComoDemo(conta.email)}
                    className="flex w-full items-center justify-between gap-3 rounded-md border border-atencao-borda bg-papel px-3 py-2 text-left hover:border-atencao disabled:opacity-60">
                    <span className="min-w-0">
                      <span className="block truncate text-[15px] font-medium text-tinta">{conta.nome}</span>
                      <span className="block truncate text-[13px] text-suave">{conta.turmas.length ? conta.turmas.join(", ") : conta.email}</span>
                    </span>
                    <span className="shrink-0 text-xs font-semibold text-suave">{PAPEL[conta.papel]}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </main>
  );
}
