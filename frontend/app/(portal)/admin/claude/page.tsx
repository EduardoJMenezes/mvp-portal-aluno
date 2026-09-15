"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Aviso, Botao, Campo, Cartao, Estado, Etiqueta, Pagina, SegredoUmaVez, TituloDeSecao, useConfirmar } from "@/components/ui";
import { api, useDados, type TokenMcp } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";

export default function ConectarAoClaude() {
  const saude = useDados(() => api.saude());
  const tokens = useDados(() => api.tokens());
  const [origem, setOrigem] = useState("");
  const [novo, setNovo] = useState<(TokenMcp & { token: string }) | null>(null);
  const [erro, setErro] = useState("");
  const [dialogo, confirmar] = useConfirmar();

  useEffect(() => setOrigem(window.location.origin), []);
  const url = saude.dados ? `${origem}${saude.dados.mcp}` : "";

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
      <Estado {...saude} linhas={2}>
        {(s) => (
          <>
            <Cartao className="flex flex-col gap-3 p-5">
              <TituloDeSecao>No claude.ai</TituloDeSecao>
              {s.mcp_oauth === "github" ? (
                <ol className="list-decimal space-y-1.5 pl-5 text-[15px]">
                  <li>Em Configurações › Conectores, adicione um conector personalizado.</li>
                  <li>
                    Cole o endereço: <Endereco valor={url} />
                  </li>
                  <li>Entre com o GitHub. O e-mail precisa ser o de um professor ou gerenciador cadastrado aqui.</li>
                </ol>
              ) : (
                <Aviso tom="atencao">O login pelo GitHub não está configurado neste servidor, então o claude.ai não conecta. Use o Claude Code com um token, abaixo.</Aviso>
              )}
              <p className="text-[13px] text-suave">Depois de uma atualização do portal, ferramenta nova só aparece se você remover o conector e adicionar de novo.</p>
            </Cartao>

            <Cartao className="flex flex-col gap-3 p-5">
              <TituloDeSecao>No Claude Code</TituloDeSecao>
              <p className="text-[15px]">Emita um token e rode no terminal:</p>
              <pre className="overflow-x-auto rounded-cartao bg-tinta px-4 py-3 font-mono text-[13px] text-white">
                claude mcp add --transport http portal-aluno {url} --header &quot;Authorization: Bearer {novo ? novo.token : "SEU_TOKEN"}&quot;
              </pre>
            </Cartao>
          </>
        )}
      </Estado>

      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Seus tokens</TituloDeSecao>
        <EmitirToken aoEmitir={(t) => { setNovo(t); void tokens.recarregar(); }} />
        {novo && (
          <SegredoUmaVez titulo={`Token "${novo.nome}"`} valor={novo.token}>
            Copie agora: ele não aparece de novo. O servidor guarda só um resumo (hash) dele. O comando acima já está com ele.
          </SegredoUmaVez>
        )}
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
                          {t.revogado ? <Etiqueta>Revogado</Etiqueta> : <button type="button" onClick={() => void revogar(t)} className="text-sm font-semibold text-erro hover:underline">Revogar</button>}
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

function Endereco({ valor }: { valor: string }) {
  const [copiado, setCopiado] = useState(false);
  return (
    <span className="mt-1 flex flex-wrap items-center gap-2">
      <code className="select-all break-all rounded-campo border border-borda bg-canvas px-2 py-1 font-mono text-[14px]">{valor}</code>
      <Botao tamanho="pequeno" onClick={async () => { await navigator.clipboard?.writeText(valor); setCopiado(true); }}>{copiado ? "Copiado" : "Copiar"}</Botao>
    </span>
  );
}

function EmitirToken({ aoEmitir }: { aoEmitir: (t: TokenMcp & { token: string }) => void }) {
  const [nome, setNome] = useState("Claude Code");
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  async function emitir(e: FormEvent) {
    e.preventDefault();
    setErro("");
    setOcupado(true);
    try {
      aoEmitir(await api.emitirToken(nome.trim()));
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  return (
    <form onSubmit={emitir} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end gap-2">
        <Campo rotulo="Nome do token" dica="Para lembrar onde ele está em uso." className="min-w-56 flex-1">
          {(id) => <input id={id} maxLength={120} value={nome} onChange={(e) => setNome(e.target.value)} className="campo" />}
        </Campo>
        <Botao type="submit" variante="primario" disabled={ocupado} className="mb-6">{ocupado ? "Emitindo…" : "Emitir token"}</Botao>
      </div>
      {erro && <Aviso tom="erro">{erro}</Aviso>}
    </form>
  );
}
