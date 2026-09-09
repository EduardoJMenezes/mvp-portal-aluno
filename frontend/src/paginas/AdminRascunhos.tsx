import { useEffect, useState } from "react";
import { api, type Rascunho } from "../api";

// A tela que prova a regra da seção 6: o que a IA propôs fica aqui, parado,
// até um humano aprovar.
export default function AdminRascunhos() {
  const [lista, setLista] = useState<Rascunho[]>([]);
  const [aberto, setAberto] = useState<Rascunho | null>(null);
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [ocupado, setOcupado] = useState(false);

  function recarregar() {
    api.rascunhos("RASCUNHO").then(setLista).catch((e) => setErro(e.message));
  }
  useEffect(recarregar, []);

  async function abrir(id: number) {
    setErro("");
    try { setAberto(await api.rascunho(id)); } catch (e) { setErro((e as Error).message); }
  }

  async function publicar(id: number) {
    setOcupado(true); setErro(""); setAviso("");
    try {
      const r = await api.publicar(id);
      setAviso(r.mensagem);
      setAberto(null);
      recarregar();
    } catch (e) { setErro((e as Error).message); } finally { setOcupado(false); }
  }

  async function descartar(id: number) {
    setOcupado(true); setErro("");
    try {
      await api.descartar(id);
      setAberto(null);
      recarregar();
    } catch (e) { setErro((e as Error).message); } finally { setOcupado(false); }
  }

  return (
    <>
      <h2>Rascunhos pendentes</h2>
      <p className="legenda">
        Nada aqui está visível para os alunos. Publicar é uma decisão sua — o backend recusa
        publicar sem essa aprovação.
      </p>
      {erro && <div className="erro">{erro}</div>}
      {aviso && <div className="cartao" style={{ borderColor: "var(--publicado)" }}>{aviso}</div>}

      {lista.length === 0 && <div className="vazio">Nenhum rascunho pendente.</div>}

      {lista.map((r) => (
        <div key={r.rascunho_id} className="cartao">
          <div className="entre">
            <div style={{ flex: 1 }}>
              <h4>#{r.rascunho_id} · {r.resumo}</h4>
              <div className="legenda" style={{ margin: 0 }}>
                {r.tipo} · criado por {r.criado_por} via {r.origem} ·{" "}
                {new Date(r.criado_em).toLocaleString("pt-BR")}
              </div>
            </div>
            <span className="etiqueta rascunho">{r.status}</span>
          </div>
          <div className="linha" style={{ marginTop: 12 }}>
            <button onClick={() => (aberto?.rascunho_id === r.rascunho_id ? setAberto(null) : abrir(r.rascunho_id))}>
              {aberto?.rascunho_id === r.rascunho_id ? "Fechar" : "Revisar"}
            </button>
            <button className="primario" disabled={ocupado} onClick={() => publicar(r.rascunho_id)}>
              Aprovar e publicar
            </button>
            <button className="perigo" disabled={ocupado} onClick={() => descartar(r.rascunho_id)}>
              Descartar
            </button>
          </div>

          {aberto?.rascunho_id === r.rascunho_id && (
            <div style={{ marginTop: 16, borderTop: "1px solid var(--borda)", paddingTop: 12 }}>
              {aberto.questoes?.map((q) => (
                <div key={q.questao_id} style={{ marginBottom: 14 }}>
                  <strong>Q{String(q.numero).padStart(2, "0")}</strong> · {q.enunciado}
                  {q.video && <div className="legenda mono" style={{ margin: 0 }}>🎬 vimeo {q.video.vimeo_id} — {q.video.titulo}</div>}
                  {q.completa ? (
                    <div className="legenda" style={{ margin: "4px 0 0" }}>
                      {Object.entries(q.alternativas).map(([l, t]) => `${l}) ${t}`).join("  ")} · gabarito {q.gabarito}
                    </div>
                  ) : (
                    <div className="legenda" style={{ margin: "4px 0 0" }}>
                      sem alternativas A–E — entra como questão com resolução em vídeo
                    </div>
                  )}
                </div>
              ))}
              {aberto.simulado && (
                <div>
                  <strong>{aberto.simulado.titulo}</strong> — {aberto.simulado.turma}
                  <ol style={{ margin: "6px 0 0", paddingLeft: 20 }}>
                    {aberto.simulado.questoes.map((q) => <li key={q.questao_id}>{q.enunciado}</li>)}
                  </ol>
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </>
  );
}
