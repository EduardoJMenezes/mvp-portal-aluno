"use client";

import { useState } from "react";
import { Botao, Campo, Cartao } from "@/components/ui";
import { api, type Turma } from "@/lib/api";
import { plural } from "@/lib/formato";
import type { Executar } from "./comum";

/** A turma "para" passa a receber os módulos e as aulas restritas da turma "de" (decisão 0011). */
export function CopiarEntreTurmas({ turmas, executar, aoFechar }: { turmas: Turma[]; executar: Executar; aoFechar: () => void }) {
  const [de, setDe] = useState("");
  const [para, setPara] = useState("");

  async function copiar() {
    const origem = turmas.find((t) => String(t.id) === de);
    const destino = turmas.find((t) => String(t.id) === para);
    if (!origem || !destino) return;
    let copia = { modulos: 0, itens: 0 };
    const ok = await executar(
      async () => {
        copia = await api.copiarModulos(destino.id, origem.id);
      },
      () => `${plural(copia.modulos, "módulo", "módulos")} e ${plural(copia.itens, "item restrito", "itens restritos")} de ${origem.nome} agora também são de ${destino.nome}.`,
    );
    if (ok) aoFechar();
  }

  return (
    <Cartao className="flex flex-col gap-3 p-5">
      <div>
        <h2 className="text-lg font-bold text-tinta">Copiar o curso de uma turma para outra</h2>
        <p className="text-sm text-suave">A segunda turma passa a receber os mesmos módulos. Nada é duplicado: mexeu num módulo, vale para as duas.</p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <Campo rotulo="De" className="min-w-48">
          {(id) => (
            <select id={id} value={de} onChange={(e) => setDe(e.target.value)} className="campo">
              <option value="">Escolha…</option>
              {turmas.map((t) => (
                <option key={t.id} value={t.id}>{t.nome}</option>
              ))}
            </select>
          )}
        </Campo>
        <Campo rotulo="Para" className="min-w-48">
          {(id) => (
            <select id={id} value={para} onChange={(e) => setPara(e.target.value)} className="campo">
              <option value="">Escolha…</option>
              {turmas.filter((t) => String(t.id) !== de).map((t) => (
                <option key={t.id} value={t.id}>{t.nome}</option>
              ))}
            </select>
          )}
        </Campo>
        <Botao variante="primario" disabled={!de || !para} onClick={() => void copiar()}>Copiar módulos</Botao>
        <Botao onClick={aoFechar}>Fechar</Botao>
      </div>
      <p className="text-[13px] text-suave">O que a turma de destino já tem continua.</p>
    </Cartao>
  );
}
