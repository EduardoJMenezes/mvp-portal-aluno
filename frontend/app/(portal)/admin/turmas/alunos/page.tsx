"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, SegredoUmaVez, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Aluno } from "@/lib/api";

export default function PaginaDosAlunos() {
  return (
    <Suspense>
      <Alunos />
    </Suspense>
  );
}

type Segredo = { titulo: string; valor: string; para: string };

function Alunos() {
  const turma = Number(useSearchParams().get("turma"));
  const alunos = useDados(() => api.alunosDaTurma(turma), [turma]);
  const [segredo, setSegredo] = useState<Segredo | null>(null);
  const [erro, setErro] = useState("");
  const [dialogo, confirmar] = useConfirmar();

  async function redefinir(aluno: Aluno) {
    const sim = await confirmar({
      titulo: `Nova senha para ${aluno.nome}?`,
      texto: "A senha atual deixa de valer e as sessões abertas caem. A nova é temporária: o aluno troca no primeiro acesso.",
      confirmar: "Gerar nova senha",
    });
    if (!sim) return;
    setErro("");
    try {
      const r = await api.redefinirSenha(aluno.id);
      setSegredo({ titulo: `Senha temporária de ${aluno.nome}`, valor: r.senha_temporaria, para: aluno.email });
      void alunos.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  async function tirar(aluno: Aluno) {
    const sim = await confirmar({
      titulo: `Tirar ${aluno.nome} da turma?`,
      texto: "O aluno perde o acesso ao curso e aos simulados desta turma na hora. As provas que já fez continuam no histórico.",
      confirmar: "Tirar da turma",
      perigo: true,
    });
    if (!sim) return;
    setErro("");
    try {
      await api.desmatricular(turma, aluno.id);
      void alunos.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <Pagina
      titulo={alunos.dados ? `Alunos · ${alunos.dados.turma}` : "Alunos"}
      legenda="Quem está matriculado vê o curso publicado e os simulados desta turma."
      voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}
    >
      {dialogo}
      <Matricular turma={turma} aoMatricular={(r) => { if (r) setSegredo(r); void alunos.recarregar(); }} />
      {segredo && (
        <SegredoUmaVez titulo={segredo.titulo} valor={segredo.valor}>
          Passe para {segredo.para} por um canal seguro. Ela aparece só agora e o aluno troca no primeiro acesso.
        </SegredoUmaVez>
      )}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <Estado {...alunos} linhas={3}>
        {(dados) =>
          dados.alunos.length === 0 ? (
            <Vazio titulo="Nenhum aluno nesta turma">Matricule pelo e-mail acima.</Vazio>
          ) : (
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[40rem]">
                <thead>
                  <tr>
                    <th scope="col">Aluno</th>
                    <th scope="col">E-mail</th>
                    <th scope="col">Acesso</th>
                    <th scope="col"><span className="sr-only">Ações</span></th>
                  </tr>
                </thead>
                <tbody>
                  {dados.alunos.map((a) => (
                    <tr key={a.id}>
                      <td className="font-medium">{a.nome}</td>
                      <td className="break-all text-suave">{a.email}</td>
                      <td>{a.senha_temporaria ? <Etiqueta tom="atencao">Senha temporária</Etiqueta> : <Etiqueta tom="sucesso">Ativo</Etiqueta>}</td>
                      <td>
                        <div className="flex flex-wrap justify-end gap-x-3 gap-y-1 text-sm">
                          <Link href={`/admin/alunos/?aluno=${a.id}`} className="font-semibold text-acento hover:underline">Desempenho</Link>
                          <button type="button" onClick={() => void redefinir(a)} className="font-semibold text-acento hover:underline">Nova senha</button>
                          <button type="button" onClick={() => void tirar(a)} className="font-semibold text-erro hover:underline">Tirar da turma</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Cartao>
          )
        }
      </Estado>
    </Pagina>
  );
}

function Matricular({ turma, aoMatricular }: { turma: number; aoMatricular: (segredo: Segredo | null) => void }) {
  const [email, setEmail] = useState("");
  const [nome, setNome] = useState("");
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    setErro("");
    setAviso("");
    try {
      const r = await api.matricular(turma, email.trim(), nome.trim());
      setEmail("");
      setNome("");
      if (r.senha_temporaria) {
        aoMatricular({ titulo: `Conta criada para ${r.aluno.nome}`, valor: r.senha_temporaria, para: r.aluno.email });
      } else {
        setAviso(`${r.aluno.nome} já tinha conta e foi matriculado em ${r.turma}.`);
        aoMatricular(null);
      }
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setSalvando(false);
    }
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={enviar} className="flex flex-col gap-4">
        <div>
          <h2 className="text-lg font-semibold text-tinta">Matricular aluno</h2>
          <p className="text-[15px] text-suave">Se o e-mail ainda não tem conta, ela é criada com uma senha temporária que aparece uma vez.</p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Campo rotulo="E-mail">{(id) => <input id={id} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="campo" autoComplete="off" />}</Campo>
          <Campo rotulo="Nome" dica="Obrigatório para conta nova">{(id) => <input id={id} maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" autoComplete="off" />}</Campo>
        </div>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
        <div>
          <Botao type="submit" variante="primario" disabled={salvando || !email.trim()}>{salvando ? "Matriculando…" : "Matricular"}</Botao>
        </div>
      </form>
    </Cartao>
  );
}
