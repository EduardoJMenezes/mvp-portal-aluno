package br.com.plataforma.portal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Headers que valem para toda resposta: portal, API e comandos. */
@Configuration
class CabecalhosDeSeguranca {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> cabecalhos(ConfigDoPortal config) {
        var filtro = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta,
                    FilterChain cadeia) throws ServletException, IOException {
                resposta.setHeader("X-Content-Type-Options", "nosniff");
                resposta.setHeader("X-Frame-Options", "DENY");
                resposta.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                resposta.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
                if (config.cookieSeguro()) {
                    resposta.setHeader("Strict-Transport-Security", "max-age=31536000");
                }
                cadeia.doFilter(pedido, resposta);
            }
        };
        var registro = new FilterRegistrationBean<OncePerRequestFilter>(filtro);
        registro.setOrder(Integer.MIN_VALUE);
        return registro;
    }
}
