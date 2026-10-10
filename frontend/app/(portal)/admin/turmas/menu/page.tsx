"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { CampoCategoria, categoriasDe } from "@/components/Categoria";
import { Aviso, Botao, Cartao, Estado, Pagina, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type BotaoDoMenu, type Funcionalidade } from "@/lib/api";

export default function PaginaDoMenu() {
  return (
    <Suspense>
      <MenuDaTurma />
    </Suspense>
  );
}

const FUNCIONALIDADES: { valor: Funcionalidade; rotulo: string }[] = [
  { valor: "CURSO", rotulo: "Curso (aulas gravadas)" },
  { valor: "AULAS", rotulo: "Aulas ao vivo" },
  { valor: "SIMULADOS", rotulo: "Simulados" },
  { valor: "MATERIAIS", rotulo: "Materiais" },
  { valor: "AGENDA", rotulo: "Agenda" },
];

/**
 * O professor monta o menu do aluno de uma turma (decisão 0009): cada botão é uma feature
 * recortada por uma categoria. Início e Desempenho são fixos e ficam nas pontas.
 */
function MenuDaTurma() {
  const turmaId = Number(useSearchParams().get("turma"));
  const dados = useDados(async () => {
    const [menu, turmas, modulos, aulas, simulados, materiais, agenda] = await Promise.all([
      api.menuDaTurma(turmaId), api.turmas(), api.modulos(turmaId), api.aulasDoProfessor(),
      api.simuladosDoProfessor(String(turmaId)), api.materiaisDoProfessor(), api.agendaDoProfessor(String(turmaId)),
    ]);
    // As categorias que já existem em cada feature: viram sugestão e conferem se o botão abre vazio.
    const categorias: Record<Funcionalidade, string[]> = {
      CURSO: categoriasDe(modulos),
      AULAS: categoriasDe(aulas),
      SIMULADOS: categoriasDe(simulados),
      MATERIAIS: categoriasDe(materiais),
      AGENDA: categoriasDe(agenda),
    };
    return { menu, turmas, categorias };
  }, [turmaId]);
  const [botoes, setBotoes] = useState<BotaoDoMenu[]>([]);
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [copiarDe, setCopiarDe] = useState("");
  const [dialogo, confirmar] = useConfirmar();

  useEffect(() => {
    if (dados.dados) setBotoes(dados.dados.menu.botoes);
  }, [dados.dados]);

  const turma = dados.dados?.turmas.find((t) => t.id === turmaId);
  const mudar = (i: number, parte: Partial<BotaoDoMenu>) => setBotoes((bs) => bs.map((b, j) => (j === i ? { ...b, ...parte } : b)));
  const trocar = (i: number, k: number) =>
    setBotoes((bs) => {
      const novo = [...bs];
      [novo[i], novo[k]] = [novo[k], novo[i]];
      return novo;
    });

  async function gravar(acao: () => Promise<unknown>, mensagem: string) {
    setErro("");
    setAviso("");
    try {
      await acao();
      await dados.recarregar();
      setAviso(mensagem);
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  async function copiar() {
    const origem = dados.dados?.turmas.find((t) => String(t.id) === copiarDe);
    if (!origem) return;
    const sim = await confirmar({
      titulo: `Copiar o menu de ${origem.nome}?`,
      texto: `O menu de ${turma?.nome ?? "esta turma"} é trocado pelo de ${origem.nome}, e os alunos veem na hora.`,
      confirmar: "Copiar menu",
    });
    if (sim) await gravar(() => api.copiarMenu(turmaId, origem.id), `Menu copiado de ${origem.nome}.`);
  }

  return (
    <Pagina
      titulo={turma ? `Menu do aluno · ${turma.nome}` : "Menu do aluno"}
      legenda="Cada botão leva a uma parte da plataforma, e a categoria escolhe o que aparece nela. Salvar vale na hora para os alunos da turma."
      voltar={{ href: "/admin/turmas/", rotulo: "Turmas" }}
    >
      {dialogo}
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      {aviso && <Aviso tom="sucesso">{aviso}</Aviso>}
      <Estado {...dados} linhas={4}>
        {({ menu, turmas, categorias }) => (
          <>
            {menu.padrao && (
              <Aviso tom="info">Esta turma ainda usa o menu de sempre, mostrado abaixo. Mude à vontade e salve.</Aviso>
            )}

            <Cartao className="flex flex-col gap-4 p-5">
              <div className="flex flex-wrap items-center gap-1.5 text-sm" aria-label="Como o aluno vê">
                <span className="font-semibold text-tinta-2">Como o aluno vê:</span>
                {["Início", ...botoes.map((b) => b.rotulo || "(sem nome)"), "Desempenho"].map((r, i) => (
                  <span key={i} className="rounded-full bg-canvas px-3 py-1 text-tinta">{r}</span>
                ))}
              </div>

              <ol className="flex flex-col gap-3">
                {botoes.map((b, i) => {
                  const existentes = categorias[b.funcionalidade];
                  const vazio = !!b.categoria?.trim() && !existentes.some((c) => c.toLowerCase() === b.categoria!.trim().toLowerCase());
                  return (
                    <li key={i} className="grid gap-2 rounded-cartao border border-borda p-3 sm:grid-cols-[1.2fr_1fr_1fr_auto] sm:items-end">
                      <label className="flex flex-col gap-1 text-sm font-semibold text-tinta-2">
                        Nome do botão
                        <input maxLength={40} value={b.rotulo} onChange={(e) => mudar(i, { rotulo: e.target.value })} className="campo font-normal" placeholder="Ex.: Simulados Rodmelo" />
                      </label>
                      <label className="flex flex-col gap-1 text-sm font-semibold text-tinta-2">
                        Leva para
                        <select value={b.funcionalidade} onChange={(e) => mudar(i, { funcionalidade: e.target.value as Funcionalidade })} className="campo font-normal">
                          {FUNCIONALIDADES.map((f) => (
                            <option key={f.valor} value={f.valor}>{f.rotulo}</option>
                          ))}
                        </select>
                      </label>
                      <div className="flex flex-col gap-1">
                        <label htmlFor={`categoria-${i}`} className="text-sm font-semibold text-tinta-2">Categoria</label>
                        <CampoCategoria id={`categoria-${i}`} valor={b.categoria ?? ""} aoMudar={(c) => mudar(i, { categoria: c })} sugestoes={existentes} />
                      </div>
                      <div className="flex gap-1">
                        <Botao tamanho="pequeno" disabled={i === 0} onClick={() => trocar(i, i - 1)} aria-label={`Subir ${b.rotulo}`}>↑</Botao>
                        <Botao tamanho="pequeno" disabled={i === botoes.length - 1} onClick={() => trocar(i, i + 1)} aria-label={`Descer ${b.rotulo}`}>↓</Botao>
                        <Botao tamanho="pequeno" variante="perigo" onClick={() => setBotoes((bs) => bs.filter((_, j) => j !== i))} aria-label={`Tirar ${b.rotulo}`}>Tirar</Botao>
                      </div>
                      <p className="text-[13px] text-suave sm:col-span-4">
                        {!b.categoria?.trim()
                          ? "Sem categoria: o botão mostra tudo desta parte."
                          : vazio
                            ? <span className="text-atencao">Nada tem a categoria &quot;{b.categoria.trim()}&quot; ainda: o botão vai abrir vazio.</span>
                            : `Mostra só o que tem a categoria "${b.categoria.trim()}".`}
                      </p>
                    </li>
                  );
                })}
              </ol>

              <div className="flex flex-wrap gap-2">
                <Botao disabled={botoes.length >= 12} onClick={() => setBotoes((bs) => [...bs, { rotulo: "", funcionalidade: "CURSO", categoria: "" }])}>
                  Adicionar botão
                </Botao>
                <Botao
                  variante="primario"
                  onClick={() =>
                    gravar(
                      () => api.definirMenu(turmaId, botoes.map((b) => ({ ...b, rotulo: b.rotulo.trim(), categoria: b.categoria?.trim() || null }))),
                      "Menu salvo. Os alunos já veem o novo.",
                    )
                  }
                >
                  Salvar menu
                </Botao>
                {!menu.padrao && (
                  <Botao onClick={() => gravar(() => api.definirMenu(turmaId, []), "A turma voltou ao menu de sempre.")}>
                    Voltar ao menu de sempre
                  </Botao>
                )}
              </div>
            </Cartao>

            <Cartao className="flex flex-col gap-3 p-5">
              <TituloDeSecao>Copiar de outra turma</TituloDeSecao>
              <div className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm font-semibold text-tinta-2">
                  Turma
                  <select value={copiarDe} onChange={(e) => setCopiarDe(e.target.value)} className="campo w-auto font-normal">
                    <option value="">Escolha…</option>
                    {turmas.filter((t) => t.id !== turmaId).map((t) => (
                      <option key={t.id} value={t.id}>{t.nome}</option>
                    ))}
                  </select>
                </label>
                <Botao disabled={!copiarDe} onClick={() => copiar()}>Copiar menu</Botao>
              </div>
            </Cartao>
          </>
        )}
      </Estado>
    </Pagina>
  );
}
