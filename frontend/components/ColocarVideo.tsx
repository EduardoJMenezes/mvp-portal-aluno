"use client";

import { useState } from "react";
import { Botao, Campo } from "@/components/ui";
import type { Aula } from "@/lib/api";

/**
 * Um vídeo do Vimeo no lugar da gravação da aula: a que não chegou, ou a que chegou ruim (o backup
 * da equipe, por exemplo). Com capítulo, entra publicado lá; sem, toca na tela de Lives.
 */
export function ColocarVideo({ aula, aoColocar }: { aula: Aula; aoColocar: (link: string) => Promise<boolean> }) {
  const [aberto, setAberto] = useState(false);
  const [link, setLink] = useState("");
  const temGravacao = !!aula.gravacao && aula.gravacao !== "enviando";

  if (!aberto) {
    return (
      <Botao variante="texto" className="w-fit" onClick={() => { setLink(""); setAberto(true); }}>
        {temGravacao ? "Trocar vídeo" : "Colocar vídeo"}
      </Botao>
    );
  }
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        if (await aoColocar(link.trim())) setAberto(false);
      }}
    >
      <Campo
        rotulo="Link do vídeo no Vimeo"
        dica={aula.submodulo_id ? "Entra publicado no capítulo, na posição da gravação." : "Toca para o aluno na tela de Lives."}
        className="min-w-60 flex-1"
      >
        {(id) => <input id={id} required value={link} onChange={(e) => setLink(e.target.value)} placeholder="https://vimeo.com/123456789" className="campo" />}
      </Campo>
      <Botao type="submit" variante="primario" tamanho="pequeno" disabled={!link.trim()}>Publicar vídeo</Botao>
      <Botao tamanho="pequeno" onClick={() => setAberto(false)}>Cancelar</Botao>
    </form>
  );
}
