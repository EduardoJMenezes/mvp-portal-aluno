package br.com.plataforma.portal;

import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identidade de quem chega pela API do portal.
 *
 * <p>Duas credenciais, a mesma porta:
 *
 * <ul>
 *   <li><b>Cookie {@code sessao}</b> — o portal no navegador. httpOnly (o JavaScript não lê),
 *       SameSite=Strict (outro site não o faz viajar) e só em {@code /api}.
 *   <li><b>{@code Authorization: Bearer}</b> — o token do MCP (Claude Code, scripts) ou uma sessão
 *       do portal em JWT, como os testes usam.
 * </ul>
 *
 * <p>O token do MCP abre a API com o canal MCP, não o do portal: é assim que a sessão do Claude
 * Code envia a figura recortada de uma questão, e continua barrada onde a regra pede um humano no
 * navegador — aprovar e descartar rascunho, cadastrar aluno, redefinir senha, emitir token.
 */
public class FiltroDaSessao extends OncePerRequestFilter {

    public static final String COOKIE = "sessao";
    static final Set<String> PUBLICAS = Set.of(
            "/api/login", "/api/logout", "/api/sessao/config", "/api/demo/entrar", "/api/saude",
            // o Zoom não tem sessão: quem prova a origem é a assinatura, conferida no WebhookDoZoom
            "/api/zoom/webhook",
            // o Asaas prova a origem pelo token do webhook, conferido no WebhookDoAsaas
            "/api/asaas/webhook");
    /** Com senha temporária, a sessão só alcança o que leva a trocá-la. */
    static final Set<String> LIVRES_COM_SENHA_TEMPORARIA = Set.of("/api/eu", "/api/conta/senha", "/api/logout");
    private static final Set<String> METODOS_SEGUROS = Set.of("GET", "HEAD", "OPTIONS");

    private final Sessoes sessoes;
    private final ContasServico contas;
    private final ConfigDoPortal config;

    public FiltroDaSessao(Sessoes sessoes, ContasServico contas, ConfigDoPortal config) {
        this.sessoes = sessoes;
        this.contas = contas;
        this.config = config;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest pedido) {
        var caminho = pedido.getRequestURI();
        // O link de envio é a própria credencial: a página abre sem sessão (EnvioProxy).
        return !caminho.startsWith("/api/") || PUBLICAS.contains(caminho) || caminho.startsWith("/api/importacoes/")
                || caminho.startsWith("/api/vendas/")
                || "OPTIONS".equalsIgnoreCase(pedido.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        var autorizacao = pedido.getHeader("Authorization");
        String valor = null;
        var peloCookie = false;
        if (autorizacao != null && autorizacao.regionMatches(true, 0, "bearer ", 0, 7)) {
            valor = autorizacao.substring(7).strip();
        } else if (pedido.getCookies() != null) {
            for (var c : pedido.getCookies()) {
                if (COOKIE.equals(c.getName())) {
                    valor = c.getValue();
                    peloCookie = true;
                }
            }
        }
        if (valor == null || valor.isEmpty()) {
            Respostas.erro(resposta, 401, "Faça login para continuar.");
            return;
        }

        var agora = Instant.now();
        var dados = sessoes.ler(valor, agora);
        Identidade ident;
        if (dados.isEmpty()) {
            if (peloCookie) {
                Respostas.erro(resposta, 401, "Sessão expirada. Entre de novo.");
                return;
            }
            var operador = contas.operadorPorToken(valor, agora);
            if (operador.isEmpty()) {
                Respostas.erro(resposta, 401, "Sessão expirada ou inválida.");
                return;
            }
            ident = identidade(operador.get(), Canal.MCP);
        } else {
            if (peloCookie && !METODOS_SEGUROS.contains(pedido.getMethod()) && !origemConfiavel(pedido)) {
                Respostas.erro(resposta, 403, "Pedido de outra origem recusado.");
                return;
            }
            var usuario = contas.buscar(dados.get().usuarioId()).orElse(null);
            if (usuario == null) {
                Respostas.erro(resposta, 401, "Esta conta não existe mais.");
                return;
            }
            if (usuario.getSenhaAlteradaEm() != null
                    && dados.get().emitidaEm().getEpochSecond() < usuario.getSenhaAlteradaEm().getEpochSecond()) {
                Respostas.erro(resposta, 401, "A senha mudou. Entre de novo.");
                return;
            }
            if (usuario.isSenhaTemporaria() && !LIVRES_COM_SENHA_TEMPORARIA.contains(pedido.getRequestURI())) {
                Respostas.erro(resposta, 403, "Troque a senha temporária para continuar.");
                return;
            }
            ident = identidade(usuario, Canal.PORTAL);
        }

        if (pedido.getRequestURI().startsWith("/api/admin/") && !ident.eOperador()) {
            Respostas.erro(resposta, 403, "Área restrita a ADMIN/GERENCIADOR.");
            return;
        }

        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ident, null, List.of()));
        SecurityContextHolder.setContext(contexto);
        cadeia.doFilter(pedido, resposta);
    }

    private static Identidade identidade(Usuario u, Canal canal) {
        return new Identidade(u.getId(), u.getNome(), u.getEmail(), u.getPapel(), canal);
    }

    /**
     * Escrita vinda do cookie precisa partir deste site. O SameSite=Strict já barra o cookie em
     * pedido de outro site; conferir a origem é a segunda tranca.
     */
    private boolean origemConfiavel(HttpServletRequest pedido) {
        var origem = pedido.getHeader("Origin");
        if (origem == null || origem.isBlank()) {
            return true; // navegador não manda Origin em todo pedido do mesmo site
        }
        try {
            var host = URI.create(origem).getAuthority();
            return host != null && host.equalsIgnoreCase(pedido.getHeader("Host"))
                    || config.corsOrigins().contains(origem);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
