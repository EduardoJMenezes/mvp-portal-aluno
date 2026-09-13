import { useEffect, useState } from "react";
import { api } from "../api";

// A página do link de envio. Não pede login: o link é a credencial — uso
// único, com prazo, preso a quem o pediu no chat (ver services/importacoes.py).
export default function EnviarDocx({ token }: { token: string }) {
  const [pedido, setPedido] = useState<any | null>(null);
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [recibo, setRecibo] = useState<any | null>(null);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  useEffect(() => { api.envio(token).then(setPedido).catch((e) => setErro(e.message)); }, [token]);

  async function enviar() {
    if (!arquivo) return;
    setEnviando(true);
    setErro("");
    try {
      setRecibo(await api.enviarDocx(token, arquivo));
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <main className="entrar" style={{ maxWidth: 520 }}>
      <div className="cartao">
        <h2>Enviar simulado</h2>
        {pedido && (
          <p className="legenda">
            {pedido.titulo ? `${pedido.titulo} · ` : ""}{pedido.turmas.join(", ")} · pedido por{" "}
            {pedido.pedido_por} no chat
          </p>
        )}
        {erro && <div className="erro">{erro}</div>}

        {recibo ? (
          <>
            <p>
              <strong>{recibo.titulo}</strong>: {recibo.questoes_completas} de {recibo.questoes_lidas}{" "}
              questões lidas por completo, {recibo.figuras} figura(s).
            </p>
            <p className="legenda" style={{ margin: 0 }}>{recibo.mensagem}</p>
          </>
        ) : pedido?.situacao === "AGUARDANDO" ? (
          <>
            <p className="legenda">O arquivo .docx do simulado. O link vale até {pedido.expira_em}.</p>
            <input type="file" accept=".docx"
                   onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
            <div className="linha" style={{ marginTop: 12 }}>
              <button className="primario" disabled={!arquivo || enviando} onClick={enviar}>
                {enviando ? "Lendo o documento…" : "Enviar"}
              </button>
            </div>
          </>
        ) : pedido ? (
          <p>
            {pedido.situacao === "RECEBIDO"
              ? "Este link já recebeu o arquivo. Volte ao chat."
              : "Este link expirou. Peça um novo no chat."}
          </p>
        ) : null}
      </div>
    </main>
  );
}
