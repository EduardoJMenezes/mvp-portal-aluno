"use client";

import { useState, type FormEvent } from "react";
import {
  Aviso,
  Botao,
  Campo,
  Cartao,
  Estado,
  Etiqueta,
  Pagina,
  TituloDeSecao,
  Vazio,
  useConfirmar,
} from "@/components/ui";
import { api, useDados, type Aula } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";

export default function AulasDoProfessor() {
  const lista = useDados(() => api.aulasDoProfessor());
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

  async function remover(aula: Aula) {
    const sim = await confirmar({
      titulo: `Remover "${aula.titulo}"?`,
      texto: "A aula some do portal e a sala é desmarcada no Zoom. Só esta sala — nenhuma outra da conta.",
      confirmar: "Remover aula",
      perigo: true,
    });
    if (sim) void executar(() => api.removerAula(aula.aula_id));
  }

  async function iniciar(aula: Aula) {
    setErro("");
    // A aba abre no clique; o endereço chega depois. Esperar antes de abrir
    // faria o navegador tratar como pop-up.
    const aba = window.open("", "_blank");
    try {
      const { url } = await api.iniciarAula(aula.aula_id);
      if (aba) aba.location.href = url;
      else window.location.href = url;
    } catch (ex) {
      aba?.close();
      setErro((ex as Error).message);
    }
  }

  const nomesDasTurmas = turmas.dados?.map((t) => t.nome) ?? [];

  return (
    <Pagina
      titulo="Aulas ao vivo"
      legenda="A sala é do Zoom; quem entra é decidido aqui. O aluno recebe um link pessoal, que só funciona na janela da aula."
    >
      {dialogo}
      <Agendar
        turmas={nomesDasTurmas}
        aoAgendar={(dados) => executar(() => api.agendarAula(dados))}
      />
      {erro && <Aviso tom="erro">{erro}</Aviso>}

      <Estado {...lista} linhas={3}>
        {(aulas) =>
          aulas.length === 0 ? (
            <Vazio titulo="Nenhuma aula agendada">Marque a primeira no formulário acima.</Vazio>
          ) : (
            <ul className="flex flex-col gap-3">
              {aulas.map((aula) => (
                <Cartao key={aula.aula_id} como="li" className="flex flex-col gap-3 p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        {aula.status === "PUBLICADO" ? (
                          <Etiqueta tom="sucesso">Publicada</Etiqueta>
                        ) : (
                          <Etiqueta tom="atencao">Rascunho</Etiqueta>
                        )}
                        {aula.estado === "ABERTA" && <Etiqueta tom="info">Ao vivo agora</Etiqueta>}
                        {aula.grava && <Etiqueta>Grava</Etiqueta>}
                      </div>
                      <p className="text-lg font-semibold text-tinta">{aula.titulo}</p>
                      <p className="text-[13px] text-suave">
                        {emBrasilia(aula.inicio_em)} · {aula.minutos} min ·{" "}
                        {aula.turmas.length ? aula.turmas.join(", ") : "nenhuma turma"}
                        {aula.alunos.length > 0 && ` · ${aula.alunos.map((a) => a.nome).join(", ")}`}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      {aula.tem_sala && (
                        <Botao tamanho="pequeno" variante="primario" onClick={() => void iniciar(aula)}>
                          Iniciar
                        </Botao>
                      )}
                      <Botao
                        tamanho="pequeno"
                        variante={aula.status === "PUBLICADO" ? "neutro" : "primario"}
                        onClick={() =>
                          void executar(() =>
                            api.editarAula(aula.aula_id, {
                              status: aula.status === "PUBLICADO" ? "RASCUNHO" : "PUBLICADO",
                            }),
                          )
                        }
                      >
                        {aula.status === "PUBLICADO" ? "Tirar do ar" : "Publicar"}
                      </Botao>
                      <Botao tamanho="pequeno" variante="perigo" onClick={() => void remover(aula)}>
                        Remover
                      </Botao>
                    </div>
                  </div>
                  <QuemAlcanca
                    aula={aula}
                    turmas={nomesDasTurmas}
                    aoSalvar={(t, a) => executar(() => api.editarAula(aula.aula_id, { turmas: t, alunos: a }))}
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

// --- agendar -----------------------------------------------------------------

type Novo = {
  titulo: string;
  inicio_em: string;
  minutos: number;
  descricao: string;
  gravar: boolean;
  turmas: string[];
  submodulo_id: number | null;
};

function Agendar({ turmas, aoAgendar }: { turmas: string[]; aoAgendar: (d: Novo) => Promise<boolean> }) {
  const [titulo, setTitulo] = useState("");
  const [quando, setQuando] = useState("");
  const [minutos, setMinutos] = useState(90);
  const [descricao, setDescricao] = useState("");
  const [gravar, setGravar] = useState(true);
  const [escolhidas, setEscolhidas] = useState<string[]>([]);
  const [destino, setDestino] = useState<number | null>(null);
  const [salvando, setSalvando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    if (!titulo.trim() || !quando) return;
    setSalvando(true);
    // O campo é hora local; o backend guarda em UTC. A conversão é do navegador.
    const ok = await aoAgendar({
      titulo: titulo.trim(),
      inicio_em: new Date(quando).toISOString(),
      minutos,
      descricao: descricao.trim(),
      gravar,
      turmas: escolhidas,
      submodulo_id: gravar ? destino : null,
    });
    setSalvando(false);
    if (ok) {
      setTitulo("");
      setQuando("");
      setDescricao("");
      setEscolhidas([]);
      setDestino(null);
    }
  }

  return (
    <Cartao className="p-5">
      <TituloDeSecao>Agendar aula</TituloDeSecao>
      <form className="mt-3 flex flex-col gap-3" onSubmit={enviar}>
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr_auto]">
          <Campo rotulo="Título">
            {(id) => (
              <input
                id={id}
                className="campo"
                value={titulo}
                onChange={(e) => setTitulo(e.target.value)}
                placeholder="Estequiometria — revisão"
                maxLength={200}
                required
              />
            )}
          </Campo>
          <Campo rotulo="Começa em">
            {(id) => (
              <input
                id={id}
                type="datetime-local"
                className="campo"
                value={quando}
                onChange={(e) => setQuando(e.target.value)}
                required
              />
            )}
          </Campo>
          <Campo rotulo="Minutos">
            {(id) => (
              <input
                id={id}
                type="number"
                className="campo w-24"
                min={5}
                max={480}
                value={minutos}
                onChange={(e) => setMinutos(Number(e.target.value))}
              />
            )}
          </Campo>
        </div>
        <Campo rotulo="Descrição" dica="Opcional — aparece para o aluno junto do horário.">
          {(id) => (
            <input
              id={id}
              className="campo"
              value={descricao}
              onChange={(e) => setDescricao(e.target.value)}
              maxLength={2000}
            />
          )}
        </Campo>
        <Turmas turmas={turmas} escolhidas={escolhidas} aoMudar={setEscolhidas} />
        <label className="flex w-fit cursor-pointer items-center gap-2 text-[15px] text-tinta">
          <input type="checkbox" checked={gravar} onChange={(e) => setGravar(e.target.checked)} />
          Gravar na nuvem, para virar aula gravada depois
        </label>
        {gravar && <DestinoDaGravacao turmas={escolhidas} valor={destino} aoMudar={setDestino} />}
        <Botao type="submit" variante="primario" disabled={salvando} className="w-fit">
          {salvando ? "Agendando…" : "Agendar em rascunho"}
        </Botao>
        <p className="text-[13px] text-apagado">
          A sala do Zoom só é criada quando você publicar — rascunho não ocupa a agenda de ninguém.
        </p>
      </form>
    </Cartao>
  );
}

// --- para onde vai a gravação -------------------------------------------------

function DestinoDaGravacao({
  turmas,
  valor,
  aoMudar,
}: {
  turmas: string[];
  valor: number | null;
  aoMudar: (id: number | null) => void;
}) {
  const chave = [...turmas].sort().join("|");
  const opcoes = useDados(
    async () =>
      (await Promise.all(turmas.map((t) => api.modulos(t)))).flatMap((modulos, i) =>
        modulos.flatMap((m) => m.submodulos.map((s) => ({ id: s.id, nome: `${turmas[i]} › ${m.nome} › ${s.nome}` }))),
      ),
    [chave],
  );
  return (
    <Campo
      rotulo="Onde a gravação entra"
      dica="Ela chega em rascunho — você aprova em Rascunhos, como qualquer vídeo. Sem destino, fica só no Vimeo."
    >
      {(id) => (
        <select
          id={id}
          className="campo"
          value={valor ?? ""}
          onChange={(e) => aoMudar(e.target.value ? Number(e.target.value) : null)}
          disabled={turmas.length === 0}
        >
          <option value="">{turmas.length === 0 ? "Escolha uma turma antes" : "Só no Vimeo, sem entrar no curso"}</option>
          {opcoes.dados?.map((o) => (
            <option key={o.id} value={o.id}>
              {o.nome}
            </option>
          ))}
        </select>
      )}
    </Campo>
  );
}

// --- quem alcança ------------------------------------------------------------

function Turmas({
  turmas,
  escolhidas,
  aoMudar,
}: {
  turmas: string[];
  escolhidas: string[];
  aoMudar: (t: string[]) => void;
}) {
  return (
    <fieldset>
      <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas que entram</legend>
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
                onChange={() =>
                  aoMudar(marcada ? escolhidas.filter((t) => t !== nome) : [...escolhidas, nome])
                }
              />
              {nome}
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}

function QuemAlcanca({
  aula,
  turmas,
  aoSalvar,
}: {
  aula: Aula;
  turmas: string[];
  aoSalvar: (turmas: string[], alunos: string[]) => Promise<boolean>;
}) {
  const [escolhidas, setEscolhidas] = useState(aula.turmas);
  const [avulsos, setAvulsos] = useState(aula.alunos.map((a) => a.email).join(", "));
  const [salvando, setSalvando] = useState(false);

  const mudou =
    [...escolhidas].sort().join("|") !== [...aula.turmas].sort().join("|") ||
    avulsos.trim() !== aula.alunos.map((a) => a.email).join(", ");

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
      <Turmas turmas={turmas} escolhidas={escolhidas} aoMudar={setEscolhidas} />
      <Campo rotulo="Alunos avulsos" dica="E-mails separados por vírgula — entram mesmo fora da turma.">
        {(id) => (
          <input
            id={id}
            className="campo"
            value={avulsos}
            onChange={(e) => setAvulsos(e.target.value)}
            placeholder="ana@escola.com, bruno@escola.com"
          />
        )}
      </Campo>
      {mudou && (
        <Botao tamanho="pequeno" variante="primario" onClick={() => void salvar()} disabled={salvando} className="w-fit">
          {salvando ? "Salvando…" : "Salvar quem entra"}
        </Botao>
      )}
      {aula.gravacao === "enviando" && (
        <p className="text-[13px] text-suave">A gravação está indo do Zoom para o Vimeo.</p>
      )}
      {aula.gravacao && aula.gravacao !== "enviando" && (
        <p className="text-[13px] text-suave">
          Gravação no Vimeo
          {aula.gravacao_item_id ? " e no curso, em rascunho — aprove em Rascunhos para os alunos verem." : "."}
        </p>
      )}
      {aula.presentes.length > 0 && (
        <details className="text-[13px] text-suave">
          <summary className="cursor-pointer">
            {aula.presentes.length} {aula.presentes.length === 1 ? "aluno esteve" : "alunos estiveram"} na aula
          </summary>
          <ul className="mt-1 flex flex-col gap-0.5">
            {aula.presentes.map((p) => (
              <li key={p.nome + p.entrou_em}>
                {p.nome} — entrou {emBrasilia(p.entrou_em)}
                {p.saiu_em && `, saiu ${emBrasilia(p.saiu_em)}`}
              </li>
            ))}
          </ul>
        </details>
      )}
      {!aula.tem_sala && aula.status === "PUBLICADO" && (
        <p className="text-[13px] text-apagado">Sem sala no Zoom — publique de novo para abrir.</p>
      )}
    </div>
  );
}
