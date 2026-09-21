package br.com.plataforma.seguranca;

import br.com.plataforma.contas.ContasServico;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Duas portas. A de comandos, para o adaptador MCP; e o resto, fechado.
 *
 * <p>O portal do aluno ganha a cadeia dele quando for portado — até lá, nada além de
 * {@code /actuator/health} responde fora de {@code /comandos}.
 */
@Configuration
class Seguranca {

    @Bean
    @Order(1)
    SecurityFilterChain comandos(HttpSecurity http, ConfigDaPlataforma config, ContasServico contas)
            throws Exception {
        return http.securityMatcher("/comandos/**")
                // Sem sessão e sem cookie: CSRF não se aplica a quem se identifica por cabeçalho.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new FiltroDoComando(config, contas), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain interno(HttpSecurity http, ConfigDaPlataforma config) throws Exception {
        return http.securityMatcher("/interno/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new FiltroDoServico(config), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain resto(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a
                        // /error liberado para um 500 não virar 403 no caminho.
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
