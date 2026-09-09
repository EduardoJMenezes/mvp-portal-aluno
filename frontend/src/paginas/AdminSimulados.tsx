import { useEffect, useState } from "react";
import { api } from "../api";

export default function AdminSimulados() {
  const [lista, setLista] = useState<any[]>([]);
  const [stats, setStats] = useState<any | null>(null);
  const [erro, setErro] = useState("");

  useEffect(() => { api.simuladosAdmin().then(setLista).catch((e) => setErro(e.message)); }, []);

  async function ver(id: number) {
    setErro("");
    try { setStats(stats?.simulado_id === id ? null : await api.estatisticas(id)); }
    catch (e) { setErro((e as Error).message); }
  }

  return (
    <>
      <h2>Simulados</h2>
      <p className="legenda">
        As mesmas estatísticas que o agente lê pelo MCP — vindas das respostas reais dos alunos.
      </p>
      {erro && <div className="erro">{erro}</div>}
      {lista.length === 0 && <div className="vazio">Nenhum simulado ainda.</div>}

      {lista.map((s) => (
        <div key={s.simulado_id} className="cartao">
          <div className="entre">
            <div>
              <h4>{s.titulo}</h4>
              <div className="legenda" style={{ margin: 0 }}>
                {s.turma} · {s.questoes} questões · {s.tentativas} tentativa(s)
              </div>
            </div>
            <div className="linha">
              <span className={`etiqueta ${s.status.toLowerCase()}`}>{s.status}</span>
              {s.status === "PUBLICADO" && (
                <button onClick={() => ver(s.simulado_id)}>
                  {stats?.simulado_id === s.simulado_id ? "Fechar" : "Estatísticas"}
                </button>
              )}
            </div>
          </div>

          {stats?.simulado_id === s.simulado_id && (
            <div style={{ marginTop: 16, borderTop: "1px solid var(--borda)", paddingTop: 12 }}>
              {!stats.encontrou_dados ? (
                <p className="legenda">{stats.mensagem}</p>
              ) : (
                <>
                  <p className="legenda">
                    {stats.alunos_responderam} de {stats.alunos_matriculados} alunos responderam ·
                    média {stats.media_percentual}%
                  </p>
                  <table>
                    <thead>
                      <tr><th>Questão</th><th>Tópico</th><th>Acerto</th><th></th></tr>
                    </thead>
                    <tbody>
                      {stats.por_questao.map((q: any) => (
                        <tr key={q.questao_id}>
                          <td>Q{String(q.numero ?? q.ordem).padStart(2, "0")}</td>
                          <td>{q.topico ?? "—"}</td>
                          <td>{q.percentual_acerto ?? "—"}%</td>
                          <td>
                            <div className="barra">
                              <div style={{ width: `${q.percentual_acerto ?? 0}%` }} />
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <h3>Por aluno</h3>
                  <table>
                    <tbody>
                      {stats.por_aluno.map((a: any) => (
                        <tr key={a.aluno}>
                          <td>{a.aluno}</td>
                          <td>{a.acertos}/{a.total}</td>
                          <td>{a.percentual}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {stats.maior_dificuldade && (
                    <p className="legenda" style={{ marginTop: 12 }}>
                      Maior dificuldade: <strong>{stats.maior_dificuldade.topico}</strong>{" "}
                      ({stats.maior_dificuldade.percentual_acerto}% de acerto)
                    </p>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      ))}
    </>
  );
}
