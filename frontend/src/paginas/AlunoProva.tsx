import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, emBrasilia } from "../api";
import { FiguraDaQuestao, TextoFormatado } from "../Questao";
import { ItemDoCurso } from "./AlunoConteudo";

// A prova vista pelo aluno. Quem decide o estado — agendada, em andamento,
// entregue, encerrada — e o prazo é o backend; esta tela só desenha.
export default function AlunoProva() {
  const simuladoId = Number(useParams().id);
  const [prova, setProva] = useState<any | null>(null);
  const [resultado, setResultado] = useState<any | null>(null);
  const [erro, setErro] = useState("");

  const carregar = useCallback(async () => {
    setErro("");
    try {
      const p = await api.simulado(simuladoId);
      setProva(p);
      if (p.estado === "ENCERRADO" && p.resultado_disponivel) {
        setResultado(await api.resultado(simuladoId));
      }
    } catch (e) {
      setErro((e as Error).message);
    }
  }, [simuladoId]);

  useEffect(() => { carregar(); }, [carregar]);

  if (erro) return <div className="erro">{erro}</div>;
  if (!prova) return <p className="legenda">carregando…</p>;
  if (resultado) return <Resultado r={resultado} />;

  if (prova.estado === "EM_ANDAMENTO") return <EmAndamento prova={prova} aoAcabar={carregar} />;
  if (prova.estado === "AGENDADO") {
    return <Aviso titulo={prova.titulo} texto={`A prova abre em ${emBrasilia(prova.abre_em)}.`} />;
  }
  if (prova.estado === "ENTREGUE") {
    const como = prova.entregue_automaticamente
      ? "O tempo acabou, e a prova foi entregue com o que estava respondido."
      : "Prova entregue.";
    return <Aviso titulo={prova.titulo} texto={`${como} O resultado sai em ${emBrasilia(prova.resultado_em)}.`} />;
  }
  return <Aviso titulo={prova.titulo} texto="Este simulado já fechou, e você não chegou a fazer a prova." />;
}

function Aviso({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <>
      <h2>{titulo}</h2>
      <div className="cartao">{texto}</div>
      <Link to="/simulados"><button>Voltar</button></Link>
    </>
  );
}

function relogio(segundos: number): string {
  const dois = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(segundos / 3600);
  const m = Math.floor((segundos % 3600) / 60);
  return `${h ? `${h}:` : ""}${dois(m)}:${dois(segundos % 60)}`;
}

function EmAndamento({ prova, aoAcabar }: { prova: any; aoAcabar: () => void }) {
  const total = prova.questoes.length;
  const [indice, setIndice] = useState(() =>
    Math.max(0, prova.questoes.findIndex((q: any) => !q.marcada)));
  const [marcadas, setMarcadas] = useState<Record<number, string>>(() =>
    Object.fromEntries(prova.questoes.filter((q: any) => q.marcada).map((q: any) => [q.questao_id, q.marcada])));
  const [restante, setRestante] = useState<number>(prova.segundos_restantes);
  const [entregando, setEntregando] = useState(false);
  const [erro, setErro] = useState("");

  // O relógio daqui só mostra o prazo que o backend deu. Zerou, recarrega:
  // é lá que a entrega automática acontece.
  useEffect(() => {
    const fim = Date.now() + prova.segundos_restantes * 1000;
    const tique = setInterval(() => {
      const faltam = Math.max(0, Math.round((fim - Date.now()) / 1000));
      setRestante(faltam);
      if (faltam === 0) {
        clearInterval(tique);
        aoAcabar();
      }
    }, 1000);
    return () => clearInterval(tique);
  }, [prova, aoAcabar]);

  const questao = prova.questoes[indice];
  const respondidas = Object.keys(marcadas).length;

  // Cada marcação é gravada na hora: se o tempo acabar, entra o que já foi marcado.
  async function marcar(letra: string) {
    const id = questao.questao_id;
    const antes = marcadas[id];
    setMarcadas((m) => ({ ...m, [id]: letra }));
    setErro("");
    try {
      await api.responder(prova.simulado_id, id, letra);
    } catch (e) {
      setMarcadas((m) => {
        const volta = { ...m };
        if (antes) volta[id] = antes;
        else delete volta[id];
        return volta;
      });
      setErro((e as Error).message);
    }
  }

  async function entregar() {
    const brancas = total - respondidas;
    const pergunta = brancas
      ? `Entregar com ${brancas} questão(ões) em branco? Em branco conta como erro.`
      : "Entregar a prova?";
    if (!window.confirm(pergunta)) return;
    setEntregando(true);
    try {
      await api.entregar(prova.simulado_id);
      aoAcabar();
    } catch (e) {
      setErro((e as Error).message);
      setEntregando(false);
    }
  }

  return (
    <>
      <div className="entre">
        <div>
          <h2>{prova.titulo}</h2>
          <p className="legenda">Questão {indice + 1} de {total} · {respondidas} respondida(s)</p>
        </div>
        <div className={`cronometro ${restante <= 300 ? "acabando" : ""}`} title="Tempo restante">
          {relogio(restante)}
        </div>
      </div>
      {erro && <div className="erro">{erro}</div>}

      <div className="cartao">
        <TextoFormatado texto={questao.enunciado} />
        {questao.tem_imagem && <FiguraDaQuestao questaoId={questao.questao_id} />}
        {Object.entries(questao.alternativas).map(([letra, texto]) => (
          <div key={letra} onClick={() => marcar(letra)}
               className={`alternativa ${marcadas[questao.questao_id] === letra ? "marcada" : ""}`}>
            <span className="letra">{letra}</span>
            <TextoFormatado texto={texto as string} />
          </div>
        ))}
      </div>

      <div className="linha">
        <button disabled={indice === 0} onClick={() => setIndice(indice - 1)}>Anterior</button>
        <button disabled={indice === total - 1} onClick={() => setIndice(indice + 1)}>Próxima</button>
        <button className="primario" style={{ marginLeft: "auto" }} disabled={entregando}
                onClick={entregar}>
          Entregar prova
        </button>
      </div>
    </>
  );
}

function Resultado({ r }: { r: any }) {
  const [vendo, setVendo] = useState<string | null>(null);
  const alternar = (chave: string) => setVendo(vendo === chave ? null : chave);

  return (
    <>
      <h2>{r.titulo}</h2>
      <div className="cartao placar">
        <div><strong>{r.acertos}</strong> de {r.total} ({r.percentual}%)</div>
        <div><strong>{r.posicao}º</strong> de {r.participantes}</div>
        {r.em_branco > 0 && <div><strong>{r.em_branco}</strong> em branco</div>}
      </div>
      {r.entregue_automaticamente && (
        <p className="legenda">A prova foi entregue automaticamente quando o tempo acabou.</p>
      )}

      <h3>Questão a questão</h3>
      {r.questoes.map((q: any) => (
        <div key={q.questao_id} className="cartao">
          <div className="entre" style={{ marginBottom: 8 }}>
            <strong>Questão {q.ordem}</strong>
            <span className={`etiqueta ${q.correta ? "publicado" : "rascunho"}`}>
              {q.correta ? "acertou" : q.em_branco ? "em branco" : "errou"}
            </span>
          </div>
          <TextoFormatado texto={q.enunciado} />
          {q.tem_imagem && <FiguraDaQuestao questaoId={q.questao_id} />}
          {Object.entries(q.alternativas).map(([letra, texto]) => (
            <div key={letra} className={[
              "alternativa fixa",
              letra === q.gabarito ? "gabarito" : "",
              letra === q.marcada && !q.correta ? "errada" : "",
            ].join(" ")}>
              <span className="letra">{letra}</span>
              <TextoFormatado texto={texto as string} />
              {letra === q.marcada && <span className="legenda" style={{ margin: 0 }}>sua resposta</span>}
            </div>
          ))}
          {q.resolucao && (
            <ItemDoCurso item={{ id: q.resolucao.id, nome: "Resolução em vídeo", video: q.resolucao }}
                         aberto={vendo === `q${q.questao_id}`}
                         aoAlternar={() => alternar(`q${q.questao_id}`)} />
          )}
        </div>
      ))}

      {r.analise.length > 0 && (
        <>
          <h3>Onde revisar</h3>
          <p className="legenda">Os assuntos em que você mais errou, e os vídeos que explicam cada um.</p>
          {r.analise.map((t: any) => (
            <div key={t.topico} className="cartao">
              <h4>{t.topico} · {t.erros} erro(s)</h4>
              {t.videos.length === 0 && <div className="legenda">Ainda sem vídeo sobre este assunto.</div>}
              {t.videos.map((v: any) => (
                <ItemDoCurso key={v.id} item={{ id: v.id, nome: v.titulo, video: v }}
                             aberto={vendo === `${t.topico}-${v.id}`}
                             aoAlternar={() => alternar(`${t.topico}-${v.id}`)} />
              ))}
            </div>
          ))}
        </>
      )}

      <Link to="/simulados"><button>Voltar</button></Link>
    </>
  );
}
