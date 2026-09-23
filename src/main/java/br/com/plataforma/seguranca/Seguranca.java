package br.com.plataforma.seguranca;

import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.portal.ConfigDoPortal;
import br.com.plataforma.portal.FiltroDaSessao;
import br.com.plataforma.portal.Sessoes;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Quatro portas.
 *
 * <ol>
 *   <li>{@code /comandos/**}: o adaptador MCP, por token de serviço mais operador.
 *   <li>{@code /interno/**}: só o token de serviço; a credencial vai no corpo.
 *   <li>{@code /api/**}: o portal, por cookie de sessão ou token Bearer do MCP.
 *   <li>O resto é o portal estático, aberto — o que ele serve é HTML e JavaScript públicos.
 * </ol>
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
    SecurityFilterChain portal(HttpSecurity http, Sessoes sessoes, ContasServico contas, ConfigDoPortal config)
            throws Exception {
        return http.securityMatcher("/api/**")
                // CSRF é a origem conferida no filtro: cookie SameSite=Strict mais o header Origin.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(cors(config)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.cacheControl(c -> c.disable()))
                .addFilterBefore(new FiltroDaSessao(sessoes, contas, config), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/login", "/api/logout", "/api/sessao/config", "/api/demo/entrar",
                                "/api/saude", "/api/importacoes/**", "/api/zoom/webhook").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/api/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((pedido, resposta, erro) -> {
                    resposta.setStatus(401);
                    resposta.setContentType("application/json;charset=UTF-8");
                    resposta.getWriter().write("{\"detail\":\"Faça login para continuar.\"}");
                }))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain resto(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // O Cache-Control é do portal estático: _next/static é imutável, o HTML não envelhece.
                .headers(h -> h.cacheControl(c -> c.disable()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    private static CorsConfigurationSource cors(ConfigDoPortal config) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(config.corsOrigins());
        cors.setAllowCredentials(true);
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        var fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/api/**", cors);
        return fonte;
    }
}
