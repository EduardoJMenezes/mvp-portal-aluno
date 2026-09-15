"use client";

import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, type Usuario } from "./api";

// Quem entrou e para onde cada papel pode ir. Esconder rota na tela não é
// segurança — o backend recusa o que não é da pessoa —, mas mandar cada um
// para a própria área evita tela de erro no caminho normal.

type ValorDaSessao = {
  usuario: Usuario | null;
  entrou: (usuario: Usuario) => void;
  sair: () => Promise<void>;
};

const Contexto = createContext<ValorDaSessao | null>(null);

const normalizar = (caminho: string) => (caminho.length > 1 ? caminho.replace(/\/+$/, "") : caminho);
const PUBLICAS = ["/entrar", "/enviar"];
const ehPublica = (caminho: string) => PUBLICAS.some((p) => caminho === p || caminho.startsWith(`${p}/`));
export const ehOperador = (usuario: Usuario) => usuario.papel !== "ALUNO";
const DO_ALUNO = ["/curso", "/simulados", "/desempenho"];
const ehDoAluno = (caminho: string) => caminho === "/" || DO_ALUNO.some((p) => caminho === p || caminho.startsWith(`${p}/`));

export const inicioDe = (usuario: Usuario) => (ehOperador(usuario) ? "/admin/" : "/");

export function Sessao({ children }: { children: ReactNode }) {
  const caminho = normalizar(usePathname() ?? "/");
  const router = useRouter();
  const [usuario, setUsuario] = useState<Usuario | null>(null);
  const [conferido, setConferido] = useState(false);

  // Uma conferência por carga de página; login e saída atualizam pelo contexto.
  useEffect(() => {
    let vivo = true;
    api
      .eu()
      .then((u) => vivo && setUsuario(u))
      .catch(() => undefined)
      .finally(() => vivo && setConferido(true));
    return () => {
      vivo = false;
    };
  }, []);

  const destino = useMemo(() => {
    if (!conferido || ehPublica(caminho)) return null;
    if (!usuario) {
      const busca = typeof window === "undefined" ? "" : window.location.search;
      return `/entrar/?volta=${encodeURIComponent(caminho + busca)}`;
    }
    if (usuario.trocar_senha && caminho !== "/conta") return "/conta/?trocar=1";
    if (caminho.startsWith("/admin") && !ehOperador(usuario)) return "/";
    if (ehOperador(usuario) && ehDoAluno(caminho)) return "/admin/";
    return null;
  }, [conferido, caminho, usuario]);

  useEffect(() => {
    if (destino) router.replace(destino);
  }, [destino, router]);

  const sair = useCallback(async () => {
    await api.sair().catch(() => undefined);
    setUsuario(null);
    router.replace("/entrar/");
  }, [router]);

  const valor = useMemo(() => ({ usuario, entrou: setUsuario, sair }), [usuario, sair]);
  const esperando = !ehPublica(caminho) && (!conferido || !usuario || destino !== null);

  return <Contexto.Provider value={valor}>{esperando ? <TelaDeEspera /> : children}</Contexto.Provider>;
}

function TelaDeEspera() {
  return (
    <div className="flex min-h-dvh items-center justify-center" aria-busy="true">
      <p className="flex items-center gap-2 text-[15px] text-suave">
        <span className="size-2 animate-pulse rounded-full bg-acento" aria-hidden="true" />
        Carregando…
      </p>
    </div>
  );
}

export function useSessao() {
  const valor = useContext(Contexto);
  if (!valor) throw new Error("useSessao fora de <Sessao>");
  return valor;
}

/** Nas telas protegidas o usuário existe: a sessão só as mostra depois de conferir. */
export function useUsuario(): Usuario {
  const { usuario } = useSessao();
  if (!usuario) throw new Error("tela protegida sem usuário");
  return usuario;
}
