"use client";

import { useEffect, useState } from "react";
import { EscolherDocx, EscolherPrints } from "@/components/Envio";
import { Aviso, Carregando } from "@/components/ui";
import { api } from "@/lib/api";

// A página do link de envio: o .docx do simulado ou os prints das questões.
// Não pede login: o link é a credencial — uso único, com prazo, preso a quem o
// pediu no chat. O backend entrega esta página em /enviar/<token>.

type Pedido = Awaited<ReturnType<typeof api.envio>>;
type Recibo = Awaited<ReturnType<typeof api.enviarDocx>>;

function tokenDoEndereco(): string {
  const doCaminho = window.location.pathname.replace(/\/+$/, "").split("/enviar/")[1];
  return doCaminho || new URLSearchParams(window.location.search).get("token") || "";
}

export default function PaginaDeEnvio() {
  const [token, setToken] = useState("");
  const [pedido, setPedido] = useState<Pedido | null>(null);
  const [recibo, setRecibo] = useState<Recibo | null>(null);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    const t = tokenDoEndereco();
    setToken(t);
    if (!t) {
      setErro("Este endereço não tem o código do link. Abra o link exatamente como chegou no chat.");
      return;
    }
    api.envio(t).then(setPedido).catch((e: Error) => setErro(e.message));
  }, []);

  async function enviar(acao: () => Promise<Recibo>) {
    setEnviando(true);
    setErro("");
    try {
      setRecibo(await acao());
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  const prints = pedido?.formato === "prints";

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-2xl flex-col justify-center px-4 py-10">
      <div className="rounded-cartao border border-borda bg-papel p-6 shadow-suave sm:p-8">
        <h1 className="text-2xl font-semibold text-tinta">{prints ? "Enviar prints das questões" : "Enviar simulado"}</h1>
        {pedido && (
          <p className="mt-1 text-[15px] text-suave">
            {pedido.titulo ? `${pedido.titulo} · ` : ""}
            {pedido.turmas.length ? `${pedido.turmas.join(", ")} · ` : ""}pedido por {pedido.pedido_por} no chat · vale até {pedido.expira_em}
          </p>
        )}

        <div className="mt-6 flex flex-col gap-4">
          {erro && <Aviso tom="erro">{erro}</Aviso>}
          {!pedido && !erro && <Carregando linhas={1} />}

          {recibo ? (
            <Aviso tom="sucesso" titulo={prints ? `${recibo.prints} print(s) recebido(s)` : `${recibo.titulo}: recebido`}>
              {!prints && (
                <p>
                  {recibo.questoes_completas} de {recibo.questoes_lidas} questões lidas por completo, {recibo.figuras} figura(s).
                </p>
              )}
              <p className="mt-1">{recibo.mensagem}</p>
            </Aviso>
          ) : pedido?.situacao === "AGUARDANDO" ? (
            prints ? (
              <EscolherPrints enviando={enviando} aoEnviar={(arquivos) => void enviar(() => api.enviarPrints(token, arquivos))} />
            ) : (
              <EscolherDocx enviando={enviando} aoEnviar={(arquivo) => void enviar(() => api.enviarDocx(token, arquivo))} />
            )
          ) : pedido ? (
            <Aviso tom="atencao">
              {pedido.situacao === "RECEBIDO" ? "Este link já recebeu o envio. Volte ao chat." : "Este link expirou. Peça um novo no chat."}
            </Aviso>
          ) : null}
        </div>
      </div>
    </main>
  );
}
