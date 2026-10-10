"use client";

import Link from "next/link";
import { useId, useState, type FormEvent } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Turma } from "@/lib/api";
import { plural } from "@/lib/formato";

export default function Turmas() {
  const turmas = useDados(() => api.turmas());
  const [criando, setCriando] = useState(false);

  return (
    <Pagina
      titulo="Turmas"
      legenda="O que se vende é a turma. Aqui ficam os alunos e o menu de cada uma; os módulos que ela recebe se escolhem em Montar o curso."
      acoes={<Botao variante="primario" onClick={() => setCriando(true)}>Nova turma</Botao>}
    >
      {criando && <NovaTurma aoCriar={() => void turmas.recarregar()} aoFechar={() => setCriando(false)} />}
      <Estado {...turmas} linhas={3} forma="lista">
        {(lista) =>
          lista.length === 0 ? (
            <Vazio titulo="Nenhuma turma ainda">Crie a primeira turma para montar o curso e matricular os alunos.</Vazio>
          ) : (
            <ul className="grid gap-3 md:grid-cols-2">
              {lista.map((t) => (
                <LinhaDaTurma key={t.id} turma={t} aoMudar={() => void turmas.recarregar()} />
              ))}
            </ul>
          )
        }
      </Estado>
    </Pagina>
  );
}

function NovaTurma({ aoCriar, aoFechar }: { aoCriar: () => void; aoFechar: () => void }) {
  const [nome, setNome] = useState("");
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();

  async function criar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    setErro("");
    try {
      await api.criarTurma(nome.trim());
      aoCriar();
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
      titulo="Nova turma"
      legenda="Depois de criar, você matricula os alunos e escolhe os módulos que ela recebe."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={salvando || !nome.trim()}>{salvando ? "Criando…" : "Criar turma"}</Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={criar} className="flex flex-col gap-4" data-foco-inicial>
        <Campo rotulo="Nome" dica="Ex.: Q1 - Extensivo 2027">
          {(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
        </Campo>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
      </form>
    </Modal>
  );
}

function LinhaDaTurma({ turma, aoMudar }: { turma: Turma; aoMudar: () => void }) {
  const [editando, setEditando] = useState(false);
  const [nome, setNome] = useState(turma.nome);
  const [erro, setErro] = useState("");

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    try {
      await api.editarTurma(turma.id, { nome: nome.trim() });
      setEditando(false);
      aoMudar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <Cartao como="li" className="flex flex-col gap-3 p-5">
      {editando ? (
        <form onSubmit={salvar} className="flex flex-col gap-3">
          <Campo rotulo="Nome">{(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}</Campo>
          {erro && <Aviso tom="erro">{erro}</Aviso>}
          <div className="flex gap-2">
            <Botao type="submit" variante="primario" tamanho="pequeno">Salvar</Botao>
            <Botao tamanho="pequeno" onClick={() => setEditando(false)}>Cancelar</Botao>
          </div>
        </form>
      ) : (
        <>
          <div className="flex items-start justify-between gap-3">
            <h2 className="text-lg font-semibold text-tinta">{turma.nome}</h2>
            <Botao variante="texto" onClick={() => setEditando(true)}>Renomear</Botao>
          </div>
          <div className="flex flex-wrap gap-2">
            <Etiqueta>{plural(turma.alunos, "aluno")}</Etiqueta>
            <Etiqueta>{plural(turma.modulos, "módulo")}</Etiqueta>
            <Etiqueta tom="sucesso">{plural(turma.itens_publicados, "vídeo publicado", "vídeos publicados")}</Etiqueta>
            {(turma.itens_em_rascunho ?? 0) > 0 && <Etiqueta tom="atencao">{plural(turma.itens_em_rascunho ?? 0, "em rascunho", "em rascunho")}</Etiqueta>}
          </div>
          <div className="flex flex-wrap gap-2">
            <Link href={`/admin/turmas/alunos/?turma=${turma.id}`} className="inline-flex rounded-full border border-acento px-4 py-1.5 text-sm font-semibold text-acento hover:bg-lilas">Alunos</Link>
            <Link href={`/admin/turmas/desempenho/?turma=${turma.id}`} className="inline-flex rounded-full border border-borda px-4 py-1.5 text-sm font-semibold text-tinta hover:border-suave">Desempenho</Link>
            <Link href={`/admin/turmas/menu/?turma=${turma.id}`} className="inline-flex rounded-full border border-borda px-4 py-1.5 text-sm font-semibold text-tinta hover:border-suave">Menu do aluno</Link>
          </div>
        </>
      )}
    </Cartao>
  );
}
