import { useEffect, useMemo, useState } from "react";
import { api } from "../api";

// A página do link de envio: o .docx do simulado ou os prints das questões.
// Não pede login: o link é a credencial — uso único, com prazo, preso a quem o
// pediu no chat (ver services/importacoes.py).
export default function Enviar({ token }: { token: string }) {
  const [pedido, setPedido] = useState<any | null>(null);
  const [recibo, setRecibo] = useState<any | null>(null);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);

  useEffect(() => { api.envio(token).then(setPedido).catch((e) => setErro(e.message)); }, [token]);

  async function enviar(envio: () => Promise<any>) {
    setEnviando(true);
    setErro("");
    try {
      setRecibo(await envio());
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setEnviando(false);
    }
  }

  const prints = pedido?.formato === "prints";
  return (
    <main className="entrar" style={{ maxWidth: prints ? 720 : 520 }}>
      <div className="cartao">
        <h2>{prints ? "Enviar prints das questões" : "Enviar simulado"}</h2>
        {pedido && (
          <p className="legenda">
            {pedido.titulo ? `${pedido.titulo} · ` : ""}
            {pedido.turmas.length ? `${pedido.turmas.join(", ")} · ` : ""}
            pedido por {pedido.pedido_por} no chat
          </p>
        )}
        {erro && <div className="erro">{erro}</div>}

        {recibo ? (
          <>
            {prints ? (
              <p><strong>{recibo.prints} print(s)</strong> recebido(s).</p>
            ) : (
              <p>
                <strong>{recibo.titulo}</strong>: {recibo.questoes_completas} de {recibo.questoes_lidas}{" "}
                questões lidas por completo, {recibo.figuras} figura(s).
              </p>
            )}
            <p className="legenda" style={{ margin: 0 }}>{recibo.mensagem}</p>
          </>
        ) : pedido?.situacao === "AGUARDANDO" ? (
          prints ? (
            <Prints expira={pedido.expira_em} enviando={enviando}
                    aoEnviar={(arquivos) => enviar(() => api.enviarPrints(token, arquivos))} />
          ) : (
            <Docx expira={pedido.expira_em} enviando={enviando}
                  aoEnviar={(arquivo) => enviar(() => api.enviarDocx(token, arquivo))} />
          )
        ) : pedido ? (
          <p>
            {pedido.situacao === "RECEBIDO"
              ? "Este link já recebeu o envio. Volte ao chat."
              : "Este link expirou. Peça um novo no chat."}
          </p>
        ) : null}
      </div>
    </main>
  );
}

type Envio<T> = { expira: string; enviando: boolean; aoEnviar: (arquivos: T) => void };

function Docx({ expira, enviando, aoEnviar }: Envio<File>) {
  const [arquivo, setArquivo] = useState<File | null>(null);
  return (
    <>
      <p className="legenda">O arquivo .docx do simulado. O link vale até {expira}.</p>
      <input type="file" accept=".docx" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
      <div className="linha" style={{ marginTop: 12 }}>
        <button className="primario" disabled={!arquivo || enviando} onClick={() => arquivo && aoEnviar(arquivo)}>
          {enviando ? "Lendo o documento…" : "Enviar"}
        </button>
      </div>
    </>
  );
}

// Print vem de qualquer lugar — prova, PDF, site — e quase sempre está na área
// de transferência: colar (Ctrl+V) é o caminho principal; escolher e arrastar
// também valem. A ordem é a de chegada.
function Prints({ expira, enviando, aoEnviar }: Envio<File[]>) {
  const [prints, setPrints] = useState<File[]>([]);
  const adicionar = (arquivos: Iterable<File>) =>
    setPrints((atuais) => [...atuais, ...[...arquivos].filter((f) => f.type.startsWith("image/"))]);
  const enderecos = useMemo(() => prints.map((f) => URL.createObjectURL(f)), [prints]);
  useEffect(() => () => enderecos.forEach((e) => URL.revokeObjectURL(e)), [enderecos]);

  useEffect(() => {
    const colar = (e: ClipboardEvent) => adicionar(e.clipboardData?.files ?? []);
    window.addEventListener("paste", colar);
    return () => window.removeEventListener("paste", colar);
  }, []);

  return (
    <>
      <p className="legenda">
        Cole os prints aqui com Ctrl+V — um de cada vez ou vários —, na ordem das questões. O link vale
        até {expira}.
      </p>
      <div className="soltar" onDragOver={(e) => e.preventDefault()}
           onDrop={(e) => { e.preventDefault(); adicionar(e.dataTransfer.files); }}>
        <input type="file" accept="image/*" multiple
               onChange={(e) => { adicionar(e.target.files ?? []); e.target.value = ""; }} />
        <span className="legenda" style={{ margin: 0 }}>ou arraste as imagens para cá</span>
      </div>
      {prints.length > 0 && (
        <div className="prints">
          {prints.map((_, i) => (
            <figure key={enderecos[i]}>
              <img src={enderecos[i]} alt={`Print ${i + 1}`} />
              <figcaption className="entre">
                <span>{i + 1}</span>
                <button className="perigo" onClick={() => setPrints(prints.filter((__, j) => j !== i))}>
                  remover
                </button>
              </figcaption>
            </figure>
          ))}
        </div>
      )}
      <div className="linha" style={{ marginTop: 12 }}>
        <button className="primario" disabled={!prints.length || enviando} onClick={() => aoEnviar(prints)}>
          {enviando ? "Enviando…" : `Enviar ${prints.length} print(s)`}
        </button>
      </div>
    </>
  );
}
