"use client";

import { useId, useState, type FormEvent } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Aviso, Botao, Campo } from "@/components/ui";
import { api, type Turma } from "@/lib/api";
import type { Segredo } from "./Matricular";

// O aluno existe sem turma: aqui ele nasce, e as turmas são opcionais. Pôr numa turma depois é
// outra ação ("Adicionar a uma turma"), na linha dele.

export function CadastrarAluno({
  turmas,
  marcadas = [],
  aoCadastrar,
  aoFechar,
}: {
  turmas: Turma[];
  /** As turmas que já vêm marcadas: a do filtro, quando há. */
  marcadas?: number[];
  aoCadastrar: (segredo: Segredo) => void;
  aoFechar: () => void;
}) {
  const [nome, setNome] = useState("");
  const [email, setEmail] = useState("");
  const [escolhidas, setEscolhidas] = useState<number[]>(marcadas);
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    setErro("");
    try {
      const r = await api.cadastrarAluno(nome.trim(), email.trim(), escolhidas);
      aoCadastrar({ titulo: `Conta criada para ${r.aluno.nome}`, valor: r.senha_temporaria, para: r.aluno.email });
      saida.fechar();
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
      titulo="Cadastrar aluno"
      legenda="A conta nasce com uma senha temporária, que aparece uma vez. Turma é opcional: dá para pôr depois."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={!email.trim() || !nome.trim()} ocupado={salvando}>
            {salvando ? "Cadastrando…" : "Cadastrar"}
          </Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={enviar} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2" data-foco-inicial>
          <Campo rotulo="Nome">{(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" autoComplete="off" />}</Campo>
          <Campo rotulo="E-mail">{(id) => <input id={id} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="campo" autoComplete="off" />}</Campo>
        </div>
        {turmas.length > 0 && (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-semibold text-tinta">Turmas <span className="font-normal text-suave">(opcional)</span></legend>
            {turmas.map((t) => (
              <label key={t.id} className="flex items-center gap-2 text-[15px] text-tinta">
                <input
                  type="checkbox"
                  checked={escolhidas.includes(t.id)}
                  onChange={(e) => setEscolhidas((atual) => (e.target.checked ? [...atual, t.id] : atual.filter((x) => x !== t.id)))}
                  className="size-4 accent-acento"
                />
                {t.nome}
              </label>
            ))}
          </fieldset>
        )}
        {erro && <Aviso tom="erro">{erro}</Aviso>}
      </form>
    </Modal>
  );
}

/**
 * Pôr um aluno que já existe numa turma, ou tirar de uma em que ele está. A matrícula é o vínculo
 * entre os dois, e se faz dos dois lados: daqui, da linha do aluno.
 */
export function TurmaDoAluno({
  modo,
  aluno,
  turmas,
  aoTerminar,
  aoFechar,
}: {
  modo: "adicionar" | "tirar";
  aluno: { id: number; nome: string; email: string; turmas: string[] };
  turmas: Turma[];
  aoTerminar: () => void;
  aoFechar: () => void;
}) {
  const opcoes = turmas.filter((t) => (modo === "adicionar" ? !aluno.turmas.includes(t.nome) : aluno.turmas.includes(t.nome)));
  const [turma, setTurma] = useState<number | null>(opcoes[0]?.id ?? null);
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);

  async function confirmar() {
    if (turma == null) return;
    setSalvando(true);
    setErro("");
    try {
      if (modo === "adicionar") await api.matricular(turma, aluno.email, aluno.nome);
      else await api.desmatricular(turma, aluno.id);
      aoTerminar();
      saida.fechar();
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
      tamanho="pequeno"
      titulo={modo === "adicionar" ? `Adicionar ${aluno.nome} a uma turma` : `Tirar ${aluno.nome} de uma turma`}
      legenda={
        modo === "adicionar"
          ? "Ele passa a ver o curso e os simulados da turma na hora."
          : "Ele perde o acesso ao curso e aos simulados da turma na hora. A conta continua, e o que ele já fez fica no histórico."
      }
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao variante={modo === "adicionar" ? "primario" : "perigo"} disabled={turma == null} ocupado={salvando} onClick={() => confirmar()}>
            {modo === "adicionar" ? "Adicionar" : "Tirar da turma"}
          </Botao>
        </div>
      }
    >
      {opcoes.length === 0 ? (
        <Aviso tom="info">{modo === "adicionar" ? "Ele já está em todas as turmas." : "Ele não está em nenhuma turma."}</Aviso>
      ) : (
        <Campo rotulo="Turma">
          {(id) => (
            <select id={id} value={turma ?? ""} onChange={(e) => setTurma(Number(e.target.value))} className="campo">
              {opcoes.map((t) => (
                <option key={t.id} value={t.id}>{t.nome}</option>
              ))}
            </select>
          )}
        </Campo>
      )}
      {erro && <Aviso tom="erro" className="mt-3">{erro}</Aviso>}
    </Modal>
  );
}
