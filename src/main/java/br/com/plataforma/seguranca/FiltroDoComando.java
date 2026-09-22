package br.com.plataforma.seguranca;

import static java.nio.charset.StandardCharsets.UTF_8;

import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Quem está pedindo um comando, conferido nesta ordem:
 *
 * <ol>
 *   <li>{@code X-Servico} bate com o segredo — prova que veio do adaptador MCP, e não de alguém
 *       na rede interna;
 *   <li>{@code X-Operador} é o id de um usuário que existe;
 *   <li>o papel desse usuário, <b>relido do banco agora</b>, é de operador. É isto que impede o
 *       deputado confuso: o Java não aceita papel vindo de cabeçalho, e quem foi rebaixado perde
 *       o acesso no comando seguinte;
 *   <li>o canal é fixo: {@link Canal#MCP}. Não existe cabeçalho para o chamador escolher — então
 *       não há como o MCP se passar pelo portal e escapar do {@code exigirHumanoNoPortal}.
 * </ol>
 *
 * <p>Recusa responde só o status. O motivo vai para o log: quem erra aqui ou configurou mal o
 * deploy ou está sondando, e em nenhum dos dois casos a resposta deve dizer se o id existe.
 *
 * <p>Não é {@code @Component} de propósito: o Spring Boot registraria o filtro para todas as
 * rotas, fora da cadeia de segurança. Ele nasce dentro de {@link Seguranca}.
 */
class FiltroDoComando extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroDoComando.class);

    private final byte[] tokenDeServico;
    private final ContasServico contas;

    FiltroDoComando(ConfigDaPlataforma config, ContasServico contas) {
        this.tokenDeServico = config.tokenDeServico().getBytes(UTF_8);
        this.contas = contas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        var servico = pedido.getHeader("X-Servico");
        if (servico == null || !MessageDigest.isEqual(servico.getBytes(UTF_8), tokenDeServico)) {
            recusar(resposta, HttpServletResponse.SC_UNAUTHORIZED, "X-Servico ausente ou errado");
            return;
        }

        Optional<Usuario> usuario = idDoOperador(pedido.getHeader("X-Operador")).flatMap(contas::buscar);
        if (usuario.isEmpty()) {
            recusar(resposta, HttpServletResponse.SC_UNAUTHORIZED, "X-Operador ausente, inválido ou inexistente");
            return;
        }

        var u = usuario.get();
        if (!u.getPapel().eOperador()) {
            recusar(resposta, HttpServletResponse.SC_FORBIDDEN,
                    "usuário %d tem papel %s, não opera".formatted(u.getId(), u.getPapel()));
            return;
        }

        var ident = new Identidade(u.getId(), u.getNome(), u.getEmail(), u.getPapel(), Canal.MCP);
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ident, null, List.of()));
        SecurityContextHolder.setContext(contexto);
        cadeia.doFilter(pedido, resposta);
    }

    private static Optional<Integer> idDoOperador(String cabecalho) {
        if (cabecalho == null) {
            return Optional.empty();
        }
        try {
            var id = Integer.parseInt(cabecalho.strip());
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void recusar(HttpServletResponse resposta, int status, String motivo) {
        log.warn("comando recusado com {}: {}", status, motivo);
        resposta.setStatus(status);
    }
}
