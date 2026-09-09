import { useEffect, useState } from "react";
import { api, type Questao } from "../api";

// Dashboard > Turmas > Capítulos > Questões > Vídeo Vimeo (seção 10 do MVP).
export default function AdminTurmas() {
  const [turmas, setTurmas] = useState<any[]>([]);
  const [turma, setTurma] = useState<string>("");
  const [questoes, setQuestoes] = useState<Questao[]>([]);
  const [erro, setErro] = useState("");

  useEffect(() => {
    api.turmas().then((t) => {
      setTurmas(t);
      if (t.length && !turma) setTurma(t[0].nome);
    }).catch((e) => setErro(e.message));
  }, []);

  useEffect(() => {
    if (!turma) return;
    api.questoes(turma).then(setQuestoes).catch((e) => setErro(e.message));
  }, [turma]);

  const porCapitulo = questoes.reduce<Record<string, Questao[]>>((acc, q) => {
    (acc[q.capitulo] ??= []).push(q);
    return acc;
  }, {});

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
            {t.nome} · {t.questoes_publicadas} publicadas
            {t.questoes_em_rascunho > 0 && ` · ${t.questoes_em_rascunho} em rascunho`}
          </button>
        ))}
      </div>

      {Object.keys(porCapitulo).length === 0 && <div className="vazio">Nenhuma questão nesta turma.</div>}

      {Object.entries(porCapitulo).map(([capitulo, lista]) => (
        <div key={capitulo}>
          <h3>{capitulo}</h3>
          {lista.map((q) => (
            <div key={q.vinculo_id} className="cartao">
              <div className="entre">
                <div style={{ flex: 1 }}>
                  <h4>Q{String(q.numero).padStart(2, "0")} · {q.enunciado}</h4>
                  <div className="legenda" style={{ margin: "4px 0 0" }}>
                    {q.subtopico ?? q.topico ?? "—"} · {q.dificuldade.toLowerCase()}
                    {q.gabarito && Object.keys(q.alternativas).length > 0 && ` · gabarito ${q.gabarito}`}
                    {Object.keys(q.alternativas).length === 0 && " · sem alternativas (só vídeo)"}
                  </div>
                </div>
                <span className={`etiqueta ${q.status.toLowerCase()}`}>{q.status}</span>
              </div>
              {q.video && (
                <div className="legenda" style={{ marginTop: 8 }}>
                  🎬 <a href={q.video.url} target="_blank" rel="noreferrer">{q.video.titulo}</a>
                  <span className="mono"> (vimeo {q.video.vimeo_id})</span>
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
