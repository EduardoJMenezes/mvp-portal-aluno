"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Pagina } from "@/components/ui";
import { api } from "@/lib/api";
import { inicioDe, useSessao, useUsuario } from "@/lib/sessao";

export default function PaginaDaConta() {
  return (
    <Suspense>
      <Conta />
    </Suspense>
  );
}

const PAPEL: Record<string, string> = { ADMIN: "Administrador", GERENCIADOR: "Gerenciador", ALUNO: "Aluno" };

function Conta() {
  const usuario = useUsuario();
  const { entrou, sair } = useSessao();
  const router = useRouter();
  const pedidoPeloEndereco = useSearchParams().get("trocar") === "1";
  const obrigatoria = usuario.trocar_senha || pedidoPeloEndereco;

  const [atual, setAtual] = useState("");
  const [nova, setNova] = useState("");
  const [confirmacao, setConfirmacao] = useState("");
  const [erro, setErro] = useState("");
  const [sucesso, setSucesso] = useState(false);
  const [enviando, setEnviando] = useState(false);

  async function trocar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setSucesso(false);
    if (nova.length < 10) return setErro("A nova senha precisa de pelo menos 10 caracteres.");
    if (nova !== confirmacao) return setErro("A confirmação não bate com a nova senha.");
    setEnviando(true);
    try {
      const { usuario: atualizado } = await api.trocarSenha(atual, nova);
      entrou(atualizado);
      setAtual("");
      setNova("");
      setConfirmacao("");
      setSucesso(true);
      if (usuario.trocar_senha) router.replace(inicioDe(atualizado));
    } catch (ex) {
      setErro(ex instanceof Error ? ex.message : "Não foi possível trocar a senha.");
    } finally {
      setEnviando(false);
    }
  }

  return (
    <Pagina titulo="Minha conta" estreita>
      {obrigatoria && usuario.trocar_senha && (
        <Aviso tom="atencao" titulo="Troque a senha temporária para continuar">
          Esta senha foi criada pelo professor. Escolha uma sua para liberar o portal.
        </Aviso>
      )}

      <Cartao className="p-6">
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Nome</dt>
            <dd className="mt-0.5 text-[15px] text-tinta">{usuario.nome}</dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wide text-suave">E-mail</dt>
            <dd className="mt-0.5 break-all text-[15px] text-tinta">{usuario.email}</dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Papel</dt>
            <dd className="mt-0.5 text-[15px] text-tinta">{PAPEL[usuario.papel]}</dd>
          </div>
          {usuario.turmas.length > 0 && (
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wide text-suave">Turmas</dt>
              <dd className="mt-0.5 text-[15px] text-tinta">{usuario.turmas.join(", ")}</dd>
            </div>
          )}
        </dl>
      </Cartao>

      <Cartao className="p-6">
        <h2 className="text-lg font-semibold text-tinta">Trocar senha</h2>
        <p className="mt-1 text-[15px] text-suave">Pelo menos 10 caracteres. Uma frase curta é mais fácil de lembrar e mais difícil de adivinhar. Trocar a senha encerra as outras sessões.</p>
        <form onSubmit={trocar} className="mt-5 flex flex-col gap-4">
          <Campo rotulo={usuario.trocar_senha ? "Senha temporária" : "Senha atual"}>
            {(id) => <input id={id} type="password" autoComplete="current-password" required value={atual} onChange={(e) => setAtual(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Nova senha">
            {(id) => <input id={id} type="password" autoComplete="new-password" required minLength={10} maxLength={72} value={nova} onChange={(e) => setNova(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Confirme a nova senha">
            {(id) => <input id={id} type="password" autoComplete="new-password" required value={confirmacao} onChange={(e) => setConfirmacao(e.target.value)} className="campo" />}
          </Campo>
          {erro && <Aviso tom="erro">{erro}</Aviso>}
          {sucesso && <Aviso tom="sucesso">Senha trocada. As outras sessões desta conta foram encerradas.</Aviso>}
          <div className="flex flex-wrap gap-2">
            <Botao type="submit" variante="primario" disabled={enviando || !atual || !nova || !confirmacao}>
              {enviando ? "Salvando…" : "Trocar senha"}
            </Botao>
            <Botao onClick={() => void sair()}>Sair</Botao>
          </div>
        </form>
      </Cartao>
    </Pagina>
  );
}
