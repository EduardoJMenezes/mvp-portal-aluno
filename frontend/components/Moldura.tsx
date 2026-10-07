import Image from "next/image";
import type { ReactNode } from "react";
import logo from "@/marca/logo.png";

/** A casca das páginas públicas (assinar, pagamento confirmado): só a marca e o conteúdo. */
export function Moldura({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-4 py-10">
      <div className="w-full max-w-md">
        <Image src={logo} alt="Rodrigo Melo" priority className="mb-6 h-14 w-auto" />
        {children}
      </div>
    </main>
  );
}
