import { useEffect, useState } from "react";
import { api, type Questao } from "../api";

// Só chega aqui o que o backend deixa este aluno ver (seção 11).
export default function AlunoConteudo() {
  const [arvore, setArvore] = useState<any[]>([]);
  const [vendo, setVendo] = useState<Questao | null>(null);
  const [erro, setErro] = useState("");

  useEffect(() => { api.conteudo().then(setArvore).catch((e) => setErro(e.message)); }, []);

  return (
    <>
      <h2>Meu conteúdo</h2>
      <p className="legenda">Questões e resoluções das turmas em que você está matriculado.</p>
      {erro && <div className="erro">{erro}</div>}
      {arvore.length === 0 && <div className="vazio">Nada publicado para você ainda.</div>}

      {arvore.map((t) => (
        <div key={t.turma}>
          <h3>{t.turma}</h3>
          {t.capitulos.map((c: any) => (
            <div key={c.capitulo} className="cartao">
              <h4>{c.capitulo}</h4>
              {c.questoes.map((q: Questao) => (
                <div key={q.vinculo_id} style={{ borderTop: "1px solid var(--borda)", padding: "10px 0" }}>
                  <div className="entre">
                    <div style={{ flex: 1 }}>
                      <strong>Questão {String(q.numero).padStart(2, "0")}</strong>
                      <div>{q.enunciado}</div>
                    </div>
                    {q.video && (
                      <button onClick={() => setVendo(vendo?.vinculo_id === q.vinculo_id ? null : q)}>
                        {vendo?.vinculo_id === q.vinculo_id ? "Fechar" : "▶ Resolução"}
                      </button>
                    )}
                  </div>
                  {vendo?.vinculo_id === q.vinculo_id && q.video && (
                    <div style={{ marginTop: 10 }}>
                      <iframe src={q.video.embed_url} allow="fullscreen; picture-in-picture"
                              title={q.video.titulo} />
                      <div className="legenda mono">{q.video.titulo}</div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
