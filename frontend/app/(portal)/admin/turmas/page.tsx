"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Turma } from "@/lib/api";
import { plural } from "@/lib/formato";

export default function Turmas() {
  const turmas = useDados(() => api.turmas());
  const [criando, setCriando] = useState(false);

  return (
    <Pagina
      titulo="Turmas"
      legenda="Cada turma tem o próprio curso, alunos e simulados. O que o Claude cria pelo MCP aparece aqui também."
      acoes={!criando && <Botao variante="primario" onClick={() => setCriando(true)}>Nova turma</Botao>}
    >
      {criando && <NovaTurma aoCriar={() => { setCriando(false); void turmas.recarregar(); }} aoCancelar={() => setCriando(false)} />}
      <Estado {...turmas} linhas={3}>
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

function NovaTurma({ aoCriar, aoCancelar }: { aoCriar: () => void; aoCancelar: () => void }) {
  const [nome, setNome] = useState("");
  const [ano, setAno] = useState(String(new Date().getFullYear() + 1));
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function criar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    setErro("");
    try {
      await api.criarTurma(nome.trim(), Number(ano));
      aoCriar();
    } catch (ex) {
      setErro((ex as Error).message);
      setSalvando(false);
    }
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={criar} className="flex flex-col gap-4">
        <h2 className="text-lg font-semibold text-tinta">Nova turma</h2>
        <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
          <Campo rotulo="Nome" dica="Ex.: Extensivo 2027">
            {(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Ano">
            {(id) => <input id={id} type="number" required min={2000} max={2100} value={ano} onChange={(e) => setAno(e.target.value)} className="campo" />}
          </Campo>
        </div>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <div className="flex gap-2">
          <Botao type="submit" variante="primario" disabled={salvando || !nome.trim()}>{salvando ? "Criando…" : "Criar turma"}</Botao>
          <Botao onClick={aoCancelar}>Cancelar</Botao>
        </div>
      </form>
    </Cartao>
  );
}

function LinhaDaTurma({ turma, aoMudar }: { turma: Turma; aoMudar: () => void }) {
  const [editando, setEditando] = useState(false);
  const [nome, setNome] = useState(turma.nome);
  const [ano, setAno] = useState(String(turma.ano));
  const [erro, setErro] = useState("");

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    try {
      await api.editarTurma(turma.id, { nome: nome.trim(), ano: Number(ano) });
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
          <div className="grid gap-3 sm:grid-cols-[1fr_7rem]">
            <Campo rotulo="Nome">{(id) => <input id={id} required value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}</Campo>
            <Campo rotulo="Ano">{(id) => <input id={id} type="number" required min={2000} max={2100} value={ano} onChange={(e) => setAno(e.target.value)} className="campo" />}</Campo>
          </div>
          {erro && <Aviso tom="erro">{erro}</Aviso>}
          <div className="flex gap-2">
            <Botao type="submit" variante="primario" tamanho="pequeno">Salvar</Botao>
            <Botao tamanho="pequeno" onClick={() => setEditando(false)}>Cancelar</Botao>
          </div>
        </form>
      ) : (
        <>
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-lg font-semibold text-tinta">{turma.nome}</h2>
              <p className="text-sm text-suave">Ano {turma.ano}</p>
            </div>
            <Botao variante="texto" onClick={() => setEditando(true)}>Renomear</Botao>
          </div>
          <div className="flex flex-wrap gap-2">
            <Etiqueta>{plural(turma.alunos, "aluno")}</Etiqueta>
            <Etiqueta>{plural(turma.modulos, "módulo")}</Etiqueta>
            <Etiqueta tom="sucesso">{plural(turma.itens_publicados, "vídeo publicado", "vídeos publicados")}</Etiqueta>
            {(turma.itens_em_rascunho ?? 0) > 0 && <Etiqueta tom="atencao">{plural(turma.itens_em_rascunho ?? 0, "em rascunho", "em rascunho")}</Etiqueta>}
          </div>
          <div className="flex gap-2">
            <Link href={`/admin/turmas/curso/?turma=${turma.id}`} className="inline-flex rounded-full border border-acento px-4 py-1.5 text-sm font-semibold text-acento hover:bg-lilas">Curso</Link>
            <Link href={`/admin/turmas/alunos/?turma=${turma.id}`} className="inline-flex rounded-full border border-borda px-4 py-1.5 text-sm font-semibold text-tinta hover:border-suave">Alunos</Link>
          </div>
        </>
      )}
    </Cartao>
  );
}
