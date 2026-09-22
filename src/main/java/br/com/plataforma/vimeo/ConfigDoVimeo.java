package br.com.plataforma.vimeo;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sem token, o acervo de demonstração embutido: a POC roda ponta a ponta antes de existir credencial. */
@Configuration
public class ConfigDoVimeo {

    @ConfigurationProperties("vimeo")
    public record Chaves(String accessToken, String apiBase) {}

    @Bean
    Vimeo vimeo(Chaves chaves) {
        if (chaves.accessToken() == null || chaves.accessToken().isBlank()) {
            LoggerFactory.getLogger(ConfigDoVimeo.class).warn("Vimeo sem token: usando o acervo de demonstração");
            return new VimeoDemo();
        }
        return new VimeoReal(chaves.accessToken().strip(),
                chaves.apiBase() == null || chaves.apiBase().isBlank() ? "https://api.vimeo.com" : chaves.apiBase());
    }
}
