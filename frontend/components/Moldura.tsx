import type { ReactNode } from "react";

/** A casca das páginas públicas (assinar, pagamento confirmado): só a marca e o conteúdo. */
export function Moldura({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center gap-2.5">
          <span className="flex size-9 items-center justify-center rounded-lg bg-acento font-bold text-white" aria-hidden="true">
            P
          </span>
          <span className="font-semibold text-tinta">Plataforma Educacional</span>
        </div>
        {children}
      </div>
    </main>
  );
}
