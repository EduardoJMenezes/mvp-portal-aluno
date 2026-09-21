package br.com.plataforma.seguranca;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A porta interna: só o token de serviço, sem operador.
 *
 * <p>Existe porque aqui o {@code X-Operador} não tem o que dizer. Em {@code /interno/operador} e
 * {@code /interno/token}, é justamente o operador que a chamada <b>descobre</b> — exigir o id
 * dele seria pedir a resposta antes da pergunta. Nas rotas do link de envio, quem está do outro
 * lado é o professor num navegador, sem sessão: um id no cabeçalho ali seria invenção do
 * adaptador, e a identidade que vale é a do dono do link, que o próprio registro carrega.
 *
 * <p>Por isso o que mora atrás desta porta carrega a <b>própria</b> credencial — o login, o token
 * Bearer, o token do link —, e é o domínio que a confere. O token de serviço prova só de onde o
 * pedido veio. Nada aqui altera estado sem uma dessas credenciais ser validada antes.
 */
class FiltroDoServico extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroDoServico.class);

    private final byte[] tokenDeServico;

    FiltroDoServico(ConfigDaPlataforma config) {
        this.tokenDeServico = config.tokenDeServico().getBytes(UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        var servico = pedido.getHeader("X-Servico");
        if (servico == null || !MessageDigest.isEqual(servico.getBytes(UTF_8), tokenDeServico)) {
            log.warn("porta interna recusada: X-Servico ausente ou errado");
            resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("adaptador", null, List.of()));
        SecurityContextHolder.setContext(contexto);
        cadeia.doFilter(pedido, resposta);
    }
}
