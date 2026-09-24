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
        var comprador = new Asaas.Comprador("Aluna Teste Portal", "aluna.teste.portal@example.com", "52998224725",
                "81999990000", "53030260", "100");

        for (var tipo : Plano.Tipo.values()) {
            var checkout = asaas.criarCheckout(new Asaas.NovoCheckout("[TESTE] Extensivo 2026 — plano de teste",
                    "Acesso a Extensivo 2026", 19700, tipo, tipo == Plano.Tipo.UNICO ? 12 : 1, comprador,
                    "teste-" + tipo, "https://app-production-e5b7.up.railway.app/assinar/pronto/?pedido=teste",
                    "https://app-production-e5b7.up.railway.app/assinar/teste"));
            System.out.println("CHECKOUT_" + tipo + "=" + checkout.link());
            assertThat(checkout.link()).startsWith("https://sandbox.asaas.com/");
        }
    }
}
