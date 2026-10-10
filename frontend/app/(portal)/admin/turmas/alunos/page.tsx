"use client";

import { KeyRound, UserMinus } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense, useId, useState, type FormEvent } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Menu } from "@/components/Menu";
import { Aviso, Botao, BotaoLink, Campo, Cartao, Estado, Etiqueta, Pagina, Progresso, SegredoUmaVez, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Aluno } from "@/lib/api";
import { emBrasilia, haQuantoTempo } from "@/lib/formato";

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
  // O progresso vem à parte: se falhar, a lista de alunos continua de pé.
  const progresso = useDados(() => api.progressoDaTurma(turma).catch(() => null), [turma]);
  const noCurso = new Map(progresso.dados?.alunos.map((a) => [a.id, a]));
  const [segredo, setSegredo] = useState<Segredo | null>(null);
  const [matriculando, setMatriculando] = useState(false);
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
      legenda={`Quem está matriculado vê o curso publicado e os simulados desta turma.${progresso.dados ? ` Hoje o curso dela tem ${progresso.dados.total} ${progresso.dados.total === 1 ? "item publicado" : "itens publicados"}.` : ""}`}
      voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}
      acoes={
        <>
          <BotaoLink href={`/admin/turmas/desempenho/?turma=${turma}`}>Desempenho da turma</BotaoLink>
          <Botao variante="primario" onClick={() => setMatriculando(true)}>Matricular aluno</Botao>
        </>
      }
    >
      {dialogo}
      {/* Matricular abre num modal: a lista dos alunos é o que fica na página. */}
      {matriculando && <Matricular turma={turma} aoMatricular={(r) => { if (r) setSegredo(r); void alunos.recarregar(); }} aoFechar={() => setMatriculando(false)} />}
      {segredo && (
        <SegredoUmaVez titulo={segredo.titulo} valor={segredo.valor}>
          Passe para {segredo.para} por um canal seguro. Ela aparece só agora e o aluno troca no primeiro acesso.
        </SegredoUmaVez>
      )}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <Estado {...alunos} linhas={5} forma="tabela">
        {(dados) =>
          dados.alunos.length === 0 ? (
            <Vazio titulo="Nenhum aluno nesta turma">Matricule o primeiro em &quot;Matricular aluno&quot;.</Vazio>
          ) : (
            <Cartao className="overflow-x-auto">
              <table className="tabela min-w-[52rem]">
                <thead>
                  <tr>
                    <th scope="col">Aluno</th>
                    <th scope="col">E-mail</th>
                    <th scope="col">No curso</th>
                    <th scope="col">Acesso</th>
                    <th scope="col"><span className="sr-only">Ações</span></th>
                  </tr>
                </thead>
                <tbody>
                  {dados.alunos.map((a) => (
                    <tr key={a.id}>
                      <td className="font-medium">{a.nome}</td>
                      <td className="break-all text-suave">{a.email}</td>
                      <td className="min-w-44">
                        <NoCurso aluno={noCurso.get(a.id)} carregando={progresso.carregando} />
                      </td>
                      <td>{a.senha_temporaria ? <Etiqueta tom="atencao">Senha temporária</Etiqueta> : <Etiqueta tom="sucesso">Ativo</Etiqueta>}</td>
                      <td>
                        <div className="flex items-center justify-end gap-1">
                          <BotaoLink tamanho="mini" href={`/admin/alunos/?aluno=${a.id}`}>Acompanhar</BotaoLink>
                          <Menu
                            rotulo={`Mais ações de ${a.nome}`}
                            itens={[
                              { rotulo: "Gerar nova senha", icone: KeyRound, aoEscolher: () => void redefinir(a) },
                              "divisor",
                              { rotulo: "Tirar da turma", icone: UserMinus, perigo: true, aoEscolher: () => void tirar(a) },
                            ]}
                          />
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

/** Quanto do curso o aluno já fez e quando apareceu pela última vez. */
function NoCurso({ aluno, carregando }: { aluno?: { nome: string; concluidos: number; total: number; ultima_atividade: string | null }; carregando: boolean }) {
  if (!aluno) return <span className="text-suave">{carregando ? "…" : "—"}</span>;
  if (aluno.total === 0) return <span className="text-suave">Sem itens publicados</span>;
  return (
    <div className="flex flex-col gap-1">
      <span className="flex items-baseline justify-between gap-2">
        <span className="font-medium tabular-nums text-tinta">{aluno.concluidos} de {aluno.total}</span>
        <span className="text-[13px] tabular-nums text-suave">{Math.round((aluno.concluidos / aluno.total) * 100)}%</span>
      </span>
      <Progresso feitos={aluno.concluidos} total={aluno.total} rotulo={`Progresso de ${aluno.nome} no curso`} />
      <span className="text-[13px] text-suave" title={aluno.ultima_atividade ? emBrasilia(aluno.ultima_atividade) : undefined}>
        {aluno.ultima_atividade ? `Última atividade ${haQuantoTempo(aluno.ultima_atividade)}` : "Ainda não começou"}
      </span>
    </div>
  );
}

/**
 * Matricular num modal. Ele continua aberto depois de cada matrícula, com o resultado à vista: a
 * senha temporária da conta nova, ou o aviso de que a pessoa já tinha conta. Assim dá para
 * matricular vários em seguida.
 */
function Matricular({ turma, aoMatricular, aoFechar }: { turma: number; aoMatricular: (segredo: Segredo | null) => void; aoFechar: () => void }) {
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
