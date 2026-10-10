"use client";

// Um sub-módulo na tela de montar o curso: a fila das linhas (vídeo, PDF ou questão), o botão
// único de "Adicionar" e o que cada linha deixa fazer.

import { ArrowDown, ArrowUp, CornerDownRight, ExternalLink, Paperclip, Pencil, Play, Plus, SquarePen, Tags, Trash2, Users } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { OffCanvas } from "@/components/Camadas";
import { EscolherTurmas } from "@/components/EscolherTurmas";
import { Menu, type ItemDoMenu } from "@/components/Menu";
import { PassarVideos, TelaDoVideo } from "@/components/PreviaDoVideo";
import { Botao, BotaoLink, Etiqueta, Formulario, LinkDeTitulo, botao } from "@/components/ui";
import { api, type Assunto, type Aula, type ItemCurso, type Modulo, type SubModulo } from "@/lib/api";
import { duracao } from "@/lib/formato";
import { Alca, Azulejo, BIBLIOTECA, Marca, TIPOS, composicao, tipoDaLinha, useArrastar, type Arrastar, type Confirmar, type Executar, type Tipo } from "./comum";
import { AdicionarVideos } from "./AdicionarVideos";
import { AdicionarQuestao } from "./AdicionarQuestao";
import { AdicionarPdf, AssuntoDaLinha, AulasDoSubmodulo, Classificar, NovaAulaAoVivo } from "./Paineis";

type Adicionando = "video" | "pdf" | "questao" | "aovivo" | "classificar" | null;

const O_QUE_ENTRA: { tipo: Tipo; rotulo: string; dica: string }[] = [
  { tipo: "video", rotulo: "Vídeos", dica: "Do Vimeo: navegue pelas pastas e marque os que quiser." },
  { tipo: "pdf", rotulo: "PDF", dica: "Um material que já existe ou um arquivo novo." },
  { tipo: "questao", rotulo: "Questão", dica: "Nova, ou do banco de questões." },
  { tipo: "aovivo", rotulo: "Aula ao vivo", dica: "Agenda no Zoom; a gravação entra aqui." },
];

export function SecaoDoSubmodulo({
  modulo,
  sub,
  vizinhos,
  aoMover,
  aoReordenar,
  nomeDaTurma,
  todasAsTurmas,
  assuntos,
  aulas,
  categoriasDeAula,
  executar,
  confirmar,
}: {
  modulo: Modulo;
  sub: SubModulo;
  vizinhos: { acima: boolean; abaixo: boolean };
  aoMover: (passo: -1 | 1) => void;
  aoReordenar: (ids: number[]) => void;
  nomeDaTurma: string;
  todasAsTurmas: string[];
  assuntos: Assunto[];
  aulas: Aula[];
  categoriasDeAula: string[];
  executar: Executar;
  confirmar: Confirmar;
}) {
  const [adicionando, setAdicionando] = useState<Adicionando>(null);
  const [renomeando, setRenomeando] = useState(false);
  const [nome, setNome] = useState(sub.nome);
  const publicados = sub.itens.filter((i) => i.status === "PUBLICADO").length;
  const outros = modulo.submodulos.filter((s) => s.id !== sub.id);
  const arrastar = useArrastar(sub.itens.map((i) => i.id), Object.fromEntries(sub.itens.map((i) => [i.id, i.nome])), aoReordenar);
  const fechar = () => setAdicionando(null);
  const semAssunto = sub.itens.filter((i) => !i.assuntos?.length).length;
  // A prévia do vídeo cru: qual linha está aberta, e se o painel está na tela (ao fechar, a linha
  // continua guardada enquanto ele sai).
  const [previa, setPrevia] = useState<number | null>(null);
  const [vendoPrevia, setVendoPrevia] = useState(false);
  const videos = sub.itens.filter((i) => tipoDaLinha(i) === "video");
  const naPrevia = videos.findIndex((i) => i.id === previa);
  const videoDaPrevia = naPrevia >= 0 ? videos[naPrevia] : null;
  const ver = (item: ItemCurso) => {
    setPrevia(item.id);
    setVendoPrevia(true);
  };

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${sub.nome}"?`,
      texto: publicados ? `${publicados === 1 ? "O item publicado some" : `Os ${publicados} itens publicados somem`} da tela dos alunos na hora.` : "Nenhum item publicado é afetado.",
      confirmar: "Remover sub-módulo",
      perigo: true,
    });
    if (sim) await executar(() => api.removerSubmodulo(BIBLIOTECA, modulo.id, sub.id), `Sub-módulo "${sub.nome}" removido.`);
  }

  async function renomear(e: FormEvent) {
    e.preventDefault();
    if (nome.trim() === sub.nome) return setRenomeando(false);
    if (await executar(() => api.renomearSubmodulo(sub.id, nome.trim()))) setRenomeando(false);
  }

  const entradas: ItemDoMenu[] = O_QUE_ENTRA.map((o) => ({ rotulo: o.rotulo, dica: o.dica, icone: TIPOS[o.tipo].Icone, aoEscolher: () => setAdicionando(o.tipo) }));
  const vazio = sub.itens.length === 0 && aulas.length === 0;

  return (
    <section aria-label={sub.nome} className="border-t border-borda">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 bg-canvas/60 px-4 py-2.5 sm:px-5">
        {renomeando ? (
          <Formulario onSubmit={renomear} className="flex flex-1 flex-wrap items-center gap-2">
            <label htmlFor={`nome-sub-${sub.id}`} className="sr-only">Nome do sub-módulo</label>
            <input id={`nome-sub-${sub.id}`} autoFocus onFocus={(e) => e.target.select()} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo max-w-xs py-1.5" />
            <Botao type="submit" variante="primario" tamanho="pequeno">Salvar</Botao>
            <Botao tamanho="pequeno" onClick={() => setRenomeando(false)}>Cancelar</Botao>
          </Formulario>
        ) : (
          <>
            <h3 className="text-[17px] font-bold tracking-[-0.01em] text-tinta">{sub.nome}</h3>
            <p className="text-[13px] text-suave">
              {composicao(sub.itens)}
              {semAssunto > 0 && <span title="Sem assunto, a linha não entra no que o aluno recebe para revisar">, {semAssunto} sem assunto</span>}
            </p>
            <div className="ml-auto flex items-center gap-1">
              <Menu rotulo={`Adicionar em ${sub.nome}`} itens={entradas} largura="w-80" className={botao("secundario", "pequeno")}>
                <Plus aria-hidden="true" className="size-4" strokeWidth={2.4} />
                Adicionar
              </Menu>
              <Menu
                rotulo={`Mais ações de ${sub.nome}`}
                itens={[
                  { rotulo: "Renomear", icone: Pencil, aoEscolher: () => { setNome(sub.nome); setRenomeando(true); } },
                  { rotulo: "Classificar por assunto", icone: Tags, aoEscolher: () => setAdicionando("classificar") },
                  { rotulo: "Mover para cima", icone: ArrowUp, desabilitado: !vizinhos.acima, aoEscolher: () => aoMover(-1) },
                  { rotulo: "Mover para baixo", icone: ArrowDown, desabilitado: !vizinhos.abaixo, aoEscolher: () => aoMover(1) },
                  "divisor",
                  { rotulo: "Remover sub-módulo", icone: Trash2, perigo: true, aoEscolher: () => void remover() },
                ]}
              />
            </div>
          </>
        )}
      </div>

      {aulas.length > 0 && <AulasDoSubmodulo aulas={aulas} executar={executar} />}

      {sub.itens.length > 0 && (
        <ol className={`divide-y divide-borda/70 ${aulas.length ? "border-t border-borda/70" : ""}`}>
          {sub.itens.map((item, j) => (
            <Linha
              key={item.id}
              item={item}
              modulo={modulo}
              sub={sub}
              outros={outros}
              arrastar={arrastar}
              primeira={j === 0}
              ultima={j === sub.itens.length - 1}
              todasAsTurmas={todasAsTurmas}
              assuntos={assuntos}
              executar={executar}
              confirmar={confirmar}
              aoVer={() => ver(item)}
            />
          ))}
        </ol>
      )}
      <p aria-live="polite" className="sr-only">{arrastar.anuncio}</p>

      {vazio && (
        <div className="px-4 py-5 sm:px-5">
          <p className="text-[15px] text-suave">
            {nomeDaTurma ? `${nomeDaTurma} não vê nada em ${sub.nome}.` : `${sub.nome} ainda está vazio.`} Comece por:
          </p>
          <div className="mt-2.5 flex flex-wrap gap-2">
            {O_QUE_ENTRA.map((o) => {
              const Icone = TIPOS[o.tipo].Icone;
              return (
                <button key={o.tipo} type="button" onClick={() => setAdicionando(o.tipo)} className="inline-flex items-center gap-2 rounded-campo border border-dashed border-borda-campo/70 bg-papel px-3.5 py-2 text-sm font-semibold text-tinta-2 transition-colors hover:border-acento hover:bg-lilas hover:text-acento-forte">
                  <Icone aria-hidden="true" className="size-4" strokeWidth={2} />
                  {o.rotulo}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <OffCanvas
        aberto={vendoPrevia && !!videoDaPrevia}
        aoFechar={() => setVendoPrevia(false)}
        lado="direita"
        tamanho="grande"
        titulo={videoDaPrevia?.nome ?? ""}
        legenda={`${sub.nome}, em ${modulo.nome}`}
        rodape={
          <div className="flex flex-wrap items-center justify-between gap-3">
            <PassarVideos posicao={naPrevia} total={videos.length} aoPassar={(passo) => setPrevia(videos[naPrevia + passo]?.id ?? previa)} />
            <Botao onClick={() => setVendoPrevia(false)}>Fechar</Botao>
          </div>
        }
      >
        {videoDaPrevia && (
          <div className="flex flex-col gap-3">
            <TelaDoVideo video={{ vimeo_id: videoDaPrevia.vimeo_id, titulo: videoDaPrevia.nome, embed_url: videoDaPrevia.embed_url }} />
            <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-suave">
              {videoDaPrevia.status !== "PUBLICADO" && <Etiqueta tom="atencao">Rascunho</Etiqueta>}
              {videoDaPrevia.duracao_segundos ? <span className="tabular-nums">{duracao(videoDaPrevia.duracao_segundos)}</span> : null}
              {videoDaPrevia.vimeo_id && (
                <BotaoLink tamanho="mini" href={`https://vimeo.com/${videoDaPrevia.vimeo_id}`}>Abrir no Vimeo</BotaoLink>
              )}
            </p>
          </div>
        )}
      </OffCanvas>

      {/* Tudo o que se adiciona abre por cima da página: a lista do sub-módulo não sai do lugar. */}
      {adicionando === "video" && <AdicionarVideos modulo={modulo} sub={sub} executar={executar} aoFechar={fechar} />}
      {adicionando === "pdf" && (
        <AdicionarPdf
          titulo="Adicionar PDF"
          legenda={`Entra em ${sub.nome}, já publicado. Para o PDF que acompanha um vídeo, use "Anexar PDF" no menu do vídeo.`}
          aoEscolher={(id) => executar(() => api.pdfNoSubmodulo(sub.id, id), `PDF adicionado em ${sub.nome}, já publicado.`)}
          aoFechar={fechar}
        />
      )}
      {adicionando === "questao" && <AdicionarQuestao modulo={modulo} sub={sub} assuntos={assuntos} executar={executar} aoFechar={fechar} />}
      {adicionando === "aovivo" && <NovaAulaAoVivo turmas={modulo.turmas ?? []} sub={sub} categorias={categoriasDeAula} executar={executar} aoFechar={fechar} />}
      {adicionando === "classificar" && <Classificar modulo={modulo} sub={sub} assuntos={assuntos} executar={executar} aoFechar={fechar} />}
    </section>
  );
}

// --- a linha -------------------------------------------------------------------

function Linha({
  item,
  modulo,
  sub,
  outros,
  arrastar,
  primeira,
  ultima,
  todasAsTurmas,
  assuntos,
  executar,
  confirmar,
  aoVer,
}: {
  item: ItemCurso;
  modulo: Modulo;
  sub: SubModulo;
  outros: SubModulo[];
  arrastar: Arrastar;
  primeira: boolean;
  ultima: boolean;
  todasAsTurmas: string[];
  assuntos: Assunto[];
  executar: Executar;
  confirmar: Confirmar;
  aoVer: () => void;
}) {
  const router = useRouter();
  const [modo, setModo] = useState<"nome" | "pdf" | "turmas" | "assunto" | null>(null);
  const [nome, setNome] = useState(item.nome);
  const tipo = tipoDaLinha(item);
  const restrita = !!item.turmas?.length;
  const voltar = () => setModo(null);

  async function renomear(e: FormEvent) {
    e.preventDefault();
    if (nome.trim() === item.nome) return voltar();
    if (await executar(() => api.editarItem(BIBLIOTECA, modulo.id, sub.id, item.id, { nome: nome.trim() }))) voltar();
  }

  async function remover() {
    const sim = await confirmar({
      titulo: `Remover "${item.nome}"?`,
      texto: item.status === "PUBLICADO" ? "Sai da tela dos alunos na hora." : "Ainda estava em rascunho; os alunos não notam.",
      confirmar: "Remover",
      perigo: true,
    });
    if (sim) await executar(() => api.removerItem(BIBLIOTECA, modulo.id, sub.id, item.id), `"${item.nome}" removido.`);
  }

  const acoes: (ItemDoMenu | false)[] = [
    tipo === "video" && { rotulo: "Ver o vídeo", icone: Play, aoEscolher: aoVer },
    { rotulo: "Renomear", icone: Pencil, aoEscolher: () => { setNome(item.nome); setModo("nome"); } },
    tipo === "questao" && { rotulo: "Editar a questão", icone: SquarePen, aoEscolher: () => router.push(`/admin/questoes/editar/?id=${item.questao!.questao_id}&modulo=${modulo.id}`) },
    tipo === "pdf" && !!item.material && { rotulo: "Abrir o PDF", icone: ExternalLink, aoEscolher: () => router.push(`/materiais/ler/?id=${item.material!.material_id}`) },
    tipo !== "questao" && { rotulo: item.material ? "Trocar o PDF" : "Anexar PDF", icone: Paperclip, aoEscolher: () => setModo("pdf") },
    tipo === "video" && !!item.material && { rotulo: "Tirar o PDF", icone: Paperclip, aoEscolher: () => void executar(() => api.materialDoItem(item.id, null), `"${item.nome}" ficou sem PDF.`) },
    { rotulo: item.assuntos?.length ? "Trocar o assunto" : "Pôr assunto", icone: Tags, aoEscolher: () => setModo("assunto") },
    { rotulo: restrita ? "Mudar as turmas que veem" : "Só para algumas turmas", icone: Users, aoEscolher: () => setModo("turmas") },
    "divisor",
    { rotulo: "Mover para cima", icone: ArrowUp, desabilitado: primeira, aoEscolher: () => arrastar.passo(item.id, -1) },
    { rotulo: "Mover para baixo", icone: ArrowDown, desabilitado: ultima, aoEscolher: () => arrastar.passo(item.id, 1) },
    ...outros.map((o): ItemDoMenu => ({
      rotulo: `Mover para ${o.nome}`,
      icone: CornerDownRight,
      aoEscolher: () => void executar(() => api.editarItem(BIBLIOTECA, modulo.id, sub.id, item.id, { mover_para_submodulo: String(o.id) }), `"${item.nome}" foi para ${o.nome}.`),
    })),
    "divisor",
    { rotulo: "Remover", icone: Trash2, perigo: true, aoEscolher: () => void remover() },
  ];

  return (
    <li {...arrastar.linha(item.id)} className={`group relative transition-opacity ${arrastar.arrastando === item.id ? "opacity-40" : ""}`}>
      <Marca onde={arrastar.marca(item.id)} />
      <div className="flex items-start gap-2 py-2.5 pl-2 pr-3 sm:pl-3 pointer-coarse:pl-4">
        <Alca arrastar={arrastar} id={item.id} nome={item.nome} className="sm:opacity-40 sm:group-focus-within:opacity-100 sm:group-hover:opacity-100" />
        {tipo === "video" ? (
          <button type="button" onClick={aoVer} aria-label={`Ver o vídeo ${item.nome}`} title="Ver o vídeo" className="group/ver shrink-0 rounded-[10px]">
            <Azulejo tipo="video" className="transition-colors group-hover/ver:bg-acento group-hover/ver:text-white" />
          </button>
        ) : (
          <Azulejo tipo={tipo} />
        )}
        <div className="min-w-0 flex-1 self-center">
          {modo === "nome" ? (
            <Formulario onSubmit={renomear} className="flex flex-wrap items-center gap-2">
              <label htmlFor={`nome-item-${item.id}`} className="sr-only">Nome do item</label>
              <input id={`nome-item-${item.id}`} autoFocus onFocus={(e) => e.target.select()} required value={nome} onChange={(e) => setNome(e.target.value)} className="campo min-w-0 flex-1 py-1.5" />
              <Botao type="submit" tamanho="pequeno" variante="primario">Salvar</Botao>
              <Botao tamanho="pequeno" onClick={voltar}>Cancelar</Botao>
            </Formulario>
          ) : (
            <>
              <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="font-medium text-tinta">{item.nome}</span>
                {item.status !== "PUBLICADO" && <Etiqueta tom="atencao">Rascunho</Etiqueta>}
                {restrita && <Etiqueta tom="info">Só {item.turmas!.join(", ")}</Etiqueta>}
              </p>
              <Detalhe item={item} tipo={tipo} />
            </>
          )}
        </div>
        <Menu rotulo={`Ações de ${item.nome}`} itens={acoes} largura="w-64" />
      </div>

      {modo === "pdf" && (
        <AdicionarPdf
          titulo={item.material ? "Trocar o PDF" : "Anexar PDF"}
          legenda={`O PDF que acompanha "${item.nome}". O aluno abre ao lado da aula.`}
          aoEscolher={(id) => executar(() => api.materialDoItem(item.id, id), `PDF de "${item.nome}" salvo.`)}
          aoFechar={voltar}
        />
      )}
      {modo === "assunto" && <AssuntoDaLinha item={item} tipo={tipo} assuntos={assuntos} executar={executar} aoFechar={voltar} />}
      {modo === "turmas" && (
        <EscolherTurmas
          abertoDeInicio
          semResumo
          modal={`Quem vê "${item.nome}"`}
          turmas={todasAsTurmas}
          marcadas={item.turmas ?? []}
          vazio="Todas as turmas do módulo"
          aoSalvar={(t) => executar(() => api.turmasDoItem(item.id, t), `Turmas de "${item.nome}" salvas.`)}
          aoFechar={voltar}
        />
      )}
    </li>
  );
}

/** O assunto do conteúdo da linha, por extenso: "Estequiometria › Mol". Sem assunto, nada. */
function AssuntoNaLinha({ item }: { item: ItemCurso }) {
  if (!item.assuntos?.length) return null;
  return (
    <span className="ml-2 inline-flex items-center gap-1 align-middle font-medium text-tinta-2">
      <Tags aria-hidden="true" className="size-3.5 text-suave" strokeWidth={2} />
      <span className="sr-only">Assunto: </span>
      {item.assuntos.map((a) => (a.subassunto ? `${a.assunto} › ${a.subassunto}` : a.assunto)).join(", ")}
    </span>
  );
}

/** A segunda linha: o tipo por extenso, o que ajuda a reconhecer o conteúdo e o assunto dele. */
function Detalhe({ item, tipo }: { item: ItemCurso; tipo: Tipo }) {
  const classe = "mt-0.5 text-[13px] text-suave";
  if (tipo === "questao" && item.questao) {
    return (
      <p className={classe}>
        <span className="line-clamp-1">
          Questão #{item.questao.questao_id}
          {item.questao.status && item.questao.status !== "PUBLICADO" && " (em rascunho no banco)"}
          {item.questao.resumo && `: ${item.questao.resumo}`}
        </span>
        {item.assuntos?.length ? <span className="-ml-2 block"><AssuntoNaLinha item={item} /></span> : null}
      </p>
    );
  }
  if (tipo === "pdf") {
    return (
      <p className={classe}>
        PDF
        {item.material && item.material.titulo !== item.nome && (
          <>
            : <LinkDeTitulo href={`/materiais/ler/?id=${item.material.material_id}`}>{item.material.titulo}</LinkDeTitulo>
          </>
        )}
        <AssuntoNaLinha item={item} />
      </p>
    );
  }
  return (
    <p className={classe}>
      Vídeo
      {item.material && (
        <>
          , com o PDF <LinkDeTitulo href={`/materiais/ler/?id=${item.material.material_id}`}>{item.material.titulo}</LinkDeTitulo>
        </>
      )}
      <AssuntoNaLinha item={item} />
    </p>
  );
}
