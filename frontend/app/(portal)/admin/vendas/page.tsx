"use client";

import { useState, type FormEvent } from "react";
import { Abas, Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, TituloDeSecao, Vazio } from "@/components/ui";
import {
  api,
  useDados,
  type DadosDoPlano,
  type PedidoDeVenda,
  type PlanoDeVenda,
  type StatusDoPedido,
  type TipoDePlano,
} from "@/lib/api";
import { dataCurta, plural, reais } from "@/lib/formato";

type Aba = "planos" | "pedidos";

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
        ]}
        atual={aba}
        aoTrocar={setAba}
      />
      {aba === "planos" ? <Planos /> : <Pedidos />}
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
    setEditando(null);
    await lista.recarregar();
  }

  return (
    <div className="flex flex-col gap-4">
      {editando ? (
        <FormularioDoPlano
          plano={editando === "novo" ? null : editando}
          turmas={nomesDasTurmas}
          aoSalvar={salvar}
          aoCancelar={() => setEditando(null)}
        />
      ) : (
        <Botao variante="primario" className="w-fit" onClick={() => setEditando("novo")}>
          Novo plano
        </Botao>
      )}
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
                        <Etiqueta tom="info">{plano.tipo === "MENSAL" ? "Assinatura mensal" : "Pagamento único"}</Etiqueta>
                      </div>
                      <p className="text-lg font-semibold text-tinta">{plano.nome}</p>
                      <p className="text-[15px] text-tinta-2">{preco(plano.tipo, plano.preco_centavos, plano.parcelas_max)}</p>
                      <p className="text-[13px] text-suave">
                        Dá acesso a: {plano.turmas.join(", ")}
                        {plano.acesso_ate && ` · até ${dataCurta(plano.acesso_ate + "T12:00:00")}`}
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
                    <Botao tamanho="pequeno" variante="primario" onClick={() => void copiar(plano)} disabled={!plano.ativo}>
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

function preco(tipo: TipoDePlano, centavos: number, parcelas: number): string {
  if (tipo === "MENSAL") return `${reais(centavos)} por mês · cartão de crédito`;
  return parcelas > 1
    ? `${reais(centavos)} à vista no Pix ou em até ${parcelas}x no cartão`
    : `${reais(centavos)} à vista no Pix ou no cartão`;
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
  aoCancelar,
}: {
  plano: PlanoDeVenda | null;
  turmas: string[];
  aoSalvar: (dados: DadosDoPlano) => Promise<void>;
  aoCancelar: () => void;
}) {
  const [nome, setNome] = useState(plano?.nome ?? "");
  const [link, setLink] = useState(plano?.link ?? "");
  const [linkMexido, setLinkMexido] = useState(Boolean(plano));
  const [tipo, setTipo] = useState<TipoDePlano>(plano?.tipo ?? "MENSAL");
  const [valor, setValor] = useState(plano ? (plano.preco_centavos / 100).toFixed(2).replace(".", ",") : "");
  const [parcelas, setParcelas] = useState(plano?.parcelas_max ?? 12);
  const [acessoAte, setAcessoAte] = useState(plano?.acesso_ate ?? "");
  const [escolhidas, setEscolhidas] = useState<string[]>(plano?.turmas ?? []);
  const [ativo, setAtivo] = useState(plano?.ativo ?? true);
  const [erro, setErro] = useState("");
  const [salvando, setSalvando] = useState(false);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro("");
    const preco = centavos(valor);
    if (Number.isNaN(preco)) {
      setErro("Informe o preço, ex.: 197,00.");
      return;
    }
    setSalvando(true);
    try {
      await aoSalvar({
        nome: nome.trim(),
        link,
        tipo,
        preco_centavos: preco,
        parcelas_max: tipo === "UNICO" ? parcelas : 1,
        acesso_ate: tipo === "UNICO" && acessoAte ? acessoAte : null,
        turmas: escolhidas,
        ativo,
      });
    } catch (ex) {
      setErro((ex as Error).message);
      setSalvando(false);
    }
  }

  return (
    <Cartao className="p-5">
      <TituloDeSecao>{plano ? "Editar plano" : "Novo plano"}</TituloDeSecao>
      <form className="mt-3 flex flex-col gap-4" onSubmit={enviar}>
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
              marcada={tipo === "MENSAL"}
              aoMarcar={() => setTipo("MENSAL")}
              titulo="Assinatura mensal"
              texto="Cobra todo mês no cartão de crédito."
            />
            <OpcaoDeTipo
              marcada={tipo === "UNICO"}
              aoMarcar={() => setTipo("UNICO")}
              titulo="Pagamento único"
              texto="À vista no Pix ou parcelado no cartão."
            />
          </div>
        </fieldset>

        <div className="grid gap-3 sm:grid-cols-3">
          <Campo rotulo={tipo === "MENSAL" ? "Preço por mês" : "Preço total"}>
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
          {tipo === "UNICO" && (
            <>
              <Campo rotulo="Parcelas no cartão">
                {(id) => (
                  <select id={id} className="campo" value={parcelas} onChange={(e) => setParcelas(Number(e.target.value))}>
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
            </>
          )}
        </div>

        {plano && (
          <label className="flex w-fit cursor-pointer items-center gap-2 text-[15px] text-tinta">
            <input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />
            À venda (desmarcado, o link sai do ar; quem já comprou continua com acesso)
          </label>
        )}

        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <div className="flex flex-wrap gap-2">
          <Botao type="submit" variante="primario" disabled={salvando}>
            {salvando ? "Salvando…" : plano ? "Salvar" : "Criar plano"}
          </Botao>
          <Botao type="button" onClick={aoCancelar} disabled={salvando}>
            Cancelar
          </Botao>
        </div>
      </form>
    </Cartao>
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
        <Etiqueta tom={situacao.tom}>{situacao.rotulo}</Etiqueta>
        {p.acesso_liberado && p.acesso_ate && (
          <span>acesso até {dataCurta(p.acesso_ate)}</span>
        )}
      </div>
    </Cartao>
  );
}
