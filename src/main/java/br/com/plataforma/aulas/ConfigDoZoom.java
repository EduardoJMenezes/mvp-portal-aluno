package br.com.plataforma.aulas;

import org.slf4j.LoggerFactory;
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
    public record Chaves(String accountId, String clientId, String clientSecret, String host, String apiBase) {

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
}
