import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, emBrasilia } from "../api";

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
              <div className="legenda" style={{ margin: 0 }}>
                {s.total_questoes} questões · {s.duracao_minutos} min de prova · {onde(s)}
              </div>
            </div>
            <Acao s={s} />
          </div>
        </div>
      ))}
    </>
  );
}

function onde(s: any): string {
  if (s.situacao === "AGENDADO") return `abre em ${emBrasilia(s.abre_em)}`;
  if (s.situacao === "ABERTO") {
    return s.minha_prova.entregue
      ? `entregue · o resultado sai em ${emBrasilia(s.fecha_em)}`
      : `aberto até ${emBrasilia(s.fecha_em)}`;
  }
  return s.resultado_disponivel ? "resultado disponível" : "encerrado · você não fez";
}

function Acao({ s }: { s: any }) {
  const navegar = useNavigate();
  const ir = () => navegar(`/simulados/${s.simulado_id}`);

  if (s.situacao === "ABERTO" && !s.minha_prova.entregue) {
    if (s.minha_prova.iniciada) return <button className="primario" onClick={ir}>Continuar</button>;
    // Abrir a prova é começar: o tempo passa a contar no backend a partir daí.
    const comecar = () =>
      window.confirm(`Ao começar, os ${s.duracao_minutos} minutos de prova passam a contar. Começar agora?`) && ir();
    return <button className="primario" onClick={comecar}>Começar</button>;
  }
  if (s.resultado_disponivel) return <button onClick={ir}>Ver resultado</button>;
  return null;
}
