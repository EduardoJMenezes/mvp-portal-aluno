"use client";

import { useId, useState, type FormEvent } from "react";
import { LinhaDoEvento, porMes } from "@/components/Agenda";
import { Modal, useSaida } from "@/components/Camadas";
import { CampoCategoria, categoriasDe } from "@/components/Categoria";
import { Aviso, Botao, Campo, Cartao, Estado, Pagina, TituloDeSecao, Vazio, useConfirmar } from "@/components/ui";
import { api, useDados, type EventoDaAgenda, type TipoDeLigacao } from "@/lib/api";
import { paraCampoDataHora } from "@/lib/formato";

type Opcao = { id: number; nome: string };
type Opcoes = Record<TipoDeLigacao, Opcao[]>;

const TIPOS: { valor: TipoDeLigacao | ""; rotulo: string }[] = [
  { valor: "", rotulo: "Nada (só um aviso)" },
  { valor: "AULA", rotulo: "Uma aula" },
  { valor: "MODULO", rotulo: "Um módulo inteiro" },
  { valor: "AULA_AO_VIVO", rotulo: "Uma aula ao vivo" },
  { valor: "SIMULADO", rotulo: "Um simulado" },
];

/**
 * A agenda (decisão 0012): eventos por turma, que levam o aluno ao conteúdo ligado. A aula ou o
 * módulo ligado fica escondido para aquelas turmas até a hora do evento.
 */
export default function AgendaDoProfessor() {
  const [turma, setTurma] = useState("");
  const dados = useDados(async () => {
    const [eventos, turmas, biblioteca, aulas, simulados] = await Promise.all([
      api.agendaDoProfessor(turma || undefined), api.turmas(), api.bibliotecaArvore(), api.aulasDoProfessor(), api.simuladosDoProfessor(),
    ]);
    const opcoes: Opcoes = {
      AULA: biblioteca.flatMap((m) => m.submodulos.flatMap((s) => s.itens.map((i) => ({ id: i.id, nome: `${m.nome} › ${s.nome} › ${i.nome}` })))),
      MODULO: biblioteca.map((m) => ({ id: m.id, nome: m.nome })),
      AULA_AO_VIVO: aulas.map((a) => ({ id: a.aula_id, nome: a.titulo })),
      SIMULADO: simulados.map((s) => ({ id: s.simulado_id, nome: s.titulo })),
    };
    return { eventos, turmas: turmas.map((t) => t.nome), opcoes };
  }, [turma]);
  const [novo, setNovo] = useState(false);
  const [editando, setEditando] = useState<number | null>(null);
  const [erro, setErro] = useState("");
  const [dialogo, confirmar] = useConfirmar();

  async function executar(acao: () => Promise<unknown>) {
    setErro("");
    try {
      await acao();
      await dados.recarregar();
      return true;
    } catch (ex) {
      setErro((ex as Error).message);
      return false;
    }
  }

  async function remover(e: EventoDaAgenda) {
    const sim = await confirmar({
      titulo: `Tirar "${e.titulo}" da agenda?`,
      texto: e.destino
        ? `Se "${e.destino.nome}" estava esperando este evento para aparecer, ele volta a aparecer normalmente para ${e.turmas.join(", ")}.`
        : "O evento some da agenda das turmas dele.",
      confirmar: "Tirar da agenda",
      perigo: true,
    });
    if (sim) await executar(() => api.removerEvento(e.evento_id));
  }

  const emEdicao = editando === null ? undefined : dados.dados?.eventos.find((e) => e.evento_id === editando);
  // Com o modal aberto, o erro aparece dentro dele; o do alto da página é o de tirar um evento.
  const noModal = novo || !!emEdicao;

  return (
    <Pagina
      titulo="Agenda"
      legenda="Cada evento vale para as turmas marcadas nele. Ligado a uma aula ou módulo, leva o aluno até lá — e o conteúdo só aparece para aquelas turmas a partir da hora do evento."
      acoes={<Botao variante="primario" onClick={() => setNovo(true)}>Novo evento</Botao>}
    >
      {dialogo}
      {erro && !noModal && <Aviso tom="erro">{erro}</Aviso>}
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="filtro-turma" className="text-sm font-semibold text-tinta-2">Turma</label>
        <select id="filtro-turma" value={turma} onChange={(e) => setTurma(e.target.value)} className="campo w-auto">
          <option value="">Todas</option>
          {dados.dados?.turmas.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </div>
      {/* O evento, novo ou em edição, abre num modal: a agenda atrás não sai do lugar. */}
      {novo && dados.dados && (
        <FormularioDoEvento
          turmas={dados.dados.turmas}
          opcoes={dados.dados.opcoes}
          categorias={categoriasDe(dados.dados.eventos)}
          erro={erro}
          aoSalvar={(d) => executar(() => api.criarEvento(d))}
          aoFechar={() => { setNovo(false); setErro(""); }}
        />
      )}
      {emEdicao && dados.dados && (
        <FormularioDoEvento
          key={emEdicao.evento_id}
          evento={emEdicao}
          turmas={dados.dados.turmas}
          opcoes={dados.dados.opcoes}
          categorias={categoriasDe(dados.dados.eventos)}
          erro={erro}
          aoSalvar={(d) => executar(() => api.editarEvento(emEdicao.evento_id, d))}
          aoFechar={() => { setEditando(null); setErro(""); }}
        />
      )}
      <Estado {...dados} linhas={4} forma="lista">
        {({ eventos }) =>
          eventos.length === 0 ? (
            <Vazio titulo="A agenda está vazia">Crie o primeiro evento aqui, ou mande a foto do calendário ao Claude.</Vazio>
          ) : (
            porMes(eventos).map(([mes, doMes]) => (
              <section key={mes} className="flex flex-col gap-2" aria-label={mes}>
                <TituloDeSecao>{mes}</TituloDeSecao>
                <Cartao>
                  <ul className="divide-y divide-borda">
                    {doMes.map((e) => (
                        <LinhaDoEvento
                          key={e.evento_id}
                          evento={{ ...e, liberado: true }}
                          acoes={
                            <span className="flex w-full flex-wrap items-center justify-between gap-2 sm:w-auto">
                              <span className="text-[13px] text-suave">{e.turmas.join(", ")}</span>
                              <span className="flex gap-1">
                                <Botao variante="texto" onClick={() => setEditando(e.evento_id)}>Editar</Botao>
                                <Botao variante="texto" className="text-erro" onClick={() => void remover(e)}>Tirar</Botao>
                              </span>
                            </span>
                          }
                        />
                    ))}
                  </ul>
                </Cartao>
              </section>
            ))
          )
        }
      </Estado>
    </Pagina>
  );
}

function FormularioDoEvento({
  evento,
  turmas,
  opcoes,
  categorias,
  erro,
  aoSalvar,
  aoFechar,
}: {
  evento?: EventoDaAgenda;
  turmas: string[];
  opcoes: Opcoes;
  categorias: string[];
  /** O que o servidor recusou: aparece dentro do modal. */
  erro: string;
  aoSalvar: (d: Parameters<typeof api.criarEvento>[0]) => Promise<boolean>;
  aoFechar: () => void;
}) {
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();
  const [titulo, setTitulo] = useState(evento?.titulo ?? "");
  const [inicio, setInicio] = useState(paraCampoDataHora(evento?.inicio_em));
  const [fim, setFim] = useState(paraCampoDataHora(evento?.fim_em));
  const [categoria, setCategoria] = useState(evento?.categoria ?? "");
  const [descricao, setDescricao] = useState(evento?.descricao ?? "");
  const [escolhidas, setEscolhidas] = useState<string[]>(evento?.turmas ?? []);
  const [tipo, setTipo] = useState<TipoDeLigacao | "">(evento?.destino?.tipo ?? "");
  const [alvo, setAlvo] = useState(evento?.destino ? String(evento.destino.id) : "");
  const [salvando, setSalvando] = useState(false);

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    // O campo é hora local; o backend guarda com fuso. A conversão é do navegador.
    const ok = await aoSalvar({
      titulo: titulo.trim(),
      inicio_em: new Date(inicio).toISOString(),
      fim_em: fim ? new Date(fim).toISOString() : null,
      categoria: categoria.trim(),
      descricao: descricao.trim(),
      turmas: escolhidas,
      ligacao: tipo ? { tipo, id: Number(alvo) } : { tipo: "NENHUMA" },
    });
    setSalvando(false);
    if (ok) saida.fechar();
  }

  return (
    <Modal
      {...saida}
      aoFechar={() => !salvando && saida.fechar()}
      fechaClicandoFora={false}
      tamanho="grande"
      titulo={evento ? "Editar evento" : "Novo evento"}
      legenda="Vale para as turmas marcadas. Ligado a uma aula ou módulo, leva o aluno até lá."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={salvando || !titulo.trim() || !inicio || !escolhidas.length || (!!tipo && !alvo)}>
            {salvando ? "Salvando…" : "Salvar"}
          </Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={salvar} className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr_1fr]" data-foco-inicial>
          <Campo rotulo="Título">
            {(id) => <input id={id} required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} placeholder="K03 - Estequiometria" className="campo" />}
          </Campo>
          <Campo rotulo="Começa em">
            {(id) => <input id={id} type="datetime-local" required value={inicio} onChange={(e) => setInicio(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Termina em" dica="Só para períodos, como Recesso.">
            {(id) => <input id={id} type="datetime-local" value={fim} onChange={(e) => setFim(e.target.value)} className="campo" />}
          </Campo>
        </div>
        <fieldset>
          <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas</legend>
          <div className="flex flex-wrap gap-2">
            <Botao tamanho="pequeno" onClick={() => setEscolhidas(escolhidas.length === turmas.length ? [] : turmas)}>
              {escolhidas.length === turmas.length ? "Nenhuma" : "Todas"}
            </Botao>
            {turmas.map((nome) => {
              const marcada = escolhidas.includes(nome);
              return (
                <label
                  key={nome}
                  className={`flex cursor-pointer items-center rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                    marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"
                  }`}
                >
                  <input type="checkbox" className="sr-only" checked={marcada} onChange={() => setEscolhidas(marcada ? escolhidas.filter((t) => t !== nome) : [...escolhidas, nome])} />
                  {nome}
                </label>
              );
            })}
          </div>
        </fieldset>
        <div className="grid gap-3 sm:grid-cols-[1fr_2fr]">
          <Campo rotulo="Leva a">
            {(id) => (
              <select id={id} value={tipo} onChange={(e) => { setTipo(e.target.value as TipoDeLigacao | ""); setAlvo(""); }} className="campo">
                {TIPOS.map((t) => (
                  <option key={t.valor} value={t.valor}>{t.rotulo}</option>
                ))}
              </select>
            )}
          </Campo>
          {tipo && (
            <Campo rotulo="Qual" dica={tipo === "AULA" || tipo === "MODULO" ? "Fica escondido para estas turmas até a hora do evento." : undefined}>
              {(id) => (
                <select id={id} required value={alvo} onChange={(e) => setAlvo(e.target.value)} className="campo">
                  <option value="">Escolha…</option>
                  {opcoes[tipo].map((o) => (
                    <option key={o.id} value={o.id}>{o.nome}</option>
                  ))}
                </select>
              )}
            </Campo>
          )}
        </div>
        <div className="grid gap-3 sm:grid-cols-[1fr_2fr]">
          <Campo rotulo="Categoria" dica="Ex.: Aula, Simulado, Feriado.">
            {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
          </Campo>
          <Campo rotulo="Descrição" dica="Opcional; aparece para o aluno.">
            {(id) => <input id={id} maxLength={2000} value={descricao} onChange={(e) => setDescricao(e.target.value)} className="campo" />}
          </Campo>
        </div>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
      </form>
    </Modal>
  );
}
