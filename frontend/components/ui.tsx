"use client";

import { ArrowLeft, ChevronDown, ChevronRight, CircleCheck, CircleX, ExternalLink, Info, LoaderCircle, TriangleAlert, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { createContext, use, useCallback, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type FormEvent, type FormHTMLAttributes, type MouseEvent, type ReactNode, type Ref } from "react";
import { Modal } from "./Camadas";
import { Carregando, type FormaDoCarregamento } from "./Esqueleto";

export { Carregamento, Carregando, Esqueleto } from "./Esqueleto";

// --- botão -------------------------------------------------------------------
//
// O que é botão e o que pode continuar texto (docs/PADRAO-BOTOES.md):
//
//   faz alguma coisa (grava, abre, troca, tira)      <Botao>
//   só um ícone                                      <BotaoIcone>
//   leva a outra tela                                <BotaoLink>; no título de uma seção, <LinkDeSecao>
//   link no meio de uma frase                        <LinkNoTexto>   sempre sublinhado
//   o título que leva ao item                        <LinkDeTitulo>
//   mostrar e esconder                               <Revelar>       sempre com a setinha
//
// Palavra colorida solta na tela não é botão. E todo botão que dispara algo demorado se trava
// sozinho enquanto a ação roda: o segundo clique não faz nada.

type Variante = "primario" | "secundario" | "neutro" | "perigo" | "discreto";
/** `mini` é para a ação que mora dentro de uma linha: ao lado de um valor, numa tabela. */
type Tamanho = "normal" | "pequeno" | "mini";

const VARIANTES: Record<Variante, string> = {
  // Os estados do guia: azul vivo, mais escuro no hover, índigo ao pressionar, cinza-azulado desabilitado.
  primario: "rounded-campo bg-acento text-white shadow-botao hover:bg-acento-forte active:bg-tinta disabled:bg-apagado/60 disabled:shadow-none",
  secundario: "rounded-campo border border-borda text-acento bg-papel hover:border-acento hover:bg-lilas active:bg-gelo disabled:text-apagado disabled:hover:border-borda disabled:hover:bg-papel",
  neutro: "rounded-campo border border-borda text-tinta bg-papel hover:border-apagado hover:bg-canvas active:bg-gelo disabled:text-apagado disabled:hover:border-borda disabled:hover:bg-papel",
  perigo: "rounded-campo border border-erro-borda text-erro bg-papel hover:bg-erro-fundo active:bg-erro-fundo disabled:border-borda disabled:text-apagado disabled:hover:bg-papel",
  // Sem borda, para barra de ferramentas: a forma aparece ao passar o mouse.
  discreto: "rounded-campo text-tinta-2 hover:bg-lilas hover:text-acento-forte active:bg-gelo disabled:text-apagado disabled:hover:bg-transparent",
};

/** O botão afunda um pouco ao ser pressionado: é o aviso de que o clique chegou. */
const AFUNDA = "transition-[color,background-color,border-color,transform] duration-150 active:scale-[0.97] disabled:active:scale-100 motion-reduce:active:scale-100";

export function botao(variante: Variante = "neutro", tamanho: Tamanho = "normal") {
  const medida = tamanho === "mini" ? "px-2.5 py-1 text-[13px] gap-1.5" : tamanho === "pequeno" ? "px-3.5 py-1.5 text-sm" : "px-5 py-2.5 text-[15px]";
  return `inline-flex items-center justify-center gap-2 font-semibold whitespace-nowrap select-none disabled:cursor-not-allowed data-[ocupado]:cursor-progress ${AFUNDA} ${medida} ${VARIANTES[variante]}`;
}

/**
 * Uma ação de cada vez. Enquanto a primeira chamada não termina, as seguintes não fazem nada, nem
 * que cheguem no mesmo instante (a trava é uma referência, não espera a tela redesenhar).
 * Devolve a função travada e se ela está rodando.
 */
export function useAcao<A extends unknown[]>(acao: (...args: A) => unknown): [(...args: A) => void, boolean] {
  const trava = useRef(false);
  const [rodando, setRodando] = useState(false);
  const atual = useRef(acao);
  useEffect(() => {
    atual.current = acao;
  });

  const rodar = useCallback((...args: A) => {
    if (trava.current) return;
    const volta = atual.current(...args);
    if (!(volta instanceof Promise)) return;
    trava.current = true;
    setRodando(true);
    const soltar = () => {
      trava.current = false;
      setRodando(false);
    };
    // O erro de quem chamou continua sendo dele: aqui só se solta a trava.
    volta.then(soltar, (erro: unknown) => {
      soltar();
      throw erro;
    });
  }, []);
  return [rodar, rodando];
}

/**
 * O envio de um formulário, uma vez de cada vez: o Enter repetido ou o segundo clique em "Salvar"
 * não mandam de novo. Use o segundo valor em `<Botao type="submit" ocupado={...}>`.
 */
export function useEnvio(enviar: (e: FormEvent<HTMLFormElement>) => unknown): [(e: FormEvent<HTMLFormElement>) => void, boolean] {
  const [rodar, enviando] = useAcao(enviar);
  const aoEnviar = useCallback(
    (e: FormEvent<HTMLFormElement>) => {
      // Sempre: travado ou não, o navegador não pode enviar o formulário por conta própria.
      e.preventDefault();
      rodar(e);
    },
    [rodar],
  );
  return [aoEnviar, enviando];
}

type CliqueDoBotao = (e: MouseEvent<HTMLButtonElement>) => unknown;

/** O clique travado de um botão: o que `onClick` devolve decide se há o que esperar. */
function useClique(onClick: CliqueDoBotao | undefined, ocupadoDeFora: boolean) {
  const [rodar, rodando] = useAcao((e: MouseEvent<HTMLButtonElement>) => onClick?.(e));
  const ocupado = ocupadoDeFora || rodando;
  const clicar = (e: MouseEvent<HTMLButtonElement>) => {
    // Ocupado, o clique não faz nada: nem a ação de novo, nem o envio do formulário.
    if (ocupado) return e.preventDefault();
    rodar(e);
  };
  return { ocupado, clicar };
}

/** O que gira à esquerda do texto enquanto o botão trabalha. O texto fica: "Salvando…" diz o que está acontecendo. */
function Girando() {
  return <LoaderCircle aria-hidden="true" className="size-[1.15em] shrink-0 animate-spin" />;
}

/** Se o formulário em volta está sendo enviado: o botão de enviar de dentro dele fica ocupado sozinho. */
const Enviando = createContext(false);

/**
 * O `<form>` da casa: o envio roda uma vez de cada vez (`useEnvio`), e o `<Botao type="submit">`
 * de dentro gira enquanto o envio não termina, sem que a tela precise guardar um "salvando".
 */
export function Formulario({ onSubmit, children, ...resto }: Omit<FormHTMLAttributes<HTMLFormElement>, "onSubmit"> & { onSubmit: (e: FormEvent<HTMLFormElement>) => unknown; ref?: Ref<HTMLFormElement> }) {
  const [aoEnviar, enviando] = useEnvio(onSubmit);
  return (
    <form onSubmit={aoEnviar} aria-busy={enviando || undefined} {...resto}>
      <Enviando value={enviando}>{children}</Enviando>
    </form>
  );
}

type PropsDoBotao = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "onClick"> & {
  /** Se devolver uma promessa, o botão fica ocupado até ela terminar. */
  onClick?: CliqueDoBotao;
  /** Para o que o botão não vê começar, como o envio do formulário: `ocupado={salvando}`. */
  ocupado?: boolean;
  ref?: Ref<HTMLButtonElement>;
};

export function Botao({ variante = "neutro", tamanho = "normal", className = "", type = "button", ocupado: ocupadoDeFora = false, onClick, children, ...resto }: PropsDoBotao & { variante?: Variante; tamanho?: Tamanho }) {
  const enviando = use(Enviando) && type === "submit";
  const { ocupado, clicar } = useClique(onClick, ocupadoDeFora || enviando);
  return (
    <button type={type} className={`${botao(variante, tamanho)} ${className}`} onClick={clicar} aria-busy={ocupado || undefined} aria-disabled={ocupado || undefined} data-ocupado={ocupado ? "" : undefined} {...resto}>
      {ocupado && <Girando />}
      {children}
    </button>
  );
}

const ICONES: Record<"discreto" | "perigo", string> = {
  discreto: "text-suave hover:bg-lilas hover:text-acento-forte",
  perigo: "text-suave hover:bg-erro-fundo hover:text-erro",
};

/** O botão que é só um ícone (fechar, tirar, tocar). O `rotulo` é o nome dele para quem não vê o ícone, e a dica ao passar o mouse. */
export function BotaoIcone({ rotulo, icone: Icone, variante = "discreto", className = "", type = "button", ocupado: ocupadoDeFora = false, onClick, ...resto }: PropsDoBotao & { rotulo: string; icone: LucideIcon; variante?: "discreto" | "perigo" }) {
  const { ocupado, clicar } = useClique(onClick, ocupadoDeFora);
  return (
    <button
      type={type}
      aria-label={rotulo}
      title={rotulo}
      className={`flex size-8 shrink-0 items-center justify-center rounded-lg disabled:cursor-not-allowed disabled:text-apagado disabled:hover:bg-transparent data-[ocupado]:cursor-progress ${AFUNDA} ${ICONES[variante]} ${className}`}
      onClick={clicar}
      aria-busy={ocupado || undefined}
      aria-disabled={ocupado || undefined}
      data-ocupado={ocupado ? "" : undefined}
      {...resto}
    >
      {ocupado ? <LoaderCircle aria-hidden="true" className="size-4 animate-spin" /> : <Icone aria-hidden="true" className="size-4" />}
    </button>
  );
}

/** Endereço de fora da plataforma abre em outra aba, e o botão avisa com o ícone. */
const deFora = (href: string) => /^https?:\/\//.test(href);

/** A navegação com cara de botão. `outraAba` abre ao lado, sem tirar a pessoa de onde está. */
export function BotaoLink({ href, variante = "neutro", tamanho = "normal", outraAba = deFora(href), children, className = "" }: { href: string; variante?: Variante; tamanho?: Tamanho; outraAba?: boolean; children: ReactNode; className?: string }) {
  const conteudo = (
    <>
      {children}
      {outraAba && <ExternalLink aria-hidden="true" className="size-[1.05em] opacity-70" />}
      {outraAba && <span className="sr-only">(abre em outra aba)</span>}
    </>
  );
  const classes = `${botao(variante, tamanho)} ${className}`;
  if (deFora(href)) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className={classes}>
        {conteudo}
      </a>
    );
  }
  return (
    <Link href={href} target={outraAba ? "_blank" : undefined} className={classes}>
      {conteudo}
    </Link>
  );
}

/** "Ver todos", no título de uma seção: leva à lista inteira. */
export function LinkDeSecao({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className={`${botao("neutro", "pequeno")} pr-2.5`}>
      {children}
      <ChevronRight aria-hidden="true" className="size-4 text-suave" />
    </Link>
  );
}

/**
 * O link que faz parte de uma frase ("Cadastre em Assuntos"). Sempre sublinhado: é o sublinhado,
 * e não a cor, que diz que ali se clica. Dentro de um aviso, `herdaCor` mantém a cor do aviso.
 */
export function LinkNoTexto({ href, herdaCor = false, children }: { href: string; herdaCor?: boolean; children: ReactNode }) {
  const classes = `font-semibold underline decoration-current/40 underline-offset-2 hover:decoration-current ${herdaCor ? "" : "text-acento-forte"}`;
  if (deFora(href)) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className={classes}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} className={classes}>
      {children}
    </Link>
  );
}

/** O título que leva ao item (o nome do simulado, do PDF): é o texto da linha, não uma ação a mais. */
export function LinkDeTitulo({ href, children, className = "" }: { href: string; children: ReactNode; className?: string }) {
  return (
    <Link href={href} className={`font-semibold text-acento underline-offset-2 hover:text-acento-forte hover:underline ${className}`}>
      {children}
    </Link>
  );
}

/** Mostrar e esconder o que está logo abaixo. É texto, mas nunca sem a setinha, que vira ao abrir. */
export function Revelar({ aberto, aoAlternar, children, className = "", ...resto }: { aberto: boolean; aoAlternar: () => void; children: ReactNode; className?: string; "aria-controls"?: string }) {
  return (
    <button type="button" onClick={aoAlternar} aria-expanded={aberto} className={`inline-flex items-center gap-0.5 rounded-md text-sm font-semibold text-acento hover:text-acento-forte hover:underline ${className}`} {...resto}>
      {children}
      <ChevronDown aria-hidden="true" className={`size-4 transition-transform ${aberto ? "rotate-180" : ""}`} />
    </button>
  );
}

// --- etiqueta e aviso --------------------------------------------------------

export type Tom = "neutro" | "info" | "sucesso" | "atencao" | "erro";

const TONS: Record<Tom, string> = {
  neutro: "bg-canvas text-suave border-borda",
  info: "bg-lilas text-acento-forte border-lilas",
  sucesso: "bg-sucesso-fundo text-sucesso border-sucesso-fundo",
  atencao: "bg-atencao-fundo text-atencao border-atencao-fundo",
  erro: "bg-erro-fundo text-erro border-erro-fundo",
};

export function Etiqueta({ tom = "neutro", children, className = "" }: { tom?: Tom; children: ReactNode; className?: string }) {
  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap ${TONS[tom]} ${className}`}>
      {children}
    </span>
  );
}

// O ícone diz o tom antes da cor: quem não distingue verde de vermelho lê do mesmo jeito.
const ICONE_DO_AVISO: Record<Tom, [LucideIcon, string]> = {
  neutro: [Info, "text-suave"],
  info: [Info, "text-acento"],
  sucesso: [CircleCheck, "text-sucesso-vivo"],
  atencao: [TriangleAlert, "text-atencao-vivo"],
  erro: [CircleX, "text-erro-vivo"],
};

const BORDA_DO_AVISO: Record<Tom, string> = {
  neutro: "border-borda",
  info: "border-lilas",
  sucesso: "border-sucesso-borda",
  atencao: "border-atencao-borda",
  erro: "border-erro-borda",
};

export function Aviso({ tom = "info", titulo, children, className = "" }: { tom?: Tom; titulo?: string; children?: ReactNode; className?: string }) {
  const [Icone, cor] = ICONE_DO_AVISO[tom];
  return (
    <div role={tom === "erro" ? "alert" : "status"} className={`flex gap-3 rounded-xl border px-4 py-3 text-[15px] ${TONS[tom]} ${BORDA_DO_AVISO[tom]} ${className}`}>
      <Icone aria-hidden="true" className={`mt-0.5 size-5 shrink-0 ${cor}`} strokeWidth={2.2} />
      <div className="min-w-0 flex-1">
        {titulo && <p className="font-semibold">{titulo}</p>}
        {children && <div className={titulo ? "mt-0.5" : ""}>{children}</div>}
      </div>
    </div>
  );
}

// --- estrutura ---------------------------------------------------------------

export function Pagina({
  titulo,
  legenda,
  acoes,
  voltar,
  estreita = false,
  children,
}: {
  titulo: ReactNode;
  legenda?: ReactNode;
  acoes?: ReactNode;
  voltar?: { href: string; rotulo: string };
  estreita?: boolean;
  children: ReactNode;
}) {
  return (
    <main className={`mx-auto w-full px-4 pb-16 pt-6 sm:px-6 sm:pt-8 ${estreita ? "max-w-3xl" : "max-w-6xl"}`}>
      {voltar && (
        <Link href={voltar.href} className="mb-3 inline-flex items-center gap-1.5 text-sm font-medium text-suave hover:text-acento">
          <ArrowLeft aria-hidden="true" className="size-4" /> {voltar.rotulo}
        </Link>
      )}
      <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em] text-tinta sm:text-[34px]">{titulo}</h1>
          {legenda && <p className="mt-1.5 max-w-2xl text-[15px] text-suave">{legenda}</p>}
        </div>
        {acoes && <div className="flex flex-wrap items-center gap-2">{acoes}</div>}
      </header>
      <div className="flex flex-col gap-4">{children}</div>
    </main>
  );
}

export function Cartao({ children, className = "", como: Como = "section" }: { children: ReactNode; className?: string; como?: "section" | "div" | "article" | "li" }) {
  return <Como className={`rounded-cartao border border-borda/70 bg-papel shadow-suave ${className}`}>{children}</Como>;
}

export function TituloDeSecao({ children, acao }: { children: ReactNode; acao?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-xl font-bold tracking-[-0.01em] text-tinta">{children}</h2>
      {acao}
    </div>
  );
}

export function Vazio({ titulo, children }: { titulo: string; children?: ReactNode }) {
  return (
    <div className="rounded-cartao border border-dashed border-apagado/60 bg-papel/70 px-6 py-10 text-center">
      <p className="font-semibold text-tinta">{titulo}</p>
      {children && <div className="mx-auto mt-1 max-w-md text-[15px] text-suave">{children}</div>}
    </div>
  );
}

/** A barra de progresso do guia: trilho claro, preenchimento azul; completa, fica verde. */
export function Progresso({ feitos, total, rotulo, className = "" }: { feitos: number; total: number; rotulo: string; className?: string }) {
  const completo = total > 0 && feitos >= total;
  return (
    // É <span> para poder morar dentro de um link, como no cartão do módulo.
    <span
      role="progressbar"
      aria-label={rotulo}
      aria-valuemin={0}
      aria-valuemax={total}
      aria-valuenow={Math.min(feitos, total)}
      aria-valuetext={`${feitos} de ${total}`}
      className={`block h-1.5 overflow-hidden rounded-full bg-gelo ${className}`}
    >
      <span className={`block h-full rounded-full transition-[width] duration-500 ${completo ? "bg-sucesso-vivo" : "bg-acento"}`} style={{ width: `${total ? Math.min(100, (feitos / total) * 100) : 0}%` }} />
    </span>
  );
}

/**
 * Conteúdo com os três estados de uma busca: carregando, erro e pronto.
 * `forma`: o formato do esqueleto mostrado enquanto os dados não chegam (ver Esqueleto.tsx).
 * `esqueleto`: um desenho sob medida, quando nenhum formato pronto parece com a tela.
 */
export function Estado<T>({
  carregando,
  erro,
  dados,
  children,
  linhas,
  forma,
  esqueleto,
}: {
  carregando: boolean;
  erro: string;
  dados: T | null;
  children: (dados: T) => ReactNode;
  linhas?: number;
  forma?: FormaDoCarregamento;
  esqueleto?: ReactNode;
}) {
  if (erro) return <Aviso tom="erro" titulo="Não deu para carregar">{erro}</Aviso>;
  if (carregando && dados === null) return esqueleto ?? <Carregando linhas={linhas} forma={forma} />;
  if (dados === null) return null;
  return <>{children(dados)}</>;
}

// --- formulário --------------------------------------------------------------

export function Campo({ rotulo, dica, children, className = "" }: { rotulo: string; dica?: ReactNode; children: (id: string) => ReactNode; className?: string }) {
  const id = useId();
  return (
    <div className={`flex flex-col gap-1.5 ${className}`}>
      <label htmlFor={id} className="text-sm font-semibold text-tinta-2">
        {rotulo}
      </label>
      {children(id)}
      {dica && <p className="text-[13px] text-suave">{dica}</p>}
    </div>
  );
}

export function Abas<T extends string>({ abas, atual, aoTrocar }: { abas: { valor: T; rotulo: string }[]; atual: T; aoTrocar: (valor: T) => void }) {
  return (
    <div role="tablist" className="flex gap-1 overflow-x-auto overflow-y-hidden border-b border-borda">
      {abas.map((aba) => (
        <button
          key={aba.valor}
          type="button"
          role="tab"
          aria-selected={aba.valor === atual}
          onClick={() => aoTrocar(aba.valor)}
          className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-[15px] font-semibold transition-colors ${
            aba.valor === atual ? "border-acento text-acento" : "border-transparent text-suave hover:text-tinta"
          }`}
        >
          {aba.rotulo}
        </button>
      ))}
    </div>
  );
}

// --- confirmação -------------------------------------------------------------

type PedidoDeConfirmacao = { titulo: string; texto?: ReactNode; confirmar: string; perigo?: boolean };

/** `confirmar()` abre um modal pequeno e devolve a escolha. */
export function useConfirmar(): [ReactNode, (pedido: PedidoDeConfirmacao) => Promise<boolean>] {
  // O pedido fica guardado depois de respondido: o modal ainda o mostra enquanto sai da tela.
  const [pedido, setPedido] = useState<PedidoDeConfirmacao | null>(null);
  const [aberto, setAberto] = useState(false);
  const resolver = useRef<(sim: boolean) => void>(() => {});

  const fechar = useCallback((sim: boolean) => {
    setAberto(false);
    resolver.current(sim);
  }, []);

  const confirmar = useCallback((novo: PedidoDeConfirmacao) => {
    setPedido(novo);
    setAberto(true);
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const elemento = (
    <Modal
      aberto={aberto}
      aoFechar={() => fechar(false)}
      titulo={pedido?.titulo}
      tamanho="pequeno"
      rodape={
        <div className="flex flex-wrap justify-end gap-2">
          <Botao onClick={() => fechar(false)} data-foco-inicial>
            Cancelar
          </Botao>
          <Botao variante={pedido?.perigo ? "perigo" : "primario"} onClick={() => fechar(true)}>
            {pedido?.confirmar}
          </Botao>
        </div>
      }
    >
      {pedido?.texto && <div className="text-[15px] text-tinta-2">{pedido.texto}</div>}
    </Modal>
  );

  return [elemento, confirmar];
}

// --- segredo mostrado uma vez --------------------------------------------------

export function SegredoUmaVez({ titulo, valor, children }: { titulo: string; valor: string; children?: ReactNode }) {
  const [copiado, setCopiado] = useState(false);
  return (
    <Aviso tom="atencao" titulo={titulo}>
      {children}
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <code className="select-all break-all rounded-campo border border-atencao-borda bg-papel px-2 py-1 font-mono text-[15px] text-tinta">{valor}</code>
        <Botao
          tamanho="pequeno"
          onClick={async () => {
            await navigator.clipboard?.writeText(valor);
            setCopiado(true);
          }}
        >
          {copiado ? "Copiado" : "Copiar"}
        </Botao>
      </div>
    </Aviso>
  );
}
