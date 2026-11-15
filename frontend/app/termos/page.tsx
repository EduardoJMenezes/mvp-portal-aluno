import type { ReactNode } from "react";
import { Moldura } from "@/components/Moldura";

// Os termos que o aluno aceita na página de assinar. Mudou o texto, mude a versão aqui E em
// VendasServico.TERMOS_VERSAO: cada pedido guarda a versão que o aluno aceitou.
const VERSAO = "2026-11-15";

export const metadata = { title: "Termos de matrícula" };

export default function Termos() {
  return (
    <Moldura>
      <article className="rounded-cartao border border-borda bg-papel p-6 shadow-suave sm:p-7">
        <h1 className="text-2xl font-semibold text-tinta">Termos de matrícula</h1>
        <p className="mt-1 text-[13px] text-suave">Versão {VERSAO}</p>
        <p className="mt-4 rounded-lg border border-borda bg-canvas px-3 py-2 text-[13px] text-tinta-2">
          Texto provisório. Ele será revisado antes da abertura das matrículas; o que estiver entre colchetes ainda
          será definido.
        </p>

        <Secao titulo="1. O que você está comprando">
          Acesso ao curso online do professor Rodrigo Melo, vendido por [razão social e CNPJ], nas turmas indicadas na
          página de matrícula, até a data informada nela. Quem se matricula depois do início do curso tem acesso a
          todas as aulas já gravadas.
        </Secao>

        <Secao titulo="2. Valores e pagamento">
          A matrícula é cobrada no início, à parte do curso. O curso pode ser pago à vista no Pix ou parcelado no
          cartão de crédito, sem juros, nos valores mostrados na página no dia da matrícula. A partir de abril, o
          valor do curso é proporcional aos meses que faltam até novembro. Os pagamentos são processados pelo Asaas:
          os dados do cartão são digitados na página dele e não passam pela plataforma.
        </Secao>

        <Secao titulo="3. Material">
          A matrícula inclui o material impresso, com frete grátis para todo o Brasil: a apostila, enviada depois da
          confirmação da matrícula (quem se matricula antes do início do curso recebe em janeiro), e a camisa com o
          material de revisão, enviados em agosto. A apostila também fica disponível na versão digital, na área de
          materiais da plataforma, sem custo adicional. O material impresso vai para o endereço informado na
          matrícula; mantê-lo correto é responsabilidade do aluno.
        </Secao>

        <Secao titulo="4. Garantia de 7 dias">
          Se você desistir em até 7 dias depois do seu primeiro acesso, devolvemos tudo o que você pagou, curso e
          matrícula. [Definir o que acontece com o material que já tiver sido enviado.] Esse prazo também cumpre o
          direito de arrependimento do Código de Defesa do Consumidor.
        </Secao>

        <Secao titulo="5. Cancelamento depois da garantia">[Definir as regras de cancelamento e de reembolso depois dos 7 dias.]</Secao>

        <Secao titulo="6. Revisão">
          Quem é matriculado no extensivo participa da revisão, a partir de setembro, sem pagamento adicional.
        </Secao>

        <Secao titulo="7. Uso pessoal">
          O acesso é individual. Não é permitido compartilhar o login nem copiar ou distribuir as aulas e os
          materiais. O descumprimento pode levar à suspensão do acesso.
        </Secao>

        <Secao titulo="8. Seus dados">
          Usamos nome, e-mail, CPF, celular e endereço para a matrícula, a cobrança e a entrega do material. Eles são
          compartilhados só com o processador de pagamento (Asaas) e com quem faz a entrega, conforme a Lei Geral de
          Proteção de Dados. Para pedir acesso, correção ou exclusão dos seus dados, fale com [contato].
        </Secao>

        <Secao titulo="9. Contato">[WhatsApp e e-mail de atendimento.]</Secao>
      </article>
    </Moldura>
  );
}

function Secao({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <section className="mt-5">
      <h2 className="text-[15px] font-semibold text-tinta">{titulo}</h2>
      <p className="mt-1 text-[15px] leading-relaxed text-tinta-2">{children}</p>
    </section>
  );
}
