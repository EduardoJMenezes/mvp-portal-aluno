"use client";

import Link from "next/link";
import { Cartao, Estado, Etiqueta, Pagina, Vazio } from "@/components/ui";
import { api, useDados } from "@/lib/api";
import { plural, tamanhoDoArquivo } from "@/lib/formato";

export default function MateriaisDoAluno() {
  const lista = useDados(() => api.materiais());

  return (
    <Pagina titulo="Materiais" legenda="Apostilas e listas da sua turma. Pode riscar por cima: o que você marcar fica salvo na sua conta.">
      <Estado {...lista} linhas={3}>
        {(materiais) =>
          materiais.length === 0 ? (
            <Vazio titulo="Nenhum material por aqui ainda">Quando o professor publicar uma apostila, ela aparece nesta tela.</Vazio>
          ) : (
            <ul className="grid gap-3 sm:grid-cols-2">
              {materiais.map((m) => (
                <Cartao key={m.material_id} como="li" className="transition-shadow hover:shadow-suave">
                  <Link href={`/materiais/ler/?id=${m.material_id}`} className="flex h-full flex-col gap-2 p-5">
                    <span className="flex flex-wrap items-center gap-2">
                      {m.paginas_anotadas ? (
                        <Etiqueta tom="info">{plural(m.paginas_anotadas, "página riscada", "páginas riscadas")}</Etiqueta>
                      ) : (
                        <Etiqueta>Sem marcações</Etiqueta>
                      )}
                    </span>
                    <span className="text-lg font-semibold text-tinta">{m.titulo}</span>
                    <span className="mt-auto text-[13px] text-suave">{tamanhoDoArquivo(m.tamanho)}</span>
                  </Link>
                </Cartao>
              ))}
            </ul>
          )
        }
      </Estado>
    </Pagina>
  );
}
