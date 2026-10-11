"use client";

import { Check, Copy } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Formulario, Pagina, Revelar, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type TokenMcp } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";

const NOME_NO_CLAUDE = "portal-aluno";

/**
 * O endereço do MCP, pronto para colar. A API informa a base do adaptador (outro domínio, em
 * produção); o MCP mora em /mcp dela. Um caminho relativo vale para o próprio portal.
 */
function enderecoDoMcp(base: string | null, origem: string) {
  if (!base) return "";
  const limpa = base.replace(/\/+$/, "");
  const absoluta = /^https?:\/\//.test(limpa) ? limpa : `${origem}${limpa}`;
  return absoluta.endsWith("/mcp") ? absoluta : `${absoluta}/mcp`;
}

export default function ConectarAoClaude() {
  const saude = useDados(() => api.saude());
  const tokens = useDados(() => api.tokens());
  const [origem, setOrigem] = useState("");
  const [novo, setNovo] = useState<(TokenMcp & { token: string }) | null>(null);
  const [erro, setErro] = useState("");
  const [noSite, setNoSite] = useState(false);
  const [dialogo, confirmar] = useConfirmar();

  useEffect(() => setOrigem(window.location.origin), []);

  async function revogar(t: TokenMcp) {
    const sim = await confirmar({
      titulo: `Revogar "${t.nome}"?`,
      texto: "Quem usa este token perde o acesso na hora. Não dá para desfazer; se precisar, emita outro.",
      confirmar: "Revogar token",
      perigo: true,
    });
    if (!sim) return;
    setErro("");
    try {
      await api.revogarToken(t.id);
      if (novo?.id === t.id) setNovo(null);
      void tokens.recarregar();
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <Pagina titulo="Conectar ao Claude" legenda="O Claude opera o portal com as suas permissões: consulta, propõe rascunhos e edita. Publicar continua sendo aprovação sua." estreita>
      {dialogo}
      <Estado {...saude} linhas={3}>
        {(s) => {
          const url = enderecoDoMcp(s.mcp, origem);
          if (!url) return <Aviso tom="atencao" titulo="O Claude ainda não conecta neste servidor">Falta configurar o endereço do adaptador (MCP_BASE_URL).</Aviso>;
          const comando = `claude mcp add --transport http ${NOME_NO_CLAUDE} ${url} --header "Authorization: Bearer ${novo ? novo.token : "SEU_TOKEN"}"`;
          return (
            <>
              <Cartao className="flex flex-col gap-5 p-5">
                <TituloDeSecao>No Claude Code</TituloDeSecao>

                <Passo numero={1} titulo="Emita um token">
                  <EmitirToken aoEmitir={(t) => { setNovo(t); void tokens.recarregar(); }} />
                  {novo && (
                    <Aviso tom="sucesso" titulo={`Token "${novo.nome}" emitido`}>
                      Ele aparece só agora: o servidor guarda apenas um resumo dele. O comando abaixo já vem com ele.
                    </Aviso>
                  )}
                </Passo>

                <Passo numero={2} titulo="Rode este comando no terminal">
                  <Bloco texto={comando} rotulo="Copiar comando" />
                  {!novo && <p className="text-[13px] text-suave">Emita o token no passo 1: o comando passa a vir com ele no lugar de SEU_TOKEN.</p>}
                </Passo>

                <Passo numero={3} titulo="Confira">
                  <p className="text-[15px]">
                    Abra o Claude Code e digite <Codigo>/mcp</Codigo>. O <Codigo>{NOME_NO_CLAUDE}</Codigo> deve aparecer como conectado.
                  </p>
                  <p className="text-[13px] text-suave">
                    Depois de uma atualização do portal, ferramenta nova só aparece em conversa nova. Se não aparecer, remova com <Codigo>claude mcp remove {NOME_NO_CLAUDE}</Codigo> e rode o comando de novo.
                  </p>
                </Passo>
              </Cartao>

              <Cartao className="flex flex-col gap-3 p-5">
                <div>
                  <Revelar aberto={noSite} aoAlternar={() => setNoSite((a) => !a)} aria-controls="no-site">
                    Prefere usar pelo site claude.ai?
                  </Revelar>
                </div>
                {noSite && (
                  <ol id="no-site" className="list-decimal space-y-2 pl-5 text-[15px]">
                    <li>Em Configurações › Conectores, adicione um conector personalizado.</li>
                    <li>
                      Cole este endereço:
                      <Bloco texto={url} rotulo="Copiar endereço" />
                    </li>
                    <li>Entre com o GitHub. O e-mail precisa ser o de um professor ou gerenciador cadastrado aqui. Não precisa de token.</li>
                    <li className="text-suave">Depois de uma atualização do portal, ferramenta nova só aparece se você remover o conector e adicionar de novo.</li>
                  </ol>
                )}
              </Cartao>
            </>
          );
        }}
      </Estado>

      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Tokens emitidos</TituloDeSecao>
        {erro && <Aviso tom="erro">{erro}</Aviso>}
        <Estado {...tokens} linhas={2}>
          {(lista) =>
            lista.length === 0 ? (
              <p className="text-[15px] text-suave">Nenhum token emitido.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="tabela min-w-[34rem]">
                  <thead>
                    <tr>
                      <th scope="col">Nome</th>
                      <th scope="col">Criado</th>
                      <th scope="col">Último uso</th>
                      <th scope="col"><span className="sr-only">Ações</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {lista.map((t) => (
                      <tr key={t.id}>
                        <td className="font-medium">{t.nome}</td>
                        <td className="whitespace-nowrap tabular-nums">{emBrasilia(t.criado_em)}</td>
                        <td className="whitespace-nowrap tabular-nums">{t.ultimo_uso_em ? emBrasilia(t.ultimo_uso_em) : "nunca"}</td>
                        <td className="text-right">
                          {t.revogado ? <Etiqueta>Revogado</Etiqueta> : <Botao tamanho="mini" variante="perigo" onClick={() => revogar(t)}>Revogar</Botao>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </Estado>
      </Cartao>
    </Pagina>
  );
}

function Passo({ numero, titulo, children }: { numero: number; titulo: string; children: React.ReactNode }) {
  return (
    <section className="flex gap-3">
      <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center rounded-full bg-lilas text-sm font-bold text-acento-forte">{numero}</span>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <h3 className="pt-0.5 font-semibold text-tinta">
          <span className="sr-only">Passo {numero}: </span>
          {titulo}
        </h3>
        {children}
      </div>
    </section>
  );
}

function Codigo({ children }: { children: React.ReactNode }) {
  return <code className="rounded-campo bg-canvas px-1.5 py-0.5 font-mono text-[13px]">{children}</code>;
}

/** Põe o texto na área de transferência; onde o navegador não deixa, cai no jeito antigo. */
async function copiar(texto: string) {
  try {
    await navigator.clipboard.writeText(texto);
  } catch {
    const area = document.createElement("textarea");
    area.value = texto;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

/** Um texto para copiar inteiro: quebra a linha em vez de rolar para o lado, e o botão copia tudo. */
function Bloco({ texto, rotulo }: { texto: string; rotulo: string }) {
  const [copiado, setCopiado] = useState(false);
  const volta = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(volta.current), []);
  // Texto novo (o token entrou no comando): o "Copiado" de antes já não vale.
  useEffect(() => setCopiado(false), [texto]);

  return (
    <div className="flex flex-col gap-2">
      <code className="block select-all break-all rounded-cartao bg-tinta px-4 py-3 font-mono text-[13px] leading-relaxed text-white">{texto}</code>
      <div>
        <Botao
          tamanho="pequeno"
          variante="secundario"
          onClick={async () => {
            await copiar(texto);
            setCopiado(true);
            clearTimeout(volta.current);
            volta.current = setTimeout(() => setCopiado(false), 2500);
          }}
        >
          {copiado ? <Check aria-hidden="true" className="size-4" /> : <Copy aria-hidden="true" className="size-4" />}
          {copiado ? "Copiado" : rotulo}
        </Botao>
      </div>
    </div>
  );
}

function EmitirToken({ aoEmitir }: { aoEmitir: (t: TokenMcp & { token: string }) => void }) {
  const [nome, setNome] = useState("Claude Code");
  const [erro, setErro] = useState("");

  async function emitir() {
    setErro("");
    try {
      aoEmitir(await api.emitirToken(nome.trim()));
    } catch (ex) {
      setErro((ex as Error).message);
    }
  }

  return (
    <Formulario onSubmit={emitir} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Nome do token" dica="Para lembrar onde ele está em uso, ex.: o seu computador." className="min-w-56 flex-1">
          {(id) => <input id={id} required maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
        </Campo>
        <Botao type="submit" variante="primario" className="mb-6">Emitir token</Botao>
      </div>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
    </Formulario>
  );
}
