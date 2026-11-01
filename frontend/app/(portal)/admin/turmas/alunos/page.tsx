"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";

// Os alunos da turma moram na tela "Alunos", com o filtro da turma. Este endereço continua
// valendo para quem tinha o link guardado.

export default function AlunosDaTurma() {
  return (
    <Suspense>
      <ParaAlunos />
    </Suspense>
  );
}

function ParaAlunos() {
  const router = useRouter();
  const turma = useSearchParams().get("turma");
  useEffect(() => {
    router.replace(turma ? `/admin/alunos/?turma=${turma}` : "/admin/alunos/");
  }, [router, turma]);
  return null;
}
