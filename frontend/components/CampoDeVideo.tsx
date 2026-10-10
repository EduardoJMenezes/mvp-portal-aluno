"use client";

// O campo "um vídeo do Vimeo" de um formulário: em vez do número do vídeo, o professor escolhe no
// explorador (pastas, busca e prévia) e o campo mostra o que ficou escolhido, com como conferir,
// trocar e tirar. Quem já tem o link do vídeo em mãos pode colar.

import { Play, Search, X } from "lucide-react";
import { useId, useState } from "react";
import { Modal } from "@/components/Camadas";
import { ExploradorDoVimeo } from "@/components/ExploradorDoVimeo";
import { TelaDoVideo } from "@/components/PreviaDoVideo";
import { Botao } from "@/components/ui";
import { duracao } from "@/lib/formato";

/** Do vídeo que veio de um cadastro antigo só se sabe o número e, às vezes, o título. */
export type VideoNoCampo = { vimeo_id: string; titulo?: string | null; caminho?: string | null; embed_url?: string | null; thumbnail_url?: string | null; duracao_segundos?: number | null };

/** O número do vídeo, de dentro de um link ("vimeo.com/123456789/abc") ou digitado sozinho. */
const numeroDoVimeo = (texto: string) => /(?:^|\/)(\d{5,})(?:\D|$)/.exec(texto.trim())?.[1] ?? null;

export function CampoDeVideo({
  rotulo,
  dica,
  video,
  aoMudar,
  tituloDaEscolha = "Escolher o vídeo no Vimeo",
}: {
  rotulo: string;
  dica?: string;
  video: VideoNoCampo | null;
  aoMudar: (video: VideoNoCampo | null) => void;
  /** O título do explorador: diz para que serve o vídeo que vai ser escolhido. */
  tituloDaEscolha?: string;
}) {
  const [escolhendo, setEscolhendo] = useState(false);
  const [vendo, setVendo] = useState(false);
  const [colando, setColando] = useState(false);
  const [colado, setColado] = useState("");
  const idDoLink = useId();
  const numero = numeroDoVimeo(colado);
  const nome = video?.titulo || (video ? `Vídeo ${video.vimeo_id}` : "");

  const usarOColado = () => {
    if (!numero) return;
    aoMudar({ vimeo_id: numero });
    setColado("");
    setColando(false);
  };

  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-sm font-semibold text-tinta-2">{rotulo}</p>
      {video ? (
        <div className="flex items-center gap-3 rounded-cartao border border-borda bg-canvas/50 p-2.5">
          <button
            type="button"
            onClick={() => setVendo(true)}
            aria-label={`Ver o vídeo ${nome}`}
            title="Ver o vídeo"
            className="group relative flex aspect-video w-24 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-tinta text-white"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {video.thumbnail_url && <img src={video.thumbnail_url} alt="" className="absolute inset-0 size-full object-cover opacity-80 transition-opacity group-hover:opacity-60" />}
            <span className="relative flex size-7 items-center justify-center rounded-full bg-acento">
              <Play aria-hidden="true" className="size-3 translate-x-px" fill="currentColor" />
            </span>
          </button>
          <div className="min-w-0 flex-1">
            <p className="line-clamp-2 text-[15px] font-medium leading-snug text-tinta">{nome}</p>
            <p className="truncate text-[13px] text-suave">{[video.caminho, duracao(video.duracao_segundos), `Vimeo ${video.vimeo_id}`].filter(Boolean).join(", ")}</p>
          </div>
          <Botao tamanho="pequeno" onClick={() => setEscolhendo(true)}>Trocar</Botao>
          <button type="button" onClick={() => aoMudar(null)} aria-label="Tirar o vídeo" title="Tirar o vídeo" className="flex size-8 shrink-0 items-center justify-center rounded-lg text-suave hover:bg-papel hover:text-erro">
            <X aria-hidden="true" className="size-4" />
          </button>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Botao variante="secundario" onClick={() => setEscolhendo(true)}>
              <Search aria-hidden="true" className="size-4" />
              Escolher no Vimeo
            </Botao>
            {!colando && <Botao variante="texto" onClick={() => setColando(true)}>Colar o link do vídeo</Botao>}
          </div>
          {colando && (
            <div className="flex flex-wrap items-start gap-2">
              <div className="min-w-48 flex-1">
                <label htmlFor={idDoLink} className="sr-only">Link ou número do vídeo no Vimeo</label>
                <input
                  id={idDoLink}
                  autoFocus
                  value={colado}
                  onChange={(e) => setColado(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      usarOColado();
                    }
                  }}
                  placeholder="https://vimeo.com/123456789"
                  className="campo"
                />
                {colado.trim() && !numero && <p className="mt-1 text-[13px] text-atencao">Não achei o número do vídeo nesse texto.</p>}
              </div>
              <Botao variante="secundario" disabled={!numero} onClick={usarOColado}>Usar</Botao>
              <Botao variante="texto" className="self-center" onClick={() => { setColando(false); setColado(""); }}>Cancelar</Botao>
            </div>
          )}
        </>
      )}
      {dica && <p className="text-[13px] text-suave">{dica}</p>}

      {escolhendo && (
        <ExploradorDoVimeo
          modo="um"
          titulo={tituloDaEscolha}
          legenda="Abra as pastas ou busque pelo nome. A busca feita de dentro de uma pasta mostra primeiro o que há nela."
          atual={video?.vimeo_id}
          aoEscolher={(v) => aoMudar({ vimeo_id: v.vimeo_id, titulo: v.titulo, caminho: v.caminho, embed_url: v.embed_url, thumbnail_url: v.thumbnail_url, duracao_segundos: v.duracao_segundos })}
          aoFechar={() => setEscolhendo(false)}
        />
      )}
      {video && (
        <Modal aberto={vendo} aoFechar={() => setVendo(false)} tamanho="grande" titulo={nome} legenda={video.caminho ?? undefined}>
          <TelaDoVideo video={{ vimeo_id: video.vimeo_id, titulo: nome, embed_url: video.embed_url }} />
        </Modal>
      )}
    </div>
  );
}
