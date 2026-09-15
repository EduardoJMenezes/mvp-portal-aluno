import Link from "next/link";

export default function NaoEncontrado() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center px-6 text-center">
      <p className="font-mono text-sm text-suave">404</p>
      <h1 className="mt-2 text-2xl font-semibold text-tinta">Esta página não existe</h1>
      <p className="mt-2 text-[15px] text-suave">O endereço pode ter mudado. Volte ao início e siga pelo menu.</p>
      <Link href="/" className="mt-6 inline-flex rounded-full bg-acento px-5 py-2.5 font-semibold text-white shadow-botao hover:bg-acento-forte">
        Ir para o início
      </Link>
    </main>
  );
}
