"use client";

// Questões entrando num sub-módulo. Quem cadastra está com a apostila aberta ao lado e vai de
// questão em questão: cola um trecho do enunciado, reconhece a questão pelo começo dela, confere
// se precisar e adiciona; a que não existe, escreve. O painel fica aberto entre uma e outra, e o
// nome da próxima linha ("Q05") anda sozinho.

import { Plus } from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { BancoDeQuestoes } from "@/components/BancoDeQuestoes";
import { OffCanvas, useSaida } from "@/components/Camadas";
import { Botao, BotaoLink } from "@/components/ui";
import { api, type Assunto, type ItemCurso, type Modulo, type Questao, type SubModulo } from "@/lib/api";
import { plural } from "@/lib/formato";
import { Azulejo, type Executar } from "./comum";

/** O nome que continua a numeração das questões do sub-módulo: depois de "Q04" vem "Q05". */
export function proximoNome(nome: string | undefined): string {
  const partes = nome && /^(.*?)(\d+)$/.exec(nome.trim());
  if (!partes) return "";
  return partes[1] + String(Number(partes[2]) + 1).padStart(partes[2].length, "0");
}
const ultimaQuestao = (itens: ItemCurso[]) => itens.filter((i) => i.questao).at(-1)?.nome;

export function AdicionarQuestao({ modulo, sub, assuntos, executar, aoFechar }: { modulo: Modulo; sub: SubModulo; assuntos: Assunto[]; executar: Executar; aoFechar: () => void }) {
  const saida = useSaida(aoFechar);
  const jaNaAula = useMemo(() => new Set(sub.itens.map((i) => i.questao?.questao_id).filter((id): id is number => id !== undefined)), [sub.itens]);
  const quantas = jaNaAula.size;

  const campo = useRef<HTMLInputElement>(null);

  // --- adicionar ---
  // O nome da próxima linha segue a numeração do sub-módulo até o professor escrever outro.
  const sugerido = proximoNome(ultimaQuestao(sub.itens));
  const [nomeEscrito, setNomeEscrito] = useState<string | null>(null);
  const nome = (nomeEscrito ?? sugerido).trim();
  const [entraram, setEntraram] = useState<Record<number, string>>({});

  async function adicionar(q: Questao) {
    let linha = nome;
    const ok = await executar(
      async () => {
        linha = (await api.questaoNoSubmodulo(sub.id, { questao_id: q.questao_id, nome: nome || undefined })).nome;
      },
      () => `Questão #${q.questao_id} entrou em ${sub.nome} como ${linha}, já publicada.`,
    );
    if (!ok) return;
    setEntraram((atual) => ({ ...atual, [q.questao_id]: linha }));
    setNomeEscrito(null);
    // A próxima busca começa por cima da anterior: é só colar o trecho seguinte.
    campo.current?.focus();
    campo.current?.select();
  }

  const destino = encodeURIComponent(`${modulo.nome} › ${sub.nome}`);
  const escrever = `/admin/questoes/editar/?submodulo=${sub.id}&modulo=${modulo.id}&destino=${destino}${nome ? `&nome=${encodeURIComponent(nome)}` : ""}`;
  const feitas = Object.keys(entraram).length;

  return (
    <OffCanvas
      {...saida}
      aoFechar={saida.fechar}
      lado="direita"
      tamanho="grande"
      semMargem
      titulo={
        <span className="flex items-center gap-3">
          <Azulejo tipo="questao" />
          Adicionar questão
        </span>
      }
      legenda={`Em ${sub.nome}, de ${modulo.nome}. Entra publicada: o aluno responde uma vez e vê o gabarito e a resolução na hora.`}
      rodape={
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <label className="flex items-center gap-2.5 text-sm text-tinta-2">
            <span>
              <span className="block font-semibold">A próxima entra como</span>
              <span className="block text-[13px] text-suave">{quantas ? `${plural(quantas, "questão", "questões")} em ${sub.nome}${feitas ? `, ${feitas} de agora` : ""}` : "É a primeira questão daqui"}</span>
            </span>
            <input value={nomeEscrito ?? sugerido} onChange={(e) => setNomeEscrito(e.target.value)} maxLength={120} placeholder="Questão N" aria-label="Nome da próxima linha" className="campo w-32" />
          </label>
          <Botao onClick={saida.fechar}>Fechar</Botao>
        </div>
      }
    >
      <BancoDeQuestoes
        jaEsta={jaNaAula}
        marcaDeJaEsta={(q) => (entraram[q.questao_id] ? `Entrou como ${entraram[q.questao_id]}` : "Já está aqui")}
        aoEscolher={adicionar}
        assuntos={assuntos}
        campoRef={campo}
        acao={
          <BotaoLink variante="primario" href={escrever}>
            <Plus aria-hidden="true" className="size-4" />
            Escrever uma nova
          </BotaoLink>
        }
        noVazio={<BotaoLink variante="secundario" href={escrever}>Escrever esta questão{nome ? ` (${nome})` : ""}</BotaoLink>}
      />
    </OffCanvas>
  );
}
