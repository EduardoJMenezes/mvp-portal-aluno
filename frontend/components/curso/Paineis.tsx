"use client";

// O que se abre por cima da página para pôr conteúdo num sub-módulo: PDF, questão, aula ao vivo e a
// etiqueta de assunto. Os vídeos do Vimeo estão em AdicionarVideos.

import Link from "next/link";
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { Modal, OffCanvas, useSaida, type TamanhoDaCamada } from "@/components/Camadas";
import { CampoCategoria } from "@/components/Categoria";
import { ColocarVideo } from "@/components/ColocarVideo";
import { BuscaNoBanco } from "@/components/MontarProva";
import { EscolherPdf, PdfDaAulaAoVivo } from "@/components/Pdf";
import { Aviso, Botao, BotaoLink, Campo } from "@/components/ui";
import { abrirEmNovaAba, api, type Assunto, type Aula, type ItemCurso, type Modulo, type SubModulo } from "@/lib/api";
import { emBrasilia, plural } from "@/lib/formato";
import { Azulejo, BIBLIOTECA, type Executar, type Tipo } from "./comum";

/**
 * A moldura comum do que se abre para pôr conteúdo: um modal ou um off-canvas, com o tipo do que
 * entra ao lado do título. Quem usa monta o painel só quando abre, com `useSaida`.
 */
export function Painel({
  tipo,
  titulo,
  legenda,
  camada = "modal",
  tamanho = "medio",
  aberto,
  aoFechar,
  aoSumir,
  rodape,
  children,
}: {
  tipo?: Tipo;
  titulo: string;
  legenda?: ReactNode;
  camada?: "modal" | "offcanvas";
  tamanho?: TamanhoDaCamada;
  aberto: boolean;
  aoFechar: () => void;
  aoSumir?: () => void;
  rodape?: ReactNode;
  children: ReactNode;
}) {
  const cabecalho = (
    <span className="flex items-center gap-3">
      {tipo && <Azulejo tipo={tipo} />}
      {titulo}
    </span>
  );
  const Camada = camada === "offcanvas" ? OffCanvas : Modal;
  return (
    <Camada aberto={aberto} aoFechar={aoFechar} aoSumir={aoSumir} tamanho={tamanho} titulo={cabecalho} legenda={legenda} rodape={rodape}>
      {children}
    </Camada>
  );
}

// --- PDF -------------------------------------------------------------------------

/** O PDF que entra: um material que já existe ou um arquivo novo. Serve à linha nova e ao PDF de um vídeo. */
export function AdicionarPdf({ titulo, legenda, aoEscolher, aoFechar }: { titulo: string; legenda: ReactNode; aoEscolher: (materialId: number) => Promise<boolean>; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  return (
    <Painel tipo="pdf" titulo={titulo} legenda={legenda} {...saida} aoFechar={saida.fechar}>
      <EscolherPdf abertoDeInicio moldura={false} aoEscolher={aoEscolher} aoFechar={saida.fechar} />
    </Painel>
  );
}

// --- questão -------------------------------------------------------------------

/** Uma linha de questão: criada agora, no editor, ou tirada do acervo. Já sai publicada. */
export function AdicionarQuestao({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const jaNaAula = new Set<number | undefined>(sub.itens.map((i) => i.questao?.questao_id).filter((id) => id !== undefined));
  const destino = encodeURIComponent(`${modulo.nome} › ${sub.nome}`);

  return (
    <Painel
      tipo="questao"
      titulo="Adicionar questão"
      legenda={`Em ${sub.nome}. O aluno responde ali mesmo, uma vez só, e vê o gabarito e a resolução na hora. Entra publicada.`}
      camada="offcanvas"
      tamanho="grande"
      {...saida}
      aoFechar={saida.fechar}
      rodape={
        <div className="flex justify-end">
          <Botao onClick={saida.fechar}>Fechar</Botao>
        </div>
      }
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center gap-3 rounded-cartao border border-borda bg-canvas/60 p-4">
          <p className="min-w-48 flex-1 text-[15px] text-tinta-2">A questão ainda não existe? Escreva agora; ela já entra nesta aula.</p>
          <BotaoLink variante="primario" href={`/admin/questoes/editar/?submodulo=${sub.id}&modulo=${modulo.id}&destino=${destino}`}>Escrever uma questão nova</BotaoLink>
        </div>
        <div className="flex flex-col gap-2">
          <h3 className="text-[15px] font-semibold text-tinta">Ou escolha do banco de questões</h3>
          <BuscaNoBanco
            jaNaProva={jaNaAula}
            rotuloJaEsta="Na aula"
            avisarReuso={false}
            aoEscolher={(q) => void executar(() => api.questaoNoSubmodulo(sub.id, { questao_id: q.questao_id }), `Questão #${q.questao_id} adicionada em ${sub.nome}, já publicada.`)}
          />
        </div>
      </div>
    </Painel>
  );
}

// --- aula ao vivo --------------------------------------------------------------

/** Agenda e já publica: a sala do Zoom abre agora, e a aula aparece no capítulo para a turma. */
export function NovaAulaAoVivo({ turmas, sub, categorias, executar, aoFechar }: { turmas: string[]; sub: SubModulo; categorias: string[]; executar: Executar; aoFechar: () => void }) {
  const [categoria, setCategoria] = useState("");
  const [titulo, setTitulo] = useState("");
  const [quando, setQuando] = useState("");
  const [minutos, setMinutos] = useState(90);
  const [descricao, setDescricao] = useState("");
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();

  async function agendar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    const ok = await executar(async () => {
      // O campo é hora local; o backend guarda em UTC. A conversão é do navegador.
      const aula = await api.agendarAula({
        titulo: titulo.trim(),
        inicio_em: new Date(quando).toISOString(),
        minutos,
        descricao: descricao.trim(),
        turmas,
        submodulo_id: sub.id,
        categoria: categoria.trim(),
      });
      await api.editarAula(aula.aula_id, { status: "PUBLICADO" });
    }, `"${titulo.trim()}" agendada em ${sub.nome}. A turma já vê no capítulo.`);
    setSalvando(false);
    if (ok) saida.fechar();
  }

  if (turmas.length === 0) {
    return (
      <Painel tipo="aovivo" titulo="Agendar aula ao vivo" {...saida} aoFechar={saida.fechar}>
        <Aviso tom="atencao">Este módulo ainda não é de nenhuma turma. Escolha as turmas dele, no alto, e depois agende a aula.</Aviso>
      </Painel>
    );
  }

  return (
    <Painel
      tipo="aovivo"
      titulo="Agendar aula ao vivo"
      legenda={`A sala do Zoom é criada agora, para ${turmas.join(", ")}. A aula é gravada, e a gravação entra publicada em ${sub.nome}.`}
      {...saida}
      aoFechar={() => !salvando && saida.fechar()}
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" disabled={salvando || !titulo.trim() || !quando}>{salvando ? "Agendando…" : "Agendar aula"}</Botao>
        </div>
      }
    >
      <form id={idDoFormulario} onSubmit={agendar} className="flex flex-col gap-3">
        <div data-foco-inicial>
          <Campo rotulo="Título">
            {(id) => <input id={id} required maxLength={200} value={titulo} onChange={(e) => setTitulo(e.target.value)} placeholder="Estequiometria, aula 1" className="campo" />}
          </Campo>
        </div>
        <div className="grid gap-3 sm:grid-cols-[1fr_auto]">
          <Campo rotulo="Começa em">
            {(id) => <input id={id} type="datetime-local" required value={quando} onChange={(e) => setQuando(e.target.value)} className="campo" />}
          </Campo>
          <Campo rotulo="Minutos">
            {(id) => <input id={id} type="number" min={5} max={480} value={minutos} onChange={(e) => setMinutos(Number(e.target.value))} className="campo sm:w-28" />}
          </Campo>
        </div>
        <Campo rotulo="Descrição" dica="Opcional; aparece para o aluno.">
          {(id) => <input id={id} maxLength={2000} value={descricao} onChange={(e) => setDescricao(e.target.value)} className="campo" />}
        </Campo>
        <Campo rotulo="Categoria" dica="Ex.: Aula, Monitoria. Separa as lives no menu.">
          {(id) => <CampoCategoria id={id} valor={categoria} aoMudar={setCategoria} sugestoes={categorias} />}
        </Campo>
      </form>
    </Painel>
  );
}

const ESTADO_DA_AULA: Record<Aula["estado"], string> = {
  RASCUNHO: "rascunho, sem sala",
  AGENDADA: "agendada",
  AGUARDANDO: "sala aberta",
  ABERTA: "ao vivo agora",
  ENCERRADA: "encerrada",
};

/** As aulas ao vivo ligadas ao sub-módulo: ficam no alto, fora da fila, porque não têm ordem. */
export function AulasDoSubmodulo({ aulas, executar }: { aulas: Aula[]; executar: Executar }) {
  const [erro, setErro] = useState("");

  const iniciar = async (aula: Aula) => {
    setErro("");
    try {
      await abrirEmNovaAba(() => api.iniciarAula(aula.aula_id));
    } catch (ex) {
      setErro((ex as Error).message);
    }
  };

  return (
    <div className="flex flex-col">
      {erro && <Aviso tom="erro" className="mx-3 mt-3">{erro}</Aviso>}
      <ul className="divide-y divide-borda/70">
        {aulas.map((aula) => {
          const semGravacao = aula.estado === "ENCERRADA" && !aula.gravacao_item_id && aula.gravacao !== "enviando";
          const situacao = [
            `${aula.minutos} min`,
            ESTADO_DA_AULA[aula.estado],
            aula.gravacao === "enviando" && "gravação indo para o Vimeo",
            aula.gravacao_item_id && "gravação publicada no curso",
            semGravacao && "sem gravação",
          ].filter(Boolean);
          return (
            <li key={aula.aula_id} className="flex items-start gap-2 py-2.5 pl-10 pr-3 sm:pl-11">
              <Azulejo tipo="aovivo" />
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate font-medium text-tinta">{aula.titulo}</p>
                    <p className="text-[13px] text-suave">Aula ao vivo em {emBrasilia(aula.inicio_em)}, {situacao.join(", ")}</p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {aula.tem_sala && aula.estado !== "ENCERRADA" && (
                      <Botao tamanho="pequeno" variante="primario" onClick={() => void iniciar(aula)}>Iniciar</Botao>
                    )}
                    <Link href="/admin/aulas/" className="text-sm font-semibold text-acento hover:underline">Gerenciar</Link>
                  </div>
                </div>
                <PdfDaAulaAoVivo aula={aula} executar={executar} />
                {aula.estado === "ENCERRADA" && (
                  <ColocarVideo
                    aula={aula}
                    aoColocar={(link) => executar(() => api.colocarVideoNaAula(aula.aula_id, link), `Vídeo publicado no lugar da gravação de "${aula.titulo}".`)}
                  />
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// --- assunto ---------------------------------------------------------------------

/** Os dois campos de sempre: o assunto e, dentro dele, o sub-assunto. Os valores são os ids. */
function EscolherAssunto({ assuntos, assunto, subassunto, aoMudar, comVazio = false }: { assuntos: Assunto[]; assunto: string; subassunto: string; aoMudar: (assunto: string, subassunto: string) => void; comVazio?: boolean }) {
  const escolhido = assuntos.find((a) => String(a.id) === assunto);
  return (
    <>
      <div data-foco-inicial>
        <Campo rotulo="Assunto">
          {(id) => (
            <select id={id} required={!comVazio} value={assunto} onChange={(e) => aoMudar(e.target.value, "")} className="campo">
              <option value="">{comVazio ? "Sem assunto" : "Escolha…"}</option>
              {assuntos.map((a) => (
                <option key={a.id} value={a.id}>{a.nome}</option>
              ))}
            </select>
          )}
        </Campo>
      </div>
      <Campo rotulo="Sub-assunto" dica={escolhido && !escolhido.subassuntos.length ? "Este assunto não tem sub-assuntos cadastrados." : undefined}>
        {(id) => (
          <select id={id} value={subassunto} onChange={(e) => aoMudar(assunto, e.target.value)} className="campo" disabled={!escolhido?.subassuntos.length}>
            <option value="">Nenhum</option>
            {escolhido?.subassuntos.map((s) => (
              <option key={s.id} value={s.id}>{s.nome}</option>
            ))}
          </select>
        )}
      </Campo>
    </>
  );
}

const SEM_ASSUNTOS = (
  <Aviso tom="atencao">
    Nenhum assunto cadastrado. <Link href="/admin/assuntos/" className="font-semibold underline">Cadastre em Assuntos</Link>.
  </Aviso>
);

/**
 * O assunto de uma linha. Ele é do conteúdo (a questão, o vídeo ou o PDF), não da linha: o texto
 * diz isso, porque trocar aqui vale em todo lugar onde aquele conteúdo aparece.
 */
export function AssuntoDaLinha({ item, tipo, assuntos, executar, aoFechar }: { item: ItemCurso; tipo: Tipo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const atual = item.assuntos?.[0];
  const [assunto, setAssunto] = useState(atual ? String(atual.assunto_id) : "");
  const [subassunto, setSubassunto] = useState(atual?.subassunto_id ? String(atual.subassunto_id) : "");
  const [salvando, setSalvando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
    const ok = await executar(() => api.assuntoDoItem(item.id, { assunto, subassunto: subassunto || undefined }), assunto ? `Assunto de "${item.nome}" salvo.` : `"${item.nome}" ficou sem assunto.`);
    setSalvando(false);
    if (ok) saida.fechar();
  }

  const deQuem =
    tipo === "questao"
      ? "É o assunto do cadastro da questão: muda em todo lugar onde ela aparece, e é por ele que o desempenho do aluno é contado."
      : tipo === "pdf"
        ? "Fica no PDF: vale em toda linha onde este material estiver."
        : "Fica no vídeo: vale em todo módulo onde este vídeo estiver, e é por ele que o aluno recebe o vídeo para revisar.";

  return (
    <Painel
      titulo={`Assunto de "${item.nome}"`}
      legenda={deQuem}
      tamanho="pequeno"
      {...saida}
      aoFechar={() => !salvando && saida.fechar()}
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>{assuntos.length ? "Cancelar" : "Fechar"}</Botao>
          {assuntos.length > 0 && <Botao type="submit" form={idDoFormulario} variante="primario" disabled={salvando}>{salvando ? "Salvando…" : "Salvar"}</Botao>}
        </div>
      }
    >
      {!assuntos.length ? (
        SEM_ASSUNTOS
      ) : (
        <form id={idDoFormulario} onSubmit={salvar} className="flex flex-col gap-3">
          <EscolherAssunto assuntos={assuntos} assunto={assunto} subassunto={subassunto} aoMudar={(a, s) => { setAssunto(a); setSubassunto(s); }} comVazio />
        </form>
      )}
    </Painel>
  );
}

/**
 * O assunto de muitas linhas de uma vez: as de um sub-módulo (todas ou uma faixa) ou as do módulo
 * inteiro. Vale para vídeo, PDF e questão.
 */
export function Classificar({ modulo, sub, assuntos, executar, aoFechar }: { modulo: Modulo; sub?: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const [assunto, setAssunto] = useState("");
  const [subassunto, setSubassunto] = useState("");
  const [faixa, setFaixa] = useState("");
  const [soSemAssunto, setSoSemAssunto] = useState(true);
  const [aplicando, setAplicando] = useState(false);
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();
  const onde = sub ? sub.nome : modulo.nome;

  async function aplicar(e: FormEvent) {
    e.preventDefault();
    setAplicando(true);
    let feito = { classificadas: 0, puladas: 0 };
    const dados = { assunto, subassunto: subassunto || undefined, so_sem_assunto: soSemAssunto };
    const ok = await executar(
      async () => {
        feito = sub ? await api.assuntoDoSubmodulo(sub.id, { ...dados, itens: faixa.trim() || undefined }) : await api.assuntoDoModulo(modulo.id, dados);
      },
      () =>
        feito.classificadas === 0
          ? `Nenhuma linha de ${onde} mudou: todas já tinham assunto.`
          : `${plural(feito.classificadas, "linha classificada", "linhas classificadas")} em ${onde}.` + (feito.puladas ? ` ${plural(feito.puladas, "ficou como estava", "ficaram como estavam")}.` : ""),
    );
    setAplicando(false);
    if (ok) saida.fechar();
  }

  return (
    <Painel
      titulo={sub ? "Classificar o sub-módulo por assunto" : "Classificar o módulo por assunto"}
      legenda={`Vale para as linhas de ${onde}: vídeos, PDFs e questões. Depois dá para afinar linha a linha, no menu de cada uma.`}
      {...saida}
      aoFechar={() => !aplicando && saida.fechar()}
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={aplicando}>{assuntos.length ? "Cancelar" : "Fechar"}</Botao>
          {assuntos.length > 0 && <Botao type="submit" form={idDoFormulario} variante="primario" disabled={!assunto || aplicando}>{aplicando ? "Aplicando…" : "Aplicar"}</Botao>}
        </div>
      }
    >
      {!assuntos.length ? (
        SEM_ASSUNTOS
      ) : (
        <form id={idDoFormulario} onSubmit={aplicar} className="flex flex-col gap-3">
          <EscolherAssunto assuntos={assuntos} assunto={assunto} subassunto={subassunto} aoMudar={(a, s) => { setAssunto(a); setSubassunto(s); }} />
          {sub && (
            <Campo rotulo="Quais linhas" dica="Em branco, todas. Ou uma faixa, ex.: Q01-Q03.">
              {(id) => <input id={id} value={faixa} onChange={(e) => setFaixa(e.target.value)} placeholder="Q01-Q03" className="campo" />}
            </Campo>
          )}
          <label className="flex cursor-pointer items-start gap-2.5 text-[15px] text-tinta">
            <input type="checkbox" checked={soSemAssunto} onChange={(e) => setSoSemAssunto(e.target.checked)} className="mt-1 size-4 accent-acento" />
            <span>
              Só as linhas que ainda não têm assunto
              <span className="block text-[13px] text-suave">Desmarcado, troca também o assunto das que já têm.</span>
            </span>
          </label>
        </form>
      )}
    </Painel>
  );
}
