import { iconeDoModulo } from "@/lib/icones";

// A capa do módulo: a foto que o professor subiu, o ícone que ele escolheu ou, sem nenhum dos
// dois, o ícone que o nome do capítulo sugere.

const LADO = { normal: "size-14 rounded-2xl", pequeno: "size-10 rounded-xl" };

export function CapaDoModulo({
  modulo,
  tamanho = "normal",
  className = "",
}: {
  modulo: { id: number; nome: string; icone?: string | null; foto_versao?: number | null };
  tamanho?: keyof typeof LADO;
  className?: string;
}) {
  if (modulo.foto_versao) {
    // A versão no endereço deixa o navegador guardar a foto para sempre: foto nova, endereço novo.
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={`/api/aluno/modulos/${modulo.id}/foto?v=${modulo.foto_versao}`}
        alt=""
        width={56}
        height={56}
        loading="lazy"
        decoding="async"
        className={`${LADO[tamanho]} shrink-0 bg-gelo/60 object-cover ${className}`}
      />
    );
  }
  const Icone = iconeDoModulo(modulo);
  return (
    <span aria-hidden="true" className={`flex ${LADO[tamanho]} shrink-0 items-center justify-center bg-gelo/60 text-acento ${className}`}>
      <Icone className={tamanho === "pequeno" ? "size-5" : "size-7"} strokeWidth={1.6} />
    </span>
  );
}
