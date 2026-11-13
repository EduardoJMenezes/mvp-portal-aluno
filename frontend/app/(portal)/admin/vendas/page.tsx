"use client";

import { useId, useState, type FormEvent } from "react";
import { OffCanvas, useSaida } from "@/components/Camadas";
import { Abas, Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import {
  api,
  useDados,
  type DadosDoPlano,
  type PedidoDeVenda,
  type PlanoDeVenda,
  type PrecoDoPlano,
  type StatusDoPedido,
  type TipoDePlano,
} from "@/lib/api";
import { dataCurta, dia, plural, reais } from "@/lib/formato";

type Aba = "planos" | "pedidos" | "envios";

export default function Vendas() {
  const [aba, setAba] = useState<Aba>("planos");
  return (
    <Pagina
      titulo="Vendas"
      legenda="O plano é o que você vende; a turma é o que o aluno acessa. Quem paga entra sozinho nas turmas do plano."
    >
      <Abas<Aba>
        abas={[
          { valor: "planos", rotulo: "Planos" },
          { valor: "pedidos", rotulo: "Pedidos" },
          { valor: "envios", rotulo: "Envios do material" },
        ]}
        atual={aba}
        aoTrocar={setAba}
      />
      {aba === "planos" ? <Planos /> : aba === "pedidos" ? <Pedidos /> : <Envios />}
    </Pagina>
  );
}

// --- planos ------------------------------------------------------------------

function Planos() {
  const lista = useDados(() => api.planosDeVenda());
  const turmas = useDados(() => api.turmas());
  const [editando, setEditando] = useState<PlanoDeVenda | "novo" | null>(null);
  const [copiado, setCopiado] = useState(0);
  const nomesDasTurmas = turmas.dados?.map((t) => t.nome) ?? [];

  async function copiar(plano: PlanoDeVenda) {
    await navigator.clipboard.writeText(`${window.location.origin}/assinar/${plano.link}`);
    setCopiado(plano.plano_id);
    setTimeout(() => setCopiado(0), 2000);
  }

  async function salvar(dados: DadosDoPlano) {
    if (editando === "novo") await api.criarPlano(dados);
    else if (editando) await api.editarPlano(editando.plano_id, dados);
    // Quem fecha é o painel, depois de salvar: aqui só se relê a lista.
    await lista.recarregar();
  }

  return (
    <div className="flex flex-col gap-4">
      {/* O plano, novo ou em edição, abre num off-canvas: a lista dos planos fica à vista atrás. */}
      {editando && (
        <FormularioDoPlano
          key={editando === "novo" ? "novo" : editando.plano_id}
          plano={editando === "novo" ? null : editando}
          turmas={nomesDasTurmas}
          aoSalvar={salvar}
          aoFechar={() => setEditando(null)}
        />
      )}
      <Botao variante="primario" className="w-fit" onClick={() => setEditando("novo")}>
        Novo plano
      </Botao>
      <Estado {...lista} linhas={2}>
        {(planos) =>
          planos.length === 0 ? (
            <Vazio titulo="Nenhum plano ainda">
              Crie um plano, escolha as turmas e o preço, e divulgue o link: quem paga já entra na turma.
            </Vazio>
          ) : (
            <ul className="flex flex-col gap-3">
              {planos.map((plano) => (
                <Cartao key={plano.plano_id} como="li" className="flex flex-col gap-3 p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        {plano.ativo ? <Etiqueta tom="sucesso">À venda</Etiqueta> : <Etiqueta>Fora do ar</Etiqueta>}
                        <Etiqueta tom="info">{plano.tipo === "MENSAL" ? "Assinatura mensal" : "Curso: Pix ou cartão"}</Etiqueta>
                      </div>
                      <p className="text-lg font-semibold text-tinta">{plano.nome}</p>
                      <p className="text-[15px] text-tinta-2">{precoDoPlano(plano)}</p>
                      <p className="text-[13px] text-suave">
                        Dá acesso a: {plano.turmas.join(", ")}
                        {plano.acesso_ate && ` · até ${dataCurta(plano.acesso_ate + "T12:00:00")}`}
                        {plano.a_venda_ate && ` · à venda até ${dia(plano.a_venda_ate)}`}
                        {" · "}
                        {plural(plano.alunos_com_acesso, "aluno com acesso", "alunos com acesso")}
                      </p>
                    </div>
                    <Botao tamanho="pequeno" onClick={() => setEditando(plano)}>
                      Editar
                    </Botao>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 border-t border-borda pt-3">
                    <code className="min-w-0 truncate rounded bg-canvas px-2 py-1 text-[13px] text-tinta-2">
                      /assinar/{plano.link}
                    </code>
                    <Botao tamanho="pequeno" variante="primario" onClick={() => copiar(plano)} disabled={!plano.ativo}>
                      {copiado === plano.plano_id ? "Link copiado" : "Copiar link"}
                    </Botao>
                  </div>
                </Cartao>
              ))}
            </ul>
          )
        }
      </Estado>
    </div>
  );
}

function precoDoPlano(plano: PlanoDeVenda): string {
  if (plano.tipo === "MENSAL")
    return plano.mensalidades
      ? `${plano.mensalidades}x de ${reais(plano.preco_centavos)}, uma por mês · cartão de crédito`
      : `${reais(plano.preco_centavos)} por mês, até o aluno cancelar · cartão de crédito`;
  const hoje = new Date().toLocaleDateString("sv-SE", { timeZone: "America/Sao_Paulo" });
  const vale = plano.precos.filter((l) => !l.a_partir_de || l.a_partir_de <= hoje).at(-1) ?? plano.precos[0];
  const partes = vale ? opcoesDaLinha(vale) : [];
  const matricula = plano.matricula_centavos ? ` · + matrícula de ${reais(plano.matricula_centavos)}` : "";
  const outras = plano.precos.length > 1 ? ` · ${plural(plano.precos.length, "preço", "preços")} na tabela` : "";
  return `Hoje: ${partes.join(" ou ")}${matricula}${outras}`;
}

function opcoesDaLinha(l: PrecoDoPlano): string[] {
  const partes: string[] = [];
  if (l.cartao_centavos)
    partes.push(
      l.cartao_parcelas > 1
        ? `${l.cartao_parcelas}x de ${reais(Math.round(l.cartao_centavos / l.cartao_parcelas))} no cartão`
        : `${reais(l.cartao_centavos)} no cartão`,
    );
  if (l.pix_centavos) partes.push(`${reais(l.pix_centavos)} no Pix`);
  return partes;
}

/** Uma linha da tabela como o formulário edita: textos, e o cartão pelo valor da parcela. */
type LinhaEditavel = { chave: number; aPartirDe: string; pix: string; parcelas: number; parcela: string };

const emReais = (c: number) => (c / 100).toFixed(2).replace(".", ",");

function paraEditar(precos: PrecoDoPlano[]): LinhaEditavel[] {
  if (precos.length === 0) return [{ chave: 1, aPartirDe: "", pix: "", parcelas: 12, parcela: "" }];
  return precos.map((l, i) => ({
    chave: i + 1,
    aPartirDe: l.a_partir_de ?? "",
    pix: l.pix_centavos ? emReais(l.pix_centavos) : "",
    parcelas: l.cartao_parcelas,
    parcela: l.cartao_centavos ? emReais(Math.round(l.cartao_centavos / l.cartao_parcelas)) : "",
  }));
}

/** "Extensivo 2026 — mensal" vira "extensivo-2026-mensal". */
function paraLink(texto: string): string {
  return texto
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
}

/** "197,00" ou "197" viram 19700; texto sem número vira NaN. */
function centavos(texto: string): number {
  const limpo = texto.replace(/[^\d,.]/g, "").replace(/\./g, "").replace(",", ".");
  return limpo ? Math.round(Number(limpo) * 100) : NaN;
}

function FormularioDoPlano({
  plano,
  turmas,
  aoSalvar,
  aoFechar,
}: {
  plano: PlanoDeVenda | null;
  turmas: string[];
  aoSalvar: (dados: DadosDoPlano) => Promise<void>;
  aoFechar: () => void;
}) {
  const saida = useSaida(aoFechar);
  const idDoFormulario = useId();
  const [nome, setNome] = useState(plano?.nome ?? "");
  const [link, setLink] = useState(plano?.link ?? "");
  const [linkMexido, setLinkMexido] = useState(Boolean(plano));
  const [tipo, setTipo] = useState<TipoDePlano>(plano?.tipo ?? "UNICO");
  const [valor, setValor] = useState(plano ? (plano.preco_centavos / 100).toFixed(2).replace(".", ",") : "");
  // 0 é "até o aluno cancelar"; plano novo já nasce com o ano do curso.
  const [mensalidades, setMensalidades] = useState(plano ? (plano.mensalidades ?? 0) : 12);
  const [acessoAte, setAcessoAte] = useState(plano?.acesso_ate ?? "");
  const [vendaAte, setVendaAte] = useState(plano?.a_venda_ate ?? "");
  const [matricula, setMatricula] = useState(plano?.matricula_centavos ? emReais(plano.matricula_centavos) : "");
  const [parcelasDaMatricula, setParcelasDaMatricula] = useState(plano?.matricula_parcelas_max ?? 3);
  const [linhas, setLinhas] = useState<LinhaEditavel[]>(paraEditar(plano?.precos ?? []));
  const [escolhidas, setEscolhidas] = useState<string[]>(plano?.turmas ?? []);
  const [ativo, setAtivo] = useState(plano?.ativo ?? true);
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    let especifico: DadosDoPlano;
    if (tipo === "MENSAL") {
      const preco = centavos(valor);
      if (Number.isNaN(preco)) {
        setErro("Informe o preço por mês, ex.: 197,00.");
        return;
      }
      especifico = { preco_centavos: preco, parcelas_max: 1, mensalidades };
    } else {
      const precos: PrecoDoPlano[] = [];
      for (const [i, l] of linhas.entries()) {
        const pix = l.pix.trim() ? centavos(l.pix) : null;
        const parcela = l.parcela.trim() ? centavos(l.parcela) : null;
        if (Number.isNaN(pix) || Number.isNaN(parcela)) {
          setErro(`Linha ${i + 1} da tabela: use valores como 1.700,00 ou 159,90.`);
          return;
        }
        if (i > 0 && !l.aPartirDe) {
          setErro(`Linha ${i + 1} da tabela: diga a partir de quando ela vale.`);
          return;
        }
        precos.push({
          a_partir_de: i === 0 ? null : l.aPartirDe,
          pix_centavos: pix,
          cartao_centavos: parcela === null ? null : parcela * l.parcelas,
          cartao_parcelas: l.parcelas,
        });
      }
      const taxa = matricula.trim() ? centavos(matricula) : 0;
      if (Number.isNaN(taxa)) {
        setErro("Informe a matrícula como 330,00, ou deixe vazio para não cobrar.");
        return;
      }
      especifico = {
        precos,
        matricula_centavos: taxa,
        matricula_parcelas_max: parcelasDaMatricula,
        acesso_ate: acessoAte || null,
      };
    }
    setSalvando(true);
    try {
      await aoSalvar({
        nome: nome.trim(),
        link,
        tipo,
        ...especifico,
        a_venda_ate: vendaAte,
        turmas: escolhidas,
        ativo,
      });
      saida.fechar();
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setSalvando(false);
    }
  }

  return (
    <OffCanvas
      {...saida}
      aoFechar={() => !salvando && saida.fechar()}
      fechaClicandoFora={false}
      lado="direita"
      tamanho="grande"
      titulo={plano ? "Editar plano" : "Novo plano"}
      legenda="O plano é o que se vende: um preço, as turmas que ele libera e o link que você divulga."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={salvando}>Cancelar</Botao>
          <Botao type="submit" form={idDoFormulario} variante="primario" ocupado={salvando}>
            {salvando ? "Salvando…" : plano ? "Salvar" : "Criar plano"}
          </Botao>
        </div>
      }
    >
      <form id={idDoFormulario} className="flex flex-col gap-4" onSubmit={enviar}>
        <div className="grid gap-3 sm:grid-cols-2">
          <Campo rotulo="Nome" dica="É o que o aluno vê na página de assinar.">
            {(id) => (
              <input
                id={id}
                className="campo"
                value={nome}
                maxLength={120}
                required
                placeholder="Extensivo 2026 — mensal"
                onChange={(e) => {
                  setNome(e.target.value);
                  if (!linkMexido) setLink(paraLink(e.target.value));
                }}
              />
            )}
          </Campo>
          <Campo rotulo="Link" dica={`O endereço fica …/assinar/${link || "seu-link"}`}>
            {(id) => (
              <input
                id={id}
                className="campo"
                value={link}
                maxLength={60}
                required
                placeholder="extensivo-2026"
                onChange={(e) => {
                  setLinkMexido(true);
                  setLink(paraLink(e.target.value));
                }}
              />
            )}
          </Campo>
        </div>

        <fieldset>
          <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Turmas que o plano abre</legend>
          <div className="flex flex-wrap gap-2">
            {turmas.map((nomeDaTurma) => {
              const marcada = escolhidas.includes(nomeDaTurma);
              return (
                <label
                  key={nomeDaTurma}
                  className={`flex cursor-pointer items-center gap-2 rounded-full border px-3.5 py-1.5 text-[15px] has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
                    marcada ? "border-acento bg-lilas text-acento-forte" : "border-borda bg-papel"
                  }`}
                >
                  <input
                    type="checkbox"
                    className="sr-only"
                    checked={marcada}
                    onChange={() =>
                      setEscolhidas(marcada ? escolhidas.filter((t) => t !== nomeDaTurma) : [...escolhidas, nomeDaTurma])
                    }
                  />
                  {nomeDaTurma}
                </label>
              );
            })}
          </div>
        </fieldset>

        <fieldset>
          <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Como o aluno paga</legend>
          <div className="flex flex-col gap-2 sm:flex-row">
            <OpcaoDeTipo
              marcada={tipo === "UNICO"}
              aoMarcar={() => setTipo("UNICO")}
              titulo="Curso: Pix ou cartão"
              texto="Pix à vista ou parcelado no cartão, cada um com seu preço, mais a matrícula."
            />
            <OpcaoDeTipo
              marcada={tipo === "MENSAL"}
              aoMarcar={() => setTipo("MENSAL")}
              titulo="Assinatura mensal"
              texto="Cobra uma vez por mês no cartão de crédito."
            />
          </div>
        </fieldset>

        {tipo === "MENSAL" ? (
          <div className="grid gap-3 sm:grid-cols-3">
            <Campo rotulo="Preço por mês">
              {(id) => (
                <input
                  id={id}
                  className="campo"
                  inputMode="decimal"
                  value={valor}
                  required
                  placeholder="197,00"
                  onChange={(e) => setValor(e.target.value)}
                />
              )}
            </Campo>
            <Campo rotulo="Quantas mensalidades" dica="Na última, a cobrança para sozinha.">
              {(id) => (
                <select
                  id={id}
                  className="campo"
                  value={mensalidades}
                  onChange={(e) => setMensalidades(Number(e.target.value))}
                >
                  {Array.from({ length: 23 }, (_, i) => i + 2).map((n) => (
                    <option key={n} value={n}>
                      {n} mensalidades
                    </option>
                  ))}
                  <option value={0}>Sem fim (até o aluno cancelar)</option>
                </select>
              )}
            </Campo>
          </div>
        ) : (
          <>
            <TabelaDePrecos linhas={linhas} aoMudar={setLinhas} />
            <div className="grid gap-3 sm:grid-cols-3">
              <Campo rotulo="Matrícula" dica="Cobrada no início, à parte. Vazio: sem matrícula.">
                {(id) => (
                  <input
                    id={id}
                    className="campo"
                    inputMode="decimal"
                    value={matricula}
                    placeholder="330,00"
                    onChange={(e) => setMatricula(e.target.value)}
                  />
                )}
              </Campo>
              <Campo rotulo="Matrícula no cartão">
                {(id) => (
                  <select
                    id={id}
                    className="campo"
                    value={parcelasDaMatricula}
                    onChange={(e) => setParcelasDaMatricula(Number(e.target.value))}
                  >
                    {Array.from({ length: 12 }, (_, i) => i + 1).map((n) => (
                      <option key={n} value={n}>
                        {n === 1 ? "Só à vista" : `Até ${n}x`}
                      </option>
                    ))}
                  </select>
                )}
              </Campo>
              <Campo rotulo="Acesso até" dica="Vazio: sem prazo.">
                {(id) => (
                  <input id={id} type="date" className="campo" value={acessoAte} onChange={(e) => setAcessoAte(e.target.value)} />
                )}
              </Campo>
            </div>
          </>
        )}

        <Campo rotulo="À venda até" dica="Último dia de matrícula; depois o link mostra “matrículas encerradas”. Vazio: sem prazo.">
          {(id) => (
            <input
              id={id}
              type="date"
              className="campo sm:w-56"
              value={vendaAte}
              onChange={(e) => setVendaAte(e.target.value)}
            />
          )}
        </Campo>

        {plano && (
          <label className="flex w-fit cursor-pointer items-center gap-2 text-[15px] text-tinta">
            <input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />
            À venda (desmarcado, o link sai do ar; quem já comprou continua com acesso)
          </label>
        )}

        {erro && <Aviso tom="erro">{erro}</Aviso>}
      </form>
    </OffCanvas>
  );
}

/**
 * O preço que muda com a data: a primeira linha vale desde já; cada outra, a partir do dia dela.
 * O cartão entra como "N x de R$ parcela", que é como a página de vendas anuncia.
 */
function TabelaDePrecos({ linhas, aoMudar }: { linhas: LinhaEditavel[]; aoMudar: (l: LinhaEditavel[]) => void }) {
  const mudar = (chave: number, campo: Partial<LinhaEditavel>) =>
    aoMudar(linhas.map((l) => (l.chave === chave ? { ...l, ...campo } : l)));
  const proxima = Math.max(0, ...linhas.map((l) => l.chave)) + 1;
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1.5 text-sm font-semibold text-tinta-2">Preço do curso</legend>
      <p className="text-[13px] text-suave">
        Cada linha vale a partir da data dela até a próxima. Deixe o Pix ou o cartão vazio para não oferecer essa forma.
      </p>
      {linhas.map((l, i) => (
        <div key={l.chave} className="grid items-end gap-2 rounded-lg border border-borda p-3 sm:grid-cols-[9.5rem_1fr_auto_1fr_auto]">
          <Campo rotulo={i === 0 ? "Vale" : "A partir de"}>
            {(id) =>
              i === 0 ? (
                <input id={id} className="campo" value="Desde já" disabled />
              ) : (
                <input
                  id={id}
                  type="date"
                  className="campo"
                  value={l.aPartirDe}
                  required
                  onChange={(e) => mudar(l.chave, { aPartirDe: e.target.value })}
                />
              )
            }
          </Campo>
          <Campo rotulo="Pix à vista">
            {(id) => (
              <input
                id={id}
                className="campo"
                inputMode="decimal"
                value={l.pix}
                placeholder="1.700,00"
                onChange={(e) => mudar(l.chave, { pix: e.target.value })}
              />
            )}
          </Campo>
          <Campo rotulo="Cartão">
            {(id) => (
              <select
                id={id}
                className="campo"
                value={l.parcelas}
                onChange={(e) => mudar(l.chave, { parcelas: Number(e.target.value) })}
              >
                {Array.from({ length: 12 }, (_, n) => n + 1).map((n) => (
                  <option key={n} value={n}>
                    {n}x de
                  </option>
                ))}
              </select>
            )}
          </Campo>
          <Campo rotulo="Parcela">
            {(id) => (
              <input
                id={id}
                className="campo"
                inputMode="decimal"
                value={l.parcela}
                placeholder="159,90"
                onChange={(e) => mudar(l.chave, { parcela: e.target.value })}
              />
            )}
          </Campo>
          {i === 0 ? (
            <span className="hidden w-[4.5rem] sm:block" />
          ) : (
            <Botao tamanho="pequeno" onClick={() => aoMudar(linhas.filter((x) => x.chave !== l.chave))}>
              Tirar
            </Botao>
          )}
        </div>
      ))}
      <Botao
        tamanho="pequeno"
        className="w-fit"
        onClick={() => aoMudar([...linhas, { chave: proxima, aPartirDe: "", pix: "", parcelas: linhas.at(-1)?.parcelas ?? 12, parcela: "" }])}
      >
        Adicionar preço a partir de uma data
      </Botao>
    </fieldset>
  );
}

function OpcaoDeTipo({
  marcada,
  aoMarcar,
  titulo,
  texto,
}: {
  marcada: boolean;
  aoMarcar: () => void;
  titulo: string;
  texto: string;
}) {
  return (
    <label
      className={`flex flex-1 cursor-pointer items-start gap-3 rounded-lg border p-3 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-acento ${
        marcada ? "border-acento bg-lilas" : "border-borda bg-papel"
      }`}
    >
      <input type="radio" name="tipo" className="mt-1" checked={marcada} onChange={aoMarcar} />
      <span>
        <span className="block text-[15px] font-semibold text-tinta">{titulo}</span>
        <span className="block text-[13px] text-suave">{texto}</span>
      </span>
    </label>
  );
}

// --- pedidos -----------------------------------------------------------------

const SITUACAO: Record<StatusDoPedido, { rotulo: string; tom: "sucesso" | "atencao" | "erro" | "neutro" | "info" }> = {
  AGUARDANDO: { rotulo: "Aguardando pagamento", tom: "neutro" },
  PAGO: { rotulo: "Pago", tom: "sucesso" },
  ATRASADO: { rotulo: "Atrasado", tom: "atencao" },
  CANCELADO: { rotulo: "Cancelado", tom: "neutro" },
  REEMBOLSADO: { rotulo: "Reembolsado", tom: "erro" },
  EXPIRADO: { rotulo: "Não concluído", tom: "neutro" },
};

function Pedidos() {
  const lista = useDados(() => api.pedidosDeVenda());
  return (
    <Estado {...lista} linhas={4}>
      {(pedidos) =>
        pedidos.length === 0 ? (
          <Vazio titulo="Nenhum pedido ainda">Quando alguém abrir o link de um plano e for pagar, o pedido aparece aqui.</Vazio>
        ) : (
          <ul className="flex flex-col gap-2">
            {pedidos.map((p) => (
              <LinhaDoPedido key={p.pedido_id} pedido={p} />
            ))}
          </ul>
        )
      }
    </Estado>
  );
}

function LinhaDoPedido({ pedido: p }: { pedido: PedidoDeVenda }) {
  const situacao = SITUACAO[p.status];
  return (
    <Cartao como="li" className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
      <div className="min-w-0">
        <p className="truncate font-semibold text-tinta">{p.nome}</p>
        <p className="truncate text-[13px] text-suave">
          {p.email} · {p.plano}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3 text-[13px] text-suave">
        <span className="tabular-nums">{dataCurta(p.criado_em)}</span>
        <span className="font-semibold tabular-nums text-tinta">{reais(p.valor_centavos)}</span>
        {p.forma === "PIX" && <span>Pix</span>}
        {p.forma === "CARTAO" && p.cartao_parcelas && <span>cartão em até {p.cartao_parcelas}x</span>}
        {p.status === "AGUARDANDO" && p.matricula_paga_em && <Etiqueta tom="info">Matrícula paga, falta o curso</Etiqueta>}
        <Etiqueta tom={situacao.tom}>{situacao.rotulo}</Etiqueta>
        {p.acesso_liberado && p.acesso_ate && (
          <span>acesso até {dataCurta(p.acesso_ate)}</span>
        )}
      </div>
    </Cartao>
  );
}

// --- envios ------------------------------------------------------------------

/**
 * Quem pagou e precisa receber o material em casa. Dois envios por aluno (na página de vendas: a
 * apostila logo após a matrícula; camisa e material de revisão em agosto). Marcar só registra.
 */
function Envios() {
  const lista = useDados(() => api.pedidosDeVenda());
  const [salvando, setSalvando] = useState("");
  const [erro, setErro] = useState("");

  async function marcar(p: PedidoDeVenda, envio: 1 | 2, enviado: boolean) {
    setErro("");
    setSalvando(`${p.pedido_id}-${envio}`);
    try {
      await api.marcarEnvio(p.pedido_id, envio, enviado);
      await lista.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setSalvando("");
    }
  }

  return (
    <Estado {...lista} linhas={4}>
      {(pedidos) => {
        const comEndereco = pedidos.filter((p) => p.acesso_liberado && p.endereco);
        const faltam = (n: 1 | 2) => comEndereco.filter((p) => !(n === 1 ? p.envio1_em : p.envio2_em)).length;
        return comEndereco.length === 0 ? (
          <Vazio titulo="Nenhum material para enviar">Quem pagar o curso aparece aqui, com o endereço de entrega.</Vazio>
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-[15px] text-tinta-2">
              Faltam {plural(faltam(1), "1º envio", "1º envios")} (apostila) e {plural(faltam(2), "2º envio", "2º envios")}{" "}
              (camisa e revisão).
            </p>
            {erro && <Aviso tom="erro">{erro}</Aviso>}
            <ul className="flex flex-col gap-2">
              {comEndereco.map((p) => (
                <Cartao key={p.pedido_id} como="li" className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="min-w-0">
                    <p className="font-semibold text-tinta">{p.nome}</p>
                    <p className="text-[13px] text-tinta-2">{p.endereco}</p>
                    <p className="text-[13px] text-suave">
                      {p.plano}
                      {p.celular && ` · ${p.celular}`}
                    </p>
                  </div>
                  <div className="flex flex-col gap-1.5 text-[15px]">
                    {([1, 2] as const).map((n) => {
                      const em = n === 1 ? p.envio1_em : p.envio2_em;
                      return (
                        <label key={n} className="flex cursor-pointer items-center gap-2">
                          <input
                            type="checkbox"
                            checked={Boolean(em)}
                            disabled={salvando === `${p.pedido_id}-${n}`}
                            onChange={(e) => marcar(p, n, e.target.checked)}
                          />
                          {n === 1 ? "1º envio (apostila)" : "2º envio (camisa e revisão)"}
                          {em && <span className="text-[13px] text-suave">em {dataCurta(em)}</span>}
                        </label>
                      );
                    })}
                  </div>
                </Cartao>
              ))}
            </ul>
          </div>
        );
      }}
    </Estado>
  );
}
