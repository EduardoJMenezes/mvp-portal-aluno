import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";

export default function AlunoSimulados() {
  const [lista, setLista] = useState<any[]>([]);
  const [erro, setErro] = useState("");

  useEffect(() => { api.simulados().then(setLista).catch((e) => setErro(e.message)); }, []);

  return (
    <>
      <h2>Simulados</h2>
      <p className="legenda">Só aparecem simulados publicados para a sua turma.</p>
      {erro && <div className="erro">{erro}</div>}
      {lista.length === 0 && <div className="vazio">Nenhum simulado disponível.</div>}

      {lista.map((s) => (
        <div key={s.simulado_id} className="cartao">
          <div className="entre">
            <div>
              <h4>{s.titulo}</h4>
              <div className="legenda" style={{ margin: 0 }}>{s.questoes} questões · {s.turma}</div>
            </div>
            <Link to={`/simulados/${s.simulado_id}`}>
              <button className={s.respondido ? "" : "primario"}>
                {s.respondido ? "Ver resultado" : s.iniciado ? "Continuar" : "Responder"}
              </button>
            </Link>
          </div>
        </div>
      ))}
    </>
  );
}
