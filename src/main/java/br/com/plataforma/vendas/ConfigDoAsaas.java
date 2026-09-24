package br.com.plataforma.vendas;

import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Sem {@code ASAAS_API_KEY}, a venda roda com um Asaas de mentira — como o Zoom e o Vimeo. */
@Configuration
public class ConfigDoAsaas {

    @ConfigurationProperties("asaas")
    public record Chaves(String apiKey, String webhookToken) {}

    @Bean
    Asaas asaas(Chaves chaves) throws IOException {
        var chave = chaves.apiKey() == null ? "" : chaves.apiKey().strip();
        if (chave.isEmpty()) {
            LoggerFactory.getLogger(ConfigDoAsaas.class).warn("Asaas sem chave: a venda usa o Asaas de mentira");
            return new Asaas.DeMentira();
        }
        var base = Asaas.baseDaChave(chave);
        LoggerFactory.getLogger(ConfigDoAsaas.class).info("Asaas em {}", base);
        return new Asaas.Real(chave, base, new ClassPathResource("vendas/icone.png").getContentAsByteArray());
    }
}
