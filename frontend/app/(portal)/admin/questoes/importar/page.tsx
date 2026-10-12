"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { MontarProva, faltaNaProva, paraApi, type EntradaDaProva } from "@/components/MontarProva";
import { Aviso, Botao, Cartao, Pagina, TituloDeSecao } from "@/components/ui";
import { api } from "@/lib/api";
import { plural } from "@/lib/formato";

/**
 * Várias questões de uma vez no Banco de questões, a partir de um .docx: o mesmo leitor e a mesma
 * lista de conferência do "Novo simulado", sem a prova em volta. Nada é gravado até o professor
 * importar a lista.
 */
export default function ImportarQuestoes() {
  const router = useRouter();
  const [questoes, setQuestoes] = useState<EntradaDaProva[]>([]);
  const [arquivo, setArquivo] = useState<string | null>(null);
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState<"rascunho" | "publicar" | null>(null);

  async function importar(publicar: boolean) {
    setErro("");
    if (!questoes.length) return setErro("A lista está vazia: importe um .docx ou escreva uma questão.");
    const falta = faltaNaProva(questoes);
    if (falta) return setErro(falta);
    setEnviando(publicar ? "publicar" : "rascunho");
    try {
      const r = await api.importarQuestoes(paraApi(questoes), arquivo, publicar);
      const resumo = new URLSearchParams({ importadas: String(r.questoes.length), publicadas: String(r.publicadas), pendentes: String(r.com_imagem_pendente) });
      // Segue para o Banco, onde elas aparecem em cima da lista.
      router.replace(`/admin/questoes/?${resumo}`);
    } catch (ex) {
      setErro((ex as Error).message);
      setEnviando(null);
    }
  }

  return (
    <Pagina
      titulo="Importar questões de um .docx"
      legenda="Para trazer várias questões de uma vez. O portal lê o documento, você confere a lista, e elas entram no Banco de questões."
      voltar={{ href: "/admin/questoes/", rotulo: "Banco de questões" }}
      estreita
    >
      <Cartao className="flex flex-col gap-4 p-5">
        <TituloDeSecao>Questões</TituloDeSecao>
        <p className="-mt-2 text-[13px] text-suave">
          O documento precisa ter as questões numeradas, as alternativas de a) a d) ou a e), e o gabarito de cada uma. Figura, fórmula e resolução comentada vêm junto. Assunto e
          comentário por alternativa não vêm do documento: preencha em “Editar”, aqui ou depois, no banco.
        </p>
        <MontarProva entradas={questoes} aoMudar={setQuestoes} permiteNova destino="banco" aoImportar={(lido) => setArquivo(lido.arquivo)} />
      </Cartao>

      {erro && <Aviso tom="erro">{erro}</Aviso>}
      <div className="flex flex-wrap items-center gap-2">
        <Botao variante="primario" ocupado={enviando === "rascunho"} disabled={!!enviando} onClick={() => importar(false)}>
          {enviando === "rascunho" ? "Importando…" : questoes.length ? `Importar ${plural(questoes.length, "questão", "questões")} em rascunho` : "Importar em rascunho"}
        </Botao>
        <Botao ocupado={enviando === "publicar"} disabled={!!enviando} onClick={() => importar(true)}>
          {enviando === "publicar" ? "Importando…" : "Importar e publicar"}
        </Botao>
      </div>
      <p className="text-[13px] text-suave">
        Em rascunho, cada questão espera você abrir, conferir e publicar no banco. “Importar e publicar” serve para quando a conferência já foi feita aqui: elas saem prontas para
        entrar em simulado e em aula. A que estiver com figura pendente fica em rascunho de qualquer jeito.
      </p>
    </Pagina>
  );
}
