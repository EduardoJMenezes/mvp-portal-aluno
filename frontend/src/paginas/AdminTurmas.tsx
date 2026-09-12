import { useEffect, useState } from "react";
import { api, type Modulo } from "../api";

// Dashboard > Turmas > Módulos > Sub-módulos > Itens (seção 10 do MVP).
// A árvore é a mesma que o aluno vê, com os rascunhos à mostra.
export default function AdminTurmas() {
  const [turmas, setTurmas] = useState<any[]>([]);
  const [turma, setTurma] = useState<string>("");
  const [modulos, setModulos] = useState<Modulo[]>([]);
  const [erro, setErro] = useState("");

  useEffect(() => {
    api.turmas().then((t) => {
      setTurmas(t);
      if (t.length && !turma) setTurma(t[0].nome);
    }).catch((e) => setErro(e.message));
  }, []);

  useEffect(() => {
    if (!turma) return;
    api.modulos(turma).then(setModulos).catch((e) => setErro(e.message));
  }, [turma]);

  return (
    <>
      <h2>Turmas</h2>
      <p className="legenda">
        O que aparece aqui é o que existe no banco — inclusive o que foi criado pelo agente via MCP.
      </p>
      {erro && <div className="erro">{erro}</div>}

      <div className="linha" style={{ marginBottom: 20 }}>
        {turmas.map((t) => (
          <button key={t.id} className={t.nome === turma ? "primario" : ""} onClick={() => setTurma(t.nome)}>
            {t.nome} · {t.modulos} módulos · {t.itens_publicados} publicados
            {t.itens_em_rascunho > 0 && ` · ${t.itens_em_rascunho} em rascunho`}
          </button>
        ))}
      </div>

      {modulos.length === 0 && <div className="vazio">Nenhum módulo nesta turma ainda.</div>}

      {modulos.map((m) => (
        <div key={m.id}>
          <h3>{m.nome}</h3>
          {m.submodulos.length === 0 && (
            <div className="vazio">Sem sub-módulos. Crie um pelo MCP: criar_submodulo.</div>
          )}
          {m.submodulos.map((s) => (
            <div key={s.id} className="cartao">
              <div className="entre">
                <h4>{s.nome}</h4>
                <span className="legenda">{s.itens.length} item(ns) · {s.tipo.toLowerCase()}</span>
              </div>
              {s.itens.length === 0 && <div className="legenda">Vazio.</div>}
              {s.itens.map((item) => (
                <div key={item.id} className="entre"
                     style={{ borderTop: "1px solid var(--borda)", padding: "8px 0" }}>
                  <div style={{ flex: 1 }}>
                    <strong>{item.nome}</strong>
                    <span className="legenda mono"> · posição {item.ordem}</span>
                  </div>
                  <span className={`etiqueta ${item.status.toLowerCase()}`}>{item.status}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
