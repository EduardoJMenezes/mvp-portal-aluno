"use client";

import Image from "next/image";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { Aviso, Botao } from "@/components/ui";
import { api, useDados, type Usuario } from "@/lib/api";
import { destinoSeguro } from "@/lib/formato";
import { inicioDe, useSessao } from "@/lib/sessao";
import banner from "@/marca/banner-vertical.webp";

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
    <main className="min-h-dvh bg-canvas lg:grid lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      {/*
        O banner da marca. O logo e o rosto ficam nos dois terços de cima da arte, então é dali
        que o recorte parte: no celular ele vira a faixa do topo, e no computador, a coluna
        inteira. O terço de baixo pode ser cortado sem perder nada.
      */}
      <div className="relative isolate h-[44svh] max-h-96 min-h-64 overflow-hidden bg-tinta lg:sticky lg:top-0 lg:h-dvh lg:max-h-none">
        <Image
          src={banner}
          alt="Professor Rodrigo Melo, de braços cruzados, diante de vidrarias de laboratório, sob o logo Rodrigo Melo"
          priority
          sizes="(min-width: 1024px) 45vw, 100vw"
          className="absolute inset-0 size-full object-cover object-[50%_6%] lg:object-top"
        />
        <div className="absolute inset-x-0 bottom-0 hidden bg-gradient-to-t from-tinta via-tinta/85 to-transparent px-10 pb-10 pt-40 text-white lg:block xl:px-14 xl:pb-14">
          <p className="font-titulo text-[22px] uppercase leading-snug tracking-[0.28em] xl:text-2xl">
            <span className="font-light">Química para</span>
            <br />
            <span className="font-bold text-ceu">grandes conquistas</span>
          </p>
          <p className="mt-5 max-w-sm border-t-2 border-ceu pt-5 text-lg text-white/90">Do conteúdo à aprovação. Tudo em um só lugar.</p>
        </div>
      </div>

      {/* No celular o formulário sobe por cima da base do banner, como uma folha. */}
      <div className="relative -mt-6 flex flex-col items-center rounded-t-3xl bg-canvas px-5 pb-12 pt-8 sm:px-8 lg:mt-0 lg:justify-center lg:rounded-none lg:py-12">
        <div className="w-full max-w-sm">
          <h1 className="text-[28px] font-bold tracking-[-0.02em] text-tinta">Entrar</h1>
          <p className="mt-1 text-[15px] text-suave">Use o e-mail cadastrado pela sua escola.</p>

          <form onSubmit={enviar} className="mt-6 flex flex-col gap-4" noValidate={false}>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="email" className="text-sm font-semibold text-tinta-2">E-mail</label>
              <input id="email" name="email" type="email" inputMode="email" autoComplete="username" autoCapitalize="none" spellCheck={false} required
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

            <Botao type="submit" variante="primario" disabled={enviando} className="mt-1 w-full py-3">
              {enviando ? "Entrando…" : "Entrar"}
            </Botao>
          </form>

          <p className="mt-5 text-[13px] text-suave">Esqueceu a senha? Peça ao professor para gerar uma nova.</p>

          {demo.length > 0 && (
            <section className="mt-8 rounded-cartao border border-atencao-borda bg-atencao-fundo p-4" aria-labelledby="titulo-demo">
              <h2 id="titulo-demo" className="font-sans text-sm font-semibold text-atencao">Modo demonstração</h2>
              <p className="mt-0.5 text-[13px] text-atencao">Entra direto nas contas de exemplo. Desligado em produção.</p>
              <ul className="mt-3 flex flex-col gap-2">
                {demo.map((conta) => (
                  <li key={conta.email}>
                    <button type="button" disabled={enviando} onClick={() => void entrarComoDemo(conta.email)}
                      className="flex w-full items-center justify-between gap-3 rounded-campo border border-atencao-borda bg-papel px-3 py-2 text-left hover:border-atencao disabled:opacity-60">
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
      </div>
    </main>
  );
}
