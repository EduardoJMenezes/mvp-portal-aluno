"use client";

import { useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Pagina, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Assunto } from "@/lib/api";

export default function Assuntos() {
  const lista = useDados(() => api.assuntos());
  const [dialogo, confirmar] = useConfirmar();
  const [erro, setErro] = useState("");

  // Toda ação recarrega a lista e mostra o erro do backend, que já vem em português.
  async function executar(acao: () => Promise<unknown>) {
    setErro("");
    try {
      await acao();
      await lista.recarregar();
      return true;
    } catch (ex) {
      setErro((ex as Error).message);
      return false;
    }
  }

  async function removerAssunto(a: Assunto) {
    const sim = await confirmar({
      titulo: `Remover o assunto "${a.nome}"?`,
      texto: "Vídeos e questões com esta etiqueta deixam de tê-la, e as recomendações de estudo param de usá-la. Os sub-assuntos vão junto.",
      confirmar: "Remover assunto",
      perigo: true,
    });
    if (sim) void executar(() => api.removerAssunto(a.id));
  }

  async function removerSub(a: Assunto, sub: { id: number; nome: string }) {
    const sim = await confirmar({
      titulo: `Remover "${sub.nome}" de ${a.nome}?`,
      texto: "O que estava etiquetado com o sub-assunto continua com o assunto.",
      confirmar: "Remover sub-assunto",
      perigo: true,
    });
    if (sim) void executar(() => api.removerSubassunto(a.id, sub.id));
  }

  return (
    <Pagina titulo="Assuntos" legenda="Etiquetas globais, sem número de capítulo: K03 é Estequiometria num ano e Tabela Periódica no outro. É por elas que o aluno recebe o que revisar.">
      {dialogo}
      <NovoAssunto aoCriar={(nome, subs) => executar(() => api.cadastrarAssunto(nome, subs))} />
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <Estado {...lista} linhas={4}>
        {(assuntos) =>
          assuntos.length === 0 ? (
            <Vazio titulo="Nenhum assunto cadastrado" />
          ) : (
            <ul className="grid gap-3 md:grid-cols-2">
              {assuntos.map((a) => (
                <Cartao key={a.id} como="li" className="flex flex-col gap-3 p-4">
                  <div className="flex items-start justify-between gap-2">
                    <NomeEditavel nome={a.nome} rotulo={`Novo nome de ${a.nome}`} classe="text-lg font-semibold" aoSalvar={(nome) => executar(() => api.editarAssunto(a.id, nome))} />
                    <Botao tamanho="pequeno" variante="texto" className="text-erro" onClick={() => void removerAssunto(a)}>Remover</Botao>
                  </div>
                  {a.subassuntos.length > 0 && (
                    <ul className="flex flex-col divide-y divide-borda rounded-cartao border border-borda">
                      {a.subassuntos.map((s) => (
                        <li key={s.id} className="flex items-center justify-between gap-2 px-3 py-1.5">
                          <NomeEditavel nome={s.nome} rotulo={`Novo nome de ${s.nome}`} classe="text-[15px]" aoSalvar={(nome) => executar(() => api.editarSubassunto(a.id, s.id, nome))} />
                          <button type="button" onClick={() => void removerSub(a, s)} className="text-sm font-semibold text-erro hover:underline">Remover</button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <NovoSub assunto={a.nome} aoCriar={(nome) => executar(() => api.cadastrarAssunto(a.nome, [nome]))} />
                </Cartao>
              ))}
            </ul>
          )
        }
      </Estado>
    </Pagina>
  );
}

function NomeEditavel({ nome, rotulo, classe, aoSalvar }: { nome: string; rotulo: string; classe: string; aoSalvar: (nome: string) => Promise<boolean> }) {
  const [editando, setEditando] = useState(false);
  const [valor, setValor] = useState(nome);

  if (!editando) {
    return (
      <button type="button" onClick={() => { setValor(nome); setEditando(true); }} className={`group inline-flex min-w-0 items-center gap-1.5 text-left ${classe}`} title="Renomear">
        <span className="min-w-0 break-words">{nome}</span>
        <svg aria-hidden="true" viewBox="0 0 16 16" className="size-3.5 shrink-0 fill-none stroke-apagado stroke-[1.5] group-hover:stroke-acento">
          <path d="M11.5 2.5l2 2L6 12H4v-2z" />
        </svg>
        <span className="sr-only">Renomear</span>
      </button>
    );
  }

  return (
    <form
      className="flex min-w-0 flex-1 gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        if (valor.trim() === nome || (await aoSalvar(valor.trim()))) setEditando(false);
      }}
    >
      <input aria-label={rotulo} autoFocus required maxLength={120} value={valor} onChange={(e) => setValor(e.target.value)} onKeyDown={(e) => e.key === "Escape" && setEditando(false)} className="campo min-w-0 flex-1 py-1" />
      <Botao type="submit" tamanho="pequeno" variante="secundario">Salvar</Botao>
    </form>
  );
}

function NovoSub({ assunto, aoCriar }: { assunto: string; aoCriar: (nome: string) => Promise<boolean> }) {
  const [nome, setNome] = useState("");
  return (
    <form
      className="flex gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        if (nome.trim() && (await aoCriar(nome.trim()))) setNome("");
      }}
    >
      <input aria-label={`Novo sub-assunto de ${assunto}`} maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} placeholder="Novo sub-assunto" className="campo min-w-0 flex-1 py-1.5" />
      <Botao type="submit" tamanho="pequeno" disabled={!nome.trim()}>Adicionar</Botao>
    </form>
  );
}

function NovoAssunto({ aoCriar }: { aoCriar: (nome: string, subassuntos: string[]) => Promise<boolean> }) {
  const [nome, setNome] = useState("");
  const [subs, setSubs] = useState("");
  const [ocupado, setOcupado] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setOcupado(true);
    const lista = subs.split(/[\n,;]/).map((s) => s.trim()).filter(Boolean);
    if (await aoCriar(nome.trim(), lista)) {
      setNome("");
      setSubs("");
    }
    setOcupado(false);
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={enviar} className="grid gap-3 sm:grid-cols-[1fr_2fr_auto] sm:items-end">
        <Campo rotulo="Novo assunto">{(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} placeholder="Estequiometria" className="campo" />}</Campo>
        <Campo rotulo="Sub-assuntos (opcional)">{(id) => <input id={id} value={subs} onChange={(e) => setSubs(e.target.value)} placeholder="Pureza e rendimento, Reagente limitante" className="campo" />}</Campo>
        <Botao type="submit" variante="primario" disabled={ocupado || !nome.trim()}>Cadastrar</Botao>
      </form>
    </Cartao>
  );
}
