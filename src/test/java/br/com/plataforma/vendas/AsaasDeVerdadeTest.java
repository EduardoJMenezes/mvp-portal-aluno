package br.com.plataforma.vendas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

/**
 * O cliente contra o Asaas de verdade — desligado, a menos que {@code ASAAS_TESTE_REAL=sim}. Só roda
 * com chave de sandbox: abre checkouts de teste, que expiram sozinhos em uma hora.
 *
 * <pre>railway run --service app -- bash -c 'unset DATABASE_URL; ASAAS_TESTE_REAL=sim ./mvnw -q test -Dtest=AsaasDeVerdadeTest'</pre>
 */
@EnabledIfEnvironmentVariable(named = "ASAAS_TESTE_REAL", matches = "sim")
class AsaasDeVerdadeTest {

    @Test
    void abreCheckoutMensalEUnicoNaSandbox() throws Exception {
        var chave = System.getenv("ASAAS_API_KEY");
        assertThat(chave).as("este teste só roda com chave de sandbox").startsWith("$aact_hmlg_");
        var asaas = new Asaas.Real(chave, Asaas.baseDaChave(chave),
                new ClassPathResource("vendas/icone.png").getContentAsByteArray());
        var endereco = asaas.endereco("53030260");
        assertThat(endereco.cidade()).isNotBlank();
        var comprador = new Asaas.Comprador("Aluna Teste Portal", "aluna.teste.portal@example.com", "52998224725",
                "81999990000", "53030260", "100", endereco.rua(), "apto 1", endereco.bairro());
        var matricula = new Asaas.Item("Matrícula", "[TESTE] Matrícula", 33000);
        var curso = new Asaas.Item("[TESTE] Extensivo 2027", "Acesso a Extensivo 2027", 191880);

        // Cada jeito que a venda usa: mensal com 12 cobranças, Pix com matrícula junto, matrícula em 3x, curso em 12x.
        for (var forma : Asaas.Forma.values()) {
            var itens = switch (forma) {
                case PIX -> java.util.List.of(matricula, curso);
                case PIX_OU_CARTAO -> java.util.List.of(matricula);
                default -> java.util.List.of(curso);
            };
            var parcelas = switch (forma) {
                case CARTAO -> 12;
                case PIX_OU_CARTAO -> 3;
                default -> 1;
            };
            var checkout = asaas.criarCheckout(new Asaas.NovoCheckout(itens, forma, parcelas,
                    forma == Asaas.Forma.MENSAL ? 12 : null, comprador, "teste-" + forma,
                    "https://app-production-e5b7.up.railway.app/assinar/pronto/?pedido=teste",
                    "https://app-production-e5b7.up.railway.app/assinar/teste"));
            System.out.println("CHECKOUT_" + forma + "=" + checkout.link());
            assertThat(checkout.link()).startsWith("https://sandbox.asaas.com/");
        }
    }
}
