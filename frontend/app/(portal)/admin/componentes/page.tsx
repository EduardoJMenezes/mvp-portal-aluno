"use client";

// A vitrine dos componentes da casa: o Modal e o OffCanvas em todos os tamanhos e lados, e o
// carregamento em todas as formas. Não está no menu; abre por /admin/componentes/.

import { useState } from "react";
import { Modal, OffCanvas, type LadoDoOffCanvas, type TamanhoDaCamada } from "@/components/Camadas";
import { Botao, Campo, Carregando, Cartao, Esqueleto, Pagina, TituloDeSecao } from "@/components/ui";
import type { FormaDoCarregamento } from "@/components/Esqueleto";

const TAMANHOS: TamanhoDaCamada[] = ["pequeno", "medio", "grande", "tela"];
const LADOS: LadoDoOffCanvas[] = ["direita", "esquerda", "baixo", "cima"];
const FORMAS: FormaDoCarregamento[] = ["lista", "cartoes", "tabela", "texto", "blocos"];

type Aberta = { tipo: "modal"; tamanho: TamanhoDaCamada } | { tipo: "offcanvas"; lado: LadoDoOffCanvas; tamanho: TamanhoDaCamada } | null;

export default function VitrineDasCamadas() {
  // A última camada aberta continua desenhada enquanto sai da tela.
  const [camada, setCamada] = useState<Aberta>(null);
  const [aberta, setAberta] = useState(false);
  const [tamanho, setTamanho] = useState<TamanhoDaCamada>("medio");
  const [forma, setForma] = useState<FormaDoCarregamento>("lista");

  const abrir = (nova: NonNullable<Aberta>) => {
    setCamada(nova);
    setAberta(true);
  };
  const fechar = () => setAberta(false);

  const conteudo = (
    <div className="flex flex-col gap-3 text-[15px] text-tinta-2">
      <p>O título fica preso em cima e os botões, embaixo. O meio rola quando o conteúdo não cabe.</p>
      <Campo rotulo="Um campo, para ver o foco preso aqui dentro">{(id) => <input id={id} className="campo" placeholder="Tab e Shift+Tab não saem da camada" />}</Campo>
      <p>Esc, o X e o clique fora pedem para fechar. Ao fechar, o foco volta para o botão que abriu.</p>
      {Array.from({ length: 8 }, (_, i) => (
        <p key={i} className="rounded-campo border border-borda bg-canvas px-3 py-2 text-suave">Linha {i + 1} de conteúdo</p>
      ))}
    </div>
  );
  const rodape = (
    <div className="flex justify-end gap-2">
      <Botao onClick={fechar}>Cancelar</Botao>
      <Botao variante="primario" onClick={fechar}>Salvar</Botao>
    </div>
  );

  return (
    <Pagina titulo="Componentes" legenda="As peças que a plataforma inteira reaproveita: as camadas que abrem por cima da página e o carregamento.">
      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Tamanho</TituloDeSecao>
        <div className="flex flex-wrap gap-2">
          {TAMANHOS.map((t) => (
            <Botao key={t} tamanho="pequeno" variante={t === tamanho ? "primario" : "neutro"} aria-pressed={t === tamanho} onClick={() => setTamanho(t)}>
              {t}
            </Botao>
          ))}
        </div>
      </Cartao>

      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Modal</TituloDeSecao>
        <p className="-mt-2 text-[15px] text-suave">No meio da tela, para uma pergunta ou uma tarefa curta. É o que a confirmação de remover já usa.</p>
        <div>
          <Botao variante="secundario" onClick={() => abrir({ tipo: "modal", tamanho })}>Abrir modal {tamanho}</Botao>
        </div>
      </Cartao>

      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Off-canvas</TituloDeSecao>
        <p className="-mt-2 text-[15px] text-suave">Preso a uma borda, com a página à vista atrás: para escolher ou editar sem sair de onde se está. É o que &quot;Adicionar vídeos&quot; usa, da direita.</p>
        <div className="flex flex-wrap gap-2">
          {LADOS.map((lado) => (
            <Botao key={lado} variante="secundario" onClick={() => abrir({ tipo: "offcanvas", lado, tamanho })}>Da {lado === "baixo" || lado === "cima" ? `parte de ${lado}` : lado}</Botao>
          ))}
        </div>
      </Cartao>

      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Carregamento</TituloDeSecao>
        <p className="-mt-2 text-[15px] text-suave">Enquanto os dados não chegam, a tela mostra blocos no formato do que vem. Cada tela escolhe a forma mais parecida com ela, ou monta a sua com o bloco solto.</p>
        <div className="flex flex-wrap gap-2">
          {FORMAS.map((f) => (
            <Botao key={f} tamanho="pequeno" variante={f === forma ? "primario" : "neutro"} aria-pressed={f === forma} onClick={() => setForma(f)}>
              {f}
            </Botao>
          ))}
        </div>
        <Carregando forma={forma} linhas={forma === "cartoes" ? 3 : 4} />
        <p className="text-[13px] font-semibold text-tinta-2">O bloco solto, para um formato sob medida</p>
        <div className="flex items-center gap-3">
          <Esqueleto className="size-12 rounded-full" />
          <div className="flex flex-1 flex-col gap-2">
            <Esqueleto className="h-4 w-48" />
            <Esqueleto className="h-3 w-72 max-w-full" />
          </div>
          <Esqueleto className="h-9 w-28 rounded-campo" />
        </div>
      </Cartao>

      <Modal aberto={aberta && camada?.tipo === "modal"} aoFechar={fechar} tamanho={camada?.tamanho} titulo={`Modal ${camada?.tamanho ?? ""}`} legenda="A legenda explica o que vai acontecer." rodape={rodape}>
        {conteudo}
      </Modal>
      <OffCanvas
        aberto={aberta && camada?.tipo === "offcanvas"}
        aoFechar={fechar}
        lado={camada?.tipo === "offcanvas" ? camada.lado : undefined}
        tamanho={camada?.tamanho}
        titulo={camada?.tipo === "offcanvas" ? `Off-canvas da ${camada.lado}, ${camada.tamanho}` : ""}
        legenda="A legenda explica o que vai acontecer."
        rodape={rodape}
      >
        {conteudo}
      </OffCanvas>
    </Pagina>
  );
}
