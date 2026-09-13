import { useEffect, useState } from "react";
import { api, type Rascunho } from "../api";
import { TextoFormatado } from "../Questao";

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
              {aberto.itens?.map((item) => (
                <div key={item.item_id} style={{ marginBottom: 12 }}>
                  <strong>{item.nome}</strong>
                  <span className={`etiqueta ${item.status.toLowerCase()}`} style={{ marginLeft: 8 }}>
                    {item.status}
                  </span>
                  <div className="legenda mono" style={{ margin: 0 }}>
                    🎬 vimeo {item.video.vimeo_id} — {item.video.titulo}
                  </div>
                  {item.assuntos.length > 0 && (
                    <div className="legenda" style={{ margin: "2px 0 0" }}>
                      🏷 {item.assuntos.map((a) =>
                        a.subassunto ? `${a.assunto} › ${a.subassunto}` : a.assunto).join(", ")}
                    </div>
                  )}
                </div>
              ))}

              {aberto.simulado && (
                <div style={{ marginBottom: 14 }}>
                  <strong>{aberto.simulado.titulo}</strong> — {aberto.simulado.turmas.join(", ")}
                  <div className="legenda" style={{ margin: 0 }}>
                    abre {aberto.simulado.abre_em ?? "—"} · fecha {aberto.simulado.fecha_em ?? "—"} ·{" "}
                    {aberto.simulado.duracao_minutos ?? "—"} min de prova
                  </div>
                  {aberto.simulado.pendencias_para_publicar.length > 0 && (
                    <div className="erro" style={{ margin: "8px 0 0" }}>
                      Ainda não publica: {aberto.simulado.pendencias_para_publicar.join("; ")}.
                    </div>
                  )}
                  <ol style={{ margin: "6px 0 0", paddingLeft: 20 }}>
                    {aberto.simulado.questoes.map((q) => (
                      <li key={q.questao_id}>
                        gabarito {q.gabarito}{q.nova ? "" : " · do acervo"}
                        {q.imagem_pendente ? " · 🖼 imagem pendente" : ""}
                        {q.resolucao ? ` · 🎬 ${q.resolucao}` : " · sem vídeo de resolução"}
                      </li>
                    ))}
                  </ol>
                </div>
              )}
              {aberto.questoes?.map((q) => (
                <div key={q.questao_id} className="cartao">
                  <TextoFormatado texto={q.enunciado} />
                  {q.completa ? Object.entries(q.alternativas).map(([l, t]) => (
                    <div key={l} className={`alternativa fixa ${l === q.gabarito ? "gabarito" : ""}`}>
                      <span className="letra">{l}</span>
                      <TextoFormatado texto={t} />
                    </div>
                  )) : (
                    <div className="legenda" style={{ margin: "4px 0 0" }}>
                      sem alternativas A–E — não pode entrar num simulado
                    </div>
                  )}
                  {q.resolucao_comentada && (
                    <div className="resolucao">
                      <div className="legenda" style={{ margin: "0 0 4px", fontWeight: 600 }}>Resolução comentada</div>
                      <TextoFormatado texto={q.resolucao_comentada} />
                    </div>
                  )}
                  <div className="legenda" style={{ margin: "6px 0 0" }}>
                    {q.classificacao.map((c) => c.subassunto ? `${c.assunto} › ${c.subassunto}` : c.assunto).join(", ") || "sem classificação"}
                    {q.imagem_pendente && " · 🖼 imagem pendente"}
                    {q.video && ` · 🎬 vimeo ${q.video.vimeo_id} — ${q.video.titulo}`}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </>
  );
}
