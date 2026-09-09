import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";

// Tela mínima da seção 14: uma questão por vez, A-E, Próxima.
export default function AlunoProva() {
  const { id } = useParams();
  const simuladoId = Number(id);
  const navegar = useNavigate();

  const [prova, setProva] = useState<any | null>(null);
  const [indice, setIndice] = useState(0);
  const [marcada, setMarcada] = useState<string | null>(null);
  const [resultado, setResultado] = useState<any | null>(null);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    api.simulado(simuladoId)
      .then((p) => {
        setProva(p);
        if (p.finalizado) api.finalizar(simuladoId).then(setResultado).catch(() => {});
        const primeiraSemResposta = p.questoes.findIndex((q: any) => !q.marcada);
        setIndice(primeiraSemResposta === -1 ? 0 : primeiraSemResposta);
      })
      .catch((e) => setErro(e.message));
  }, [simuladoId]);

  useEffect(() => {
    if (prova) setMarcada(prova.questoes[indice]?.marcada ?? null);
  }, [indice, prova]);

  if (erro) return <div className="erro">{erro}</div>;
  if (!prova) return <p className="legenda">carregando…</p>;

  if (resultado) {
    return (
      <>
        <h2>{resultado.titulo}</h2>
        <p className="legenda">
          Você acertou {resultado.acertos} de {resultado.total} ({resultado.percentual}%).
        </p>
        {resultado.respostas.map((r: any, i: number) => (
          <div key={r.questao_id} className="cartao">
            <div className="entre">
              <strong>Questão {i + 1}</strong>
              <span className={`etiqueta ${r.correta ? "publicado" : "rascunho"}`}>
                {r.correta ? "acertou" : "errou"}
              </span>
            </div>
            <div className="legenda" style={{ margin: "6px 0 0" }}>
              você marcou {r.marcada} · gabarito {r.gabarito}
            </div>
          </div>
        ))}
        <button onClick={() => navegar("/simulados")}>Voltar</button>
      </>
    );
  }

  const questao = prova.questoes[indice];
  const ultima = indice === prova.questoes.length - 1;

  async function avancar() {
    if (!marcada) return;
    setOcupado(true);
    setErro("");
    try {
      await api.responder(simuladoId, questao.questao_id, marcada);
      prova.questoes[indice].marcada = marcada;
      if (ultima) setResultado(await api.finalizar(simuladoId));
      else setIndice(indice + 1);
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  return (
    <>
      <h2>{prova.titulo}</h2>
      <p className="legenda">Questão {indice + 1}/{prova.questoes.length} · {prova.turma}</p>

      <div className="cartao">
        <p style={{ marginTop: 0 }}>{questao.enunciado}</p>
        {Object.entries(questao.alternativas).map(([letra, texto]) => (
          <div key={letra} className={`alternativa ${marcada === letra ? "marcada" : ""}`}
               onClick={() => setMarcada(letra)}>
            <span className="letra">{letra}</span>
            <span>{texto as string}</span>
          </div>
        ))}
      </div>

      <div className="linha">
        <button disabled={indice === 0} onClick={() => setIndice(indice - 1)}>Anterior</button>
        <button className="primario" disabled={!marcada || ocupado} onClick={avancar}>
          {ultima ? "Finalizar" : "Próxima"}
        </button>
      </div>
    </>
  );
}
