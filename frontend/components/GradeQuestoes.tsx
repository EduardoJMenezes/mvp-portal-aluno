"use client";

export type EstadoDaQuestao = "respondida" | "branco" | "acertou" | "errou" | "sem_resposta";

const ESTILO: Record<EstadoDaQuestao, string> = {
  respondida: "border-acento bg-lilas text-acento-forte",
  branco: "border-borda bg-papel text-suave",
  acertou: "border-sucesso-borda bg-sucesso-fundo text-sucesso",
  errou: "border-erro-borda bg-erro-fundo text-erro",
  sem_resposta: "border-atencao-borda bg-atencao-fundo text-atencao",
};

const LEGENDA: Record<EstadoDaQuestao, string> = {
  respondida: "respondida",
  branco: "em branco",
  acertou: "acertou",
  errou: "errou",
  sem_resposta: "em branco",
};

/** A grade 1…N: na prova, respondida e em branco; no resultado, acerto, erro e branco. */
export function GradeQuestoes({
  estados,
  atual,
  aoEscolher,
  rotulo,
}: {
  estados: EstadoDaQuestao[];
  atual?: number;
  aoEscolher: (indice: number) => void;
  rotulo: string;
}) {
  return (
    <nav aria-label={rotulo}>
      <ol className="grid grid-cols-[repeat(auto-fill,minmax(2.5rem,1fr))] gap-1.5">
        {estados.map((estado, i) => (
          <li key={i}>
            <button
              type="button"
              onClick={() => aoEscolher(i)}
              aria-current={i === atual ? "step" : undefined}
              aria-label={`Questão ${i + 1}, ${LEGENDA[estado]}`}
              className={`flex h-10 w-full items-center justify-center rounded-md border text-sm font-semibold tabular-nums transition-shadow ${ESTILO[estado]} ${
                i === atual ? "ring-2 ring-acento ring-offset-2" : "hover:shadow-botao"
              }`}
            >
              {i + 1}
            </button>
          </li>
        ))}
      </ol>
    </nav>
  );
}
