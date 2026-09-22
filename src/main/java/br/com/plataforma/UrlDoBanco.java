package br.com.plataforma;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Aceita a URL do banco como o Railway (e o Heroku) a entregam: {@code postgresql://user:senha@host:porta/db}.
 *
 * <p>O JDBC não entende usuário e senha dentro da URL, e pedir três variáveis onde a plataforma dá
 * uma é convite a erro de deploy. Se {@code SPRING_DATASOURCE_URL} estiver definida, ela vence.
 */
public class UrlDoBanco implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
        var url = ambiente.getProperty("DATABASE_URL");
        if (url == null || url.isBlank() || ambiente.containsProperty("SPRING_DATASOURCE_URL")) {
            return;
        }
        var texto = url.strip().replaceFirst("^postgres(ql)?(\\+\\w+)?://", "postgresql://");
        var uri = URI.create(texto);
        var propriedades = new LinkedHashMap<String, Object>();
        var porta = uri.getPort() == -1 ? 5432 : uri.getPort();
        var caminho = uri.getPath() == null ? "" : uri.getPath();
        propriedades.put("spring.datasource.url",
                "jdbc:postgresql://" + uri.getHost() + ":" + porta + caminho
                        + (uri.getQuery() == null ? "" : "?" + uri.getQuery()));
        if (uri.getUserInfo() != null) {
            var partes = uri.getUserInfo().split(":", 2);
            propriedades.put("spring.datasource.username", URLDecoder.decode(partes[0], StandardCharsets.UTF_8));
            if (partes.length > 1) {
                propriedades.put("spring.datasource.password", URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
            }
        }
        ambiente.getPropertySources().addFirst(new MapPropertySource("DATABASE_URL", propriedades));
    }
}
