"use client";

import { useId, useState, type FormEvent } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Aviso, Botao, Campo, SegredoUmaVez } from "@/components/ui";
import { api } from "@/lib/api";

/** A senha que aparece uma vez: a da conta nova ou a gerada de novo. */
export type Segredo = { titulo: string; valor: string; para: string };

/**
 * Matricular num modal. Ele continua aberto depois de cada matrícula, com o resultado à vista: a
 * senha temporária da conta nova, ou o aviso de que a pessoa já tinha conta. Assim dá para
 * matricular vários em seguida.
 */
export function Matricular({ turma, aoMatricular, aoFechar }: { turma: number; aoMatricular: (segredo: Segredo | null) => void; aoFechar: () => void }) {
  const [email, setEmail] = useState("");
  const [nome, setNome] = useState("");
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [criada, setCriada] = useState<Segredo | null>(null);
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    setErro("");
    setAviso("");
    setCriada(null);
    try {
      const r = await api.matricular(turma, email.trim(), nome.trim());
      setEmail("");
      setNome("");
      if (r.senha_temporaria) {
        const segredo = { titulo: `Conta criada para ${r.aluno.nome}`, valor: r.senha_temporaria, para: r.aluno.email };
        setCriada(segredo);
        aoMatricular(segredo);
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
    <Modal
      {...saida}
      aoFechar={() => !salvando && saida.fechar()}
      fechaClicandoFora={false}
      tamanho="medio"
      titulo="Matricular aluno"
      legenda="Se o e-mail ainda não tem conta, ela é criada com uma senha temporária que aparece uma vez."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Fechar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={!email.trim()} ocupado={salvando}>{salvando ? "Matriculando…" : "Matricular"}</Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={enviar} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2" data-foco-inicial>
          <Campo rotulo="E-mail">{(id) => <input id={id} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="campo" autoComplete="off" />}</Campo>
          <Campo rotulo="Nome" dica="Obrigatório para conta nova">{(id) => <input id={id} maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" autoComplete="off" />}</Campo>
        </div>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
        {criada && (
          <SegredoUmaVez titulo={criada.titulo} valor={criada.valor}>
            Passe para {criada.para} por um canal seguro. Ela aparece só agora e o aluno troca no primeiro acesso.
          </SegredoUmaVez>
        )}
      </form>
    </Modal>
  );
}
