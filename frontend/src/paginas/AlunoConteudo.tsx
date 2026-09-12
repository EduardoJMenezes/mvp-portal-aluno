import { useEffect, useState } from "react";
import { api } from "../api";

// Só chega aqui o que o backend deixa este aluno ver (seção 11). O vídeo
// bloqueado vem sem embed_url — sem nada do Vimeo, na verdade —, então não há
// o que o devtools libere: o bloqueio é do backend, não desta tela.
export default function AlunoConteudo() {
  const [arvore, setArvore] = useState<any[]>([]);
  const [vendo, setVendo] = useState<number | null>(null);
  const [erro, setErro] = useState("");

  useEffect(() => { api.conteudo().then(setArvore).catch((e) => setErro(e.message)); }, []);

  return (
    <>
      <h2>Meu conteúdo</h2>
      <p className="legenda">Aulas e resoluções das turmas em que você está matriculado.</p>
      {erro && <div className="erro">{erro}</div>}
      {arvore.length === 0 && <div className="vazio">Nada publicado para você ainda.</div>}

      {arvore.map((t) => (
        <div key={t.turma_id}>
          <h3>{t.turma}</h3>
          {t.modulos.map((m: any) => (
            <div key={m.id} className="cartao">
              <h4>{m.nome}</h4>
              {m.submodulos.map((s: any) => (
                <div key={s.id} style={{ marginTop: 12 }}>
                  <div className="legenda" style={{ fontWeight: 600 }}>
                    {s.nome} · {s.itens.length}
                  </div>
                  {s.itens.map((item: any) => (
                    <ItemDoCurso
                      key={item.id}
                      item={item}
                      aberto={vendo === item.id}
                      aoAlternar={() => setVendo(vendo === item.id ? null : item.id)}
                    />
                  ))}
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}

function ItemDoCurso({ item, aberto, aoAlternar }: {
  item: any; aberto: boolean; aoAlternar: () => void;
}) {
  const video = item.video;
  const bloqueado = !video || video.bloqueado;

  return (
    <div style={{ borderTop: "1px solid var(--borda)", padding: "10px 0" }}>
      <div className="entre">
        <div style={{ flex: 1, opacity: bloqueado ? 0.55 : 1 }}>
          <strong>{item.nome}</strong>
          {bloqueado && (
            <div className="legenda">🔒 {video?.motivo ?? "Não incluído no seu plano"}</div>
          )}
        </div>
        {!bloqueado && (
          <button onClick={aoAlternar}>{aberto ? "Fechar" : "▶ Assistir"}</button>
        )}
      </div>

      {aberto && !bloqueado && (
        <div style={{ marginTop: 10 }}>
          {/* embed_url vem como o Vimeo devolveu: sem o hash de privacidade o
              player recusa tocar vídeo unlisted (ver docs/VIMEO.md). */}
          <iframe src={video.embed_url} allow="fullscreen; picture-in-picture"
                  title={video.titulo} />
          <div className="legenda mono">{video.titulo}</div>
        </div>
      )}
    </div>
  );
}
