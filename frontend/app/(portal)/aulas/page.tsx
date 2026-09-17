"use client";

import { useState } from "react";
import { Aviso, Botao, Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados, type Aula } from "@/lib/api";
import { emBrasilia } from "@/lib/formato";

const TOM: Record<Aula["estado"], { rotulo: string; tom: "info" | "sucesso" | "neutro" }> = {
  ABERTA: { rotulo: "Ao vivo agora", tom: "sucesso" },
  AGENDADA: { rotulo: "Agendada", tom: "info" },
  ENCERRADA: { rotulo: "Encerrada", tom: "neutro" },
  RASCUNHO: { rotulo: "Rascunho", tom: "neutro" },
};

export default function AulasDoAluno() {
  const lista = useDados(() => api.aulas());
  const [erro, setErro] = useState("");
  const [entrando, setEntrando] = useState(0);

  const entrar = async (aula: Aula) => {
    setErro("");
    setEntrando(aula.aula_id);
    // A aba abre agora, no clique, e recebe o endereço quando ele chega: se
    // esperarmos a resposta para abrir, o navegador trata como pop-up e barra.
    const aba = window.open("", "_blank");
    try {
      const { url } = await api.entrarNaAula(aula.aula_id);
      if (aba) aba.location.href = url;
      else window.location.href = url;
    } catch (ex) {
      aba?.close();
      setErro(ex instanceof Error ? ex.message : "Não foi possível entrar na aula.");
    } finally {
      setEntrando(0);
    }
  };

  return (
    <Pagina
      titulo="Aulas ao vivo"
      legenda="As aulas da sua turma. O botão de entrar acende 15 minutos antes de começar."
    >
      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <Estado {...lista} linhas={3}>
        {(aulas) =>
          aulas.length === 0 ? (
            <Vazio titulo="Nenhuma aula marcada">
              Quando o professor agendar uma aula ao vivo, ela aparece aqui com o horário.
            </Vazio>
          ) : (
            <ul className="grid gap-3">
              {aulas.map((aula) => (
                <Cartao key={aula.aula_id} como="li">
                  <div className="flex flex-wrap items-start justify-between gap-3 p-5">
                    <div className="min-w-0">
                      <span className="flex flex-wrap items-center gap-2">
                        <Etiqueta tom={TOM[aula.estado].tom}>{TOM[aula.estado].rotulo}</Etiqueta>
                        {aula.grava && aula.estado !== "ENCERRADA" && <Etiqueta>Será gravada</Etiqueta>}
                      </span>
                      <h2 className="mt-2 text-lg font-semibold text-tinta">{aula.titulo}</h2>
                      <p className="text-[13px] text-suave">
                        {emBrasilia(aula.inicio_em)} · {aula.minutos} min
                      </p>
                      {aula.descricao && <p className="mt-1 text-sm text-suave">{aula.descricao}</p>}
                    </div>
                    {aula.estado === "ABERTA" ? (
                      <Botao
                        variante="primario"
                        onClick={() => void entrar(aula)}
                        disabled={entrando === aula.aula_id}
                      >
                        {entrando === aula.aula_id ? "Abrindo…" : "Entrar na aula"}
                      </Botao>
                    ) : aula.estado === "AGENDADA" ? (
                      <p className="text-[13px] text-apagado">Abre às {emBrasilia(aula.abre_em)}</p>
                    ) : null}
                  </div>
                </Cartao>
              ))}
            </ul>
          )
        }
      </Estado>
    </Pagina>
  );
}
