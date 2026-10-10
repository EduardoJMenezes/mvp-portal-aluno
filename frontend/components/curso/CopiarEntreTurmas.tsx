"use client";

import { useState } from "react";
import { Modal, useSaida } from "@/components/Camadas";
import { Botao, Campo } from "@/components/ui";
import { api, type Turma } from "@/lib/api";
import { plural } from "@/lib/formato";
import type { Executar } from "./comum";

/** A turma "para" passa a receber os módulos e as aulas restritas da turma "de" (decisão 0011). */
export function CopiarEntreTurmas({ turmas, executar, aoFechar }: { turmas: Turma[]; executar: Executar; aoFechar: () => void }) {
  const [de, setDe] = useState("");
  const [para, setPara] = useState("");
  const [copiando, setCopiando] = useState(false);
  const saida = useSaida(aoFechar);

  async function copiar() {
    const origem = turmas.find((t) => String(t.id) === de);
    const destino = turmas.find((t) => String(t.id) === para);
    if (!origem || !destino) return;
    let copia = { modulos: 0, itens: 0 };
    setCopiando(true);
    const ok = await executar(
      async () => {
        copia = await api.copiarModulos(destino.id, origem.id);
      },
      () => `${plural(copia.modulos, "módulo", "módulos")} e ${plural(copia.itens, "item restrito", "itens restritos")} de ${origem.nome} agora também são de ${destino.nome}.`,
    );
    setCopiando(false);
    if (ok) saida.fechar();
  }

  return (
    <Modal
      {...saida}
      aoFechar={() => !copiando && saida.fechar()}
      tamanho="medio"
      titulo="Copiar o curso de uma turma para outra"
      legenda="A segunda turma passa a receber os mesmos módulos. Nada é duplicado: mexeu num módulo, vale para as duas."
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={saida.fechar} disabled={copiando}>Cancelar</Botao>
          <Botao variante="primario" disabled={!de || !para || copiando} onClick={() => void copiar()}>{copiando ? "Copiando…" : "Copiar módulos"}</Botao>
        </div>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2" data-foco-inicial>
          <Campo rotulo="De">
            {(id) => (
              <select id={id} value={de} onChange={(e) => setDe(e.target.value)} className="campo">
                <option value="">Escolha…</option>
                {turmas.map((t) => (
                  <option key={t.id} value={t.id}>{t.nome}</option>
                ))}
              </select>
            )}
          </Campo>
          <Campo rotulo="Para">
            {(id) => (
              <select id={id} value={para} onChange={(e) => setPara(e.target.value)} className="campo">
                <option value="">Escolha…</option>
                {turmas.filter((t) => String(t.id) !== de).map((t) => (
                  <option key={t.id} value={t.id}>{t.nome}</option>
                ))}
              </select>
            )}
          </Campo>
        </div>
        <p className="text-[13px] text-suave">O que a turma de destino já tem continua.</p>
      </div>
    </Modal>
  );
}
