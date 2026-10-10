"use client";

// Vídeos do Vimeo entrando num sub-módulo: o explorador do Vimeo, no modo de marcar vários, e a
// gravação das linhas no fim do sub-módulo.

import { useMemo } from "react";
import { ExploradorDoVimeo, type VideoDoVimeo } from "@/components/ExploradorDoVimeo";
import { api, type Modulo, type SubModulo } from "@/lib/api";
import { plural } from "@/lib/formato";
import { BIBLIOTECA, type Executar } from "./comum";

export function AdicionarVideos({ modulo, sub, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; executar: Executar; aoFechar: () => void }) {
  const jaAqui = useMemo(() => new Set(sub.itens.map((i) => i.vimeo_id).filter((id): id is string => !!id)), [sub.itens]);

  async function gravar(escolhidos: VideoDoVimeo[]) {
    const videos = escolhidos.map(({ caminho: _, ...v }) => v);
    const recusados = (await api.adicionarVideos(BIBLIOTECA, modulo.id, sub.id, videos)).erros;
    const entraram = videos.length - recusados.length;
    // O recado mora na página, atrás da camada: só aparece quando ela fecha.
    void executar(
      () => Promise.resolve(),
      `${plural(entraram, "vídeo adicionado", "vídeos adicionados")} em ${sub.nome}, já ${entraram === 1 ? "publicado" : "publicados"}.` + (recusados.length ? ` Ficaram de fora: ${recusados.join("; ")}.` : ""),
    );
  }

  return (
    <ExploradorDoVimeo
      titulo="Adicionar vídeos do Vimeo"
      legenda={`Entram no fim de ${sub.nome}, em ${modulo.nome}, já publicados.`}
      destino={sub.nome}
      jaAqui={jaAqui}
      aoGravar={gravar}
      aoFechar={aoFechar}
    />
  );
}
