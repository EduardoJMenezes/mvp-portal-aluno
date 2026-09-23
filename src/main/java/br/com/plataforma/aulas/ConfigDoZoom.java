package br.com.plataforma.aulas;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Zoom das aulas ao vivo (app Server-to-Server OAuth). Sem as quatro variáveis, o backend usa um
 * Zoom de mentira e a POC roda inteira assim. {@code host} é o e-mail do usuário do Zoom que
 * hospeda as aulas — a conta é dividida com outra plataforma, então esse endereço importa.
 */
@Configuration
public class ConfigDoZoom {

    @ConfigurationProperties("zoom")
    public record Chaves(String accountId, String clientId, String clientSecret, String host, String apiBase,
            String webhookSecret, boolean webhookSincrono) {

        boolean completa() {
            return preenchido(accountId) && preenchido(clientId) && preenchido(clientSecret) && preenchido(host);
        }

        private static boolean preenchido(String v) {
            return v != null && !v.isBlank();
        }
    }

    @Bean
    Zoom zoom(Chaves chaves) {
        if (!chaves.completa()) {
            LoggerFactory.getLogger(ConfigDoZoom.class)
                    .warn("Zoom sem credencial: as aulas ao vivo usam o Zoom de mentira");
            return new ZoomDeMentira();
        }
        return new ZoomReal(chaves.accountId(), chaves.clientId(), chaves.clientSecret(), chaves.host(),
                chaves.apiBase() == null || chaves.apiBase().isBlank() ? ZoomReal.BASE : chaves.apiBase());
    }

    /** Sem token do Vimeo, a gravação "sobe" para um Vimeo de mentira — o resto do cano roda igual. */
    @Bean
    EnvioAoVimeo envioAoVimeo(@Value("${vimeo.access-token:}") String token,
            @Value("${vimeo.api-base:https://api.vimeo.com}") String base) {
        return token == null || token.isBlank() ? new EnvioAoVimeo.DeMentira()
                : new EnvioAoVimeo.Real(token.strip(), base);
    }

    /**
     * O Zoom quer 200 em 3 segundos e reenvia o aviso se não receber: o trabalho do aviso (subir ao
     * Vimeo) roda fora do pedido. Nos testes, na hora, para dar para conferir o resultado. Tipo
     * próprio: um bean {@code Executor} solto desligaria o executor padrão do Spring.
     */
    record TrabalhoDoZoom(Executor executor) {}

    @Bean
    TrabalhoDoZoom trabalhoDoZoom(Chaves chaves) {
        return new TrabalhoDoZoom(chaves.webhookSincrono() ? Runnable::run : Executors.newVirtualThreadPerTaskExecutor());
    }
}
