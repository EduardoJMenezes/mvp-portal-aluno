"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { MontarProva, faltaNaProva, paraApi, type EntradaDaProva } from "@/components/MontarProva";
import { Aviso, Botao, Campo, Cartao, Pagina, TituloDeSecao } from "@/components/ui";
import { api, useDados } from "@/lib/api";

// O simulado que está sendo montado fica guardado no navegador até virar rascunho: recarregar a
// página ou fechar a aba sem querer não joga fora o título, as turmas nem as questões já postas.
const GUARDADO = "simulado-novo-em-andamento";
type EmAndamento = { titulo: string; escolhidas: string[]; abre: string; fecha: string; duracao: string; pasta: string; questoes: EntradaDaProva[] };

export default function NovoSimulado() {
  const router = useRouter();
  const turmas = useDados(() => api.turmas());
  const [titulo, setTitulo] = useState("");
  const [escolhidas, setEscolhidas] = useState<string[]>([]);
  const [abre, setAbre] = useState("");
  const [fecha, setFecha] = useState("");
  const [duracao, setDuracao] = useState("");
  const [pasta, setPasta] = useState("");
  const [questoes, setQuestoes] = useState<EntradaDaProva[]>([]);
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);
  // Só depois de ler o que estava guardado é que se começa a guardar: senão o vazio do primeiro
  // desenho apagaria o que havia.
  const [lido, setLido] = useState(false);
  const [retomado, setRetomado] = useState(false);
  const criado = useRef(false);

  useEffect(() => {
    try {
      const cru = localStorage.getItem(GUARDADO);
      const g = cru ? (JSON.parse(cru) as Partial<EmAndamento>) : null;
      if (g) {
        setTitulo(g.titulo ?? "");
        setEscolhidas(g.escolhidas ?? []);
        setAbre(g.abre ?? "");
        setFecha(g.fecha ?? "");
        setDuracao(g.duracao ?? "");
        setPasta(g.pasta ?? "");
        setQuestoes(g.questoes ?? []);
        setRetomado(true);
      }
    } catch {
      // Sem armazenamento, ou guardado ilegível: começa em branco.
    }
    setLido(true);
  }, []);

  useEffect(() => {
    if (!lido || criado.current) return;
    const vazio = !titulo && !escolhidas.length && !abre && !fecha && !duracao && !pasta && !questoes.length;
    try {
      if (vazio) localStorage.removeItem(GUARDADO);
      else localStorage.setItem(GUARDADO, JSON.stringify({ titulo, escolhidas, abre, fecha, duracao, pasta, questoes } satisfies EmAndamento));
    } catch {
      // Navegador sem armazenamento: o simulado só não fica guardado.
    }
  }, [lido, titulo, escolhidas, abre, fecha, duracao, pasta, questoes]);

  function comecarDoZero() {
    setTitulo("");
    setEscolhidas([]);
    setAbre("");
    setFecha("");
    setDuracao("");
    setPasta("");
    setQuestoes([]);
    setRetomado(false);
    setErro("");
  }

  async function criar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    if (!escolhidas.length) return setErro("Escolha ao menos uma turma.");
    if (!questoes.length) return setErro("A prova precisa de ao menos uma questão.");
    const falta = faltaNaProva(questoes);
    if (falta) return setErro(falta);
    if (abre && fecha && fecha <= abre) return setErro("O fechamento precisa vir depois da abertura.");
    setSalvando(true);
    try {
      const rascunho = await api.criarSimulado({
        titulo: titulo.trim(),
        turmas: escolhidas,
        questoes: paraApi(questoes),
        abre_em: abre || undefined,
        fecha_em: fecha || undefined,
        duracao_minutos: duracao ? Number(duracao) : undefined,
        pasta_resolucao: pasta.trim() || undefined,
      });
      // Virou rascunho no servidor: o que estava guardado no navegador já cumpriu o papel.
      criado.current = true;
      try {
        localStorage.removeItem(GUARDADO);
      } catch {
        // Sem armazenamento, não havia o que apagar.
      }
      router.replace(`/admin/rascunhos/revisar/?id=${rascunho.rascunho_id}`);
    } catch (ex) {
      setErro((ex as Error).message);
      setSalvando(false);
    }
  }

  return (
    <Pagina titulo="Novo simulado" legenda="Nasce em rascunho. Os alunos só veem depois que você aprovar, na revisão do rascunho." voltar={{ href: "/admin/simulados/", rotulo: "Simulados" }} estreita>
      {retomado && (
        <Aviso tom="info" titulo="Você tinha um simulado começado">
          Ele voltou como estava, com {questoes.length === 1 ? "1 questão" : `${questoes.length} questões`}. Fica guardado neste navegador até você criar o rascunho.{" "}
          <Botao tamanho="mini" className="ml-1 align-middle" onClick={comecarDoZero}>Começar do zero</Botao>
        </Aviso>
      )}
      <form onSubmit={criar} className="flex flex-col gap-4">
        <Cartao className="flex flex-col gap-4 p-5">
          <Campo rotulo="Título">
            {(id) => <input id={id} required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} placeholder="Ex.: Simulado 04 — Estequiometria" className="campo" />}
          </Campo>
          <fieldset>
            <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas</legend>
            {turmas.erro && <Aviso tom="erro">{turmas.erro}</Aviso>}
            <div className="flex flex-wrap gap-2">
              {turmas.dados?.map((t) => {
                const marcada = escolhidas.includes(t.nome);
                return (
                  <label key={t.id} className={`flex cursor-pointer items-center gap-2 rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"}`}>
                    <input type="checkbox" className="sr-only" checked={marcada} onChange={() => setEscolhidas((atual) => (marcada ? atual.filter((n) => n !== t.nome) : [...atual, t.nome]))} />
                    {t.nome}
                  </label>
                );
              })}
            </div>
          </fieldset>
          <div className="grid gap-4 sm:grid-cols-3">
            <Campo rotulo="Abre em" dica="Horário de Brasília">
              {(id) => <input id={id} type="datetime-local" value={abre} onChange={(e) => setAbre(e.target.value)} className="campo" />}
            </Campo>
            <Campo rotulo="Fecha em">
              {(id) => <input id={id} type="datetime-local" value={fecha} onChange={(e) => setFecha(e.target.value)} className="campo" />}
            </Campo>
            <Campo rotulo="Tempo de prova" dica="Em minutos">
              {(id) => <input id={id} type="number" min={1} max={1440} value={duracao} onChange={(e) => setDuracao(e.target.value)} className="campo" />}
            </Campo>
          </div>
          <p className="-mt-2 text-[13px] text-suave">Pode deixar agenda e tempo em branco agora; sem eles o rascunho não publica.</p>
          <Campo rotulo="Pasta do Vimeo com as resoluções" dica="Opcional. Cada questão nova recebe o vídeo com o mesmo número no título (Q07 → questão 7).">
            {(id) => <input id={id} value={pasta} onChange={(e) => setPasta(e.target.value)} placeholder="Nome ou id da pasta" className="campo" />}
          </Campo>
        </Cartao>

        <Cartao className="flex flex-col gap-4 p-5">
          <TituloDeSecao>Questões</TituloDeSecao>
          <MontarProva entradas={questoes} aoMudar={setQuestoes} permiteNova aoImportar={(lido) => lido.titulo && setTitulo((atual) => atual.trim() || lido.titulo || "")} />
        </Cartao>

        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <div className="flex flex-wrap gap-2">
          <Botao type="submit" variante="primario" ocupado={salvando}>{salvando ? "Criando…" : "Criar rascunho"}</Botao>
        </div>
      </form>
    </Pagina>
  );
}
