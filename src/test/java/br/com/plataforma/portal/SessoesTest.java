package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** O segredo da sessão: com o cookie Secure (produção), o portal não sobe com o de desenvolvimento. */
class SessoesTest {

    private static final String SEGREDO_DE_VERDADE = "k3Jq9vX2mT7pR4sW8yZ1aB6cD0eF5gH-um-segredo-longo";

    private static Sessoes sessoes(String segredo, boolean cookieSeguro) {
        return new Sessoes(new ConfigDoPortal(segredo, 12, cookieSeguro, false, List.of(), "", "../frontend/out"),
                new ObjectMapper());
    }

    @Test
    void comCookieSeguroOSegredoDeDesenvolvimentoImpedeAPartida() {
        assertThatThrownBy(() -> sessoes(ConfigDoPortal.SEGREDO_DE_DESENVOLVIMENTO, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void comCookieSeguroSegredoCurtoImpedeAPartida() {
        assertThatThrownBy(() -> sessoes("curtinho", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void segredoLongoSobeEODeDesenvolvimentoSoValeSemCookieSeguro() {
        assertThatCode(() -> sessoes(SEGREDO_DE_VERDADE, true)).doesNotThrowAnyException();
        assertThatCode(() -> sessoes(ConfigDoPortal.SEGREDO_DE_DESENVOLVIMENTO, false)).doesNotThrowAnyException();
    }

    /** Quem conhece o valor publicado no repositório não fabrica sessão num portal com segredo próprio. */
    @Test
    void tokenAssinadoComOSegredoDeDesenvolvimentoNaoValeComOutroSegredo() throws Exception {
        var agora = Instant.now();
        var base = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "."
                + b64("{\"sub\":\"1\",\"papel\":\"ADMIN\",\"iat\":%d,\"exp\":%d}"
                        .formatted(agora.getEpochSecond(), agora.plusSeconds(3600).getEpochSecond()));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ConfigDoPortal.SEGREDO_DE_DESENVOLVIMENTO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var forjado = base + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(base.getBytes(StandardCharsets.US_ASCII)));

        assertThat(sessoes(ConfigDoPortal.SEGREDO_DE_DESENVOLVIMENTO, false).ler(forjado, agora)).isPresent();
        assertThat(sessoes(SEGREDO_DE_VERDADE, true).ler(forjado, agora)).isEmpty();
    }

    private static String b64(String texto) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }
}
