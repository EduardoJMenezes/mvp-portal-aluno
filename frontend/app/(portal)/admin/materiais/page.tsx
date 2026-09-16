"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type Material } from "@/lib/api";
import { emBrasilia, tamanhoDoArquivo } from "@/lib/formato";

export default function MateriaisDoProfessor() {
  const lista = useDados(() => api.materiaisDoProfessor());
  const turmas = useDados(() => api.turmas());
  const [erro, setErro] = useState("");
  const [dialogo, confirmar] = useConfirmar();

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

  async function remover(m: Material) {
    const sim = await confirmar({
      titulo: `Remover "${m.titulo}"?`,
      texto: "O material some da tela dos alunos. O que eles já riscaram continua guardado, caso você o restaure depois.",
      confirmar: "Remover material",
      perigo: true,
    });
    if (sim) void executar(() => api.removerMaterial(m.material_id));
  }

  return (
    <Pagina
      titulo="Materiais"
      legenda="PDF que o aluno lê e risca dentro do portal. Ele não baixa o arquivo: cada página sai do servidor com a sessão dele aberta."
    >
      {dialogo}
      <Enviar turmas={turmas.dados?.map((t) => t.nome) ?? []} aoEnviar={(arquivo, titulo) => executar(() => api.enviarMaterial(arquivo, titulo, [], []))} />
      {erro && <Aviso tom="erro">{erro}</Aviso>}

      <Estado {...lista} linhas={3}>
        {(materiais) =>
          materiais.length === 0 ? (
            <Vazio titulo="Nenhum material enviado">Mande o primeiro PDF no formulário acima.</Vazio>
          ) : (
            <ul className="flex flex-col gap-3">
              {materiais.map((m) => (
                <Cartao key={m.material_id} como="li" className="flex flex-col gap-3 p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        {m.status === "PUBLICADO" ? <Etiqueta tom="sucesso">Publicado</Etiqueta> : <Etiqueta tom="atencao">Rascunho</Etiqueta>}
                        <span className="text-[13px] text-suave">{tamanhoDoArquivo(m.tamanho)} · enviado {emBrasilia(m.criado_em)}</span>
                      </div>
                      <p className="text-lg font-semibold text-tinta">{m.titulo}</p>
                      <p className="text-[13px] text-suave">
                        {m.turmas.length ? m.turmas.join(", ") : "Nenhuma turma"}
                        {m.alunos.length > 0 && ` · ${m.alunos.map((a) => a.nome).join(", ")}`}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Link href={`/materiais/ler/?id=${m.material_id}`} className="text-sm font-semibold text-acento hover:underline">
                        Abrir
                      </Link>
                      <Botao
                        tamanho="pequeno"
                        variante={m.status === "PUBLICADO" ? "neutro" : "primario"}
                        onClick={() =>
                          void executar(() =>
                            api.editarMaterial(m.material_id, { status: m.status === "PUBLICADO" ? "RASCUNHO" : "PUBLICADO" }),
                          )
                        }
                      >
                        {m.status === "PUBLICADO" ? "Tirar do ar" : "Publicar"}
                      </Botao>
                      <Botao tamanho="pequeno" variante="perigo" onClick={() => void remover(m)}>
                        Remover
                      </Botao>
                    </div>
                  </div>
                  <QuemAlcanca
                    material={m}
                    turmas={turmas.dados?.map((t) => t.nome) ?? []}
                    aoSalvar={(t, alunos) => executar(() => api.editarMaterial(m.material_id, { turmas: t, alunos }))}
                  />
                </Cartao>
              ))}
            </ul>
          )
        }
      </Estado>
    </Pagina>
  );
}

function Enviar({
  turmas,
  aoEnviar,
}: {
  turmas: string[];
  aoEnviar: (arquivo: File, titulo: string) => Promise<boolean>;
}) {
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [titulo, setTitulo] = useState("");
  const [enviando, setEnviando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    if (!arquivo) return;
    setEnviando(true);
    if (await aoEnviar(arquivo, titulo.trim() || arquivo.name.replace(/\.pdf$/i, ""))) {
      setArquivo(null);
      setTitulo("");
    }
    setEnviando(false);
  }

  return (
    <Cartao className="p-5">
      <form onSubmit={enviar} className="flex flex-col gap-4">
        <div>
          <TituloDeSecao>Enviar material</TituloDeSecao>
          <p className="text-[15px] text-suave">
            PDF de até 60 MB. Ele entra como rascunho: escolha as turmas ou os alunos e publique quando quiser.
            {turmas.length === 0 && " Cadastre uma turma antes, senão não há para quem publicar."}
          </p>
        </div>
        <label className="flex cursor-pointer flex-col items-center gap-1 rounded-cartao border-2 border-dashed border-borda bg-canvas px-6 py-8 text-center hover:border-suave">
          <span className="font-semibold text-tinta">{arquivo ? arquivo.name : "Escolha o PDF"}</span>
          <span className="text-sm text-suave">{arquivo ? tamanhoDoArquivo(arquivo.size) : "Apostila, lista de exercícios, gabarito"}</span>
          <input type="file" accept="application/pdf,.pdf" className="sr-only" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
        </label>
        <Campo rotulo="Título" dica="Vazio usa o nome do arquivo.">
          {(id) => <input id={id} maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} className="campo" />}
        </Campo>
        <div>
          <Botao type="submit" variante="primario" disabled={!arquivo || enviando}>
            {enviando ? "Enviando…" : "Enviar"}
          </Botao>
        </div>
      </form>
    </Cartao>
  );
}

function QuemAlcanca({
  material,
  turmas,
  aoSalvar,
}: {
  material: Material;
  turmas: string[];
  aoSalvar: (turmas: string[], alunos: string[]) => Promise<boolean>;
}) {
  const [escolhidas, setEscolhidas] = useState(material.turmas);
  const [avulsos, setAvulsos] = useState(material.alunos.map((a) => a.email).join(", "));
  const [salvando, setSalvando] = useState(false);

  const mudou =
    [...escolhidas].sort().join("|") !== [...material.turmas].sort().join("|") ||
    avulsos.trim() !== material.alunos.map((a) => a.email).join(", ");

  async function salvar() {
    setSalvando(true);
    await aoSalvar(
      escolhidas,
      avulsos
        .split(/[,;\n]/)
        .map((a) => a.trim())
        .filter(Boolean),
    );
    setSalvando(false);
  }

  return (
    <div className="flex flex-col gap-3 border-t border-borda pt-3">
      <fieldset>
        <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Quem alcança</legend>
        <div className="flex flex-wrap gap-2">
          {turmas.map((nome) => {
            const marcada = escolhidas.includes(nome);
            return (
              <label
                key={nome}
                className={`flex cursor-pointer items-center gap-2 rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                  marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"
                }`}
              >
                <input
                  type="checkbox"
                  className="sr-only"
                  checked={marcada}
                  onChange={() => setEscolhidas((atual) => (marcada ? atual.filter((n) => n !== nome) : [...atual, nome]))}
                />
                {nome}
              </label>
            );
          })}
        </div>
      </fieldset>
      <div className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Alunos avulsos" dica="E-mails separados por vírgula. É por aqui que um material vai para uma pessoa só." className="min-w-64 flex-1">
          {(id) => <input id={id} value={avulsos} onChange={(e) => setAvulsos(e.target.value)} placeholder="aluno@escola.com" className="campo" />}
        </Campo>
        <Botao tamanho="pequeno" variante="secundario" disabled={!mudou || salvando} onClick={() => void salvar()} className="mb-6">
          {salvando ? "Salvando…" : "Salvar acesso"}
        </Botao>
      </div>
    </div>
  );
}
