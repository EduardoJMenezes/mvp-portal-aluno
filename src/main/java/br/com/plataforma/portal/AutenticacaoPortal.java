package br.com.plataforma.portal;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.MuitasTentativas;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.vimeo.Vimeo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessão do portal: entrar, sair, trocar a senha e o modo demonstração.
 *
 * <p>A senha nunca volta e o token da sessão nunca chega ao JavaScript: o login responde só o
 * perfil e grava a sessão num cookie httpOnly.
 */
@RestController
@RequestMapping("/api")
public class AutenticacaoPortal {

    private static final Logger log = LoggerFactory.getLogger("plataforma");

    private final ContasServico contas;
    private final Sessoes sessoes;
    private final ConfigDoPortal config;
    private final JdbcTemplate jdbc;
    private final Vimeo vimeo;

    public AutenticacaoPortal(ContasServico contas, Sessoes sessoes, ConfigDoPortal config, JdbcTemplate jdbc,
            Vimeo vimeo) {
        this.contas = contas;
        this.sessoes = sessoes;
        this.config = config;
        this.jdbc = jdbc;
        this.vimeo = vimeo;
    }

    public record LoginIn(@NotBlank @Email String email, @NotBlank @Size(max = 200) String senha) {}

    public record TrocaDeSenhaIn(@NotBlank @Size(max = 200) String senhaAtual, @NotBlank @Size(max = 200) String novaSenha) {}

    public record DemoIn(@NotBlank @Size(max = 180) String email) {}

    void abrirSessao(HttpServletResponse resposta, Usuario usuario) {
        resposta.addHeader(HttpHeaders.SET_COOKIE, cookie(sessoes.criar(usuario, Instant.now()),
                sessoes.validade().toSeconds()).toString());
    }

    private ResponseCookie cookie(String valor, long maxAge) {
        return ResponseCookie.from(FiltroDaSessao.COOKIE, valor)
                .httpOnly(true).secure(config.cookieSeguro()).sameSite("Strict").path("/api").maxAge(maxAge)
                .build();
    }

    /** Atrás do proxy do Railway, o Tomcat lê o X-Forwarded-For: este é o IP de quem pediu. */
    static String ip(HttpServletRequest pedido) {
        var ip = pedido.getRemoteAddr();
        return ip == null || ip.isBlank() ? "desconhecido" : ip;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginIn dados, HttpServletRequest pedido,
            HttpServletResponse resposta) {
        Usuario usuario;
        try {
            usuario = contas.entrar(dados.email(), dados.senha(), ip(pedido), Instant.now());
        } catch (MuitasTentativas e) {
            log.warn("login travado por excesso de tentativas (ip={})", ip(pedido));
            throw e;
        }
        abrirSessao(resposta, usuario);
        log.info("login do usuário {} ({})", usuario.getId(), usuario.getPapel());
        return Map.of("usuario", contas.perfil(usuario));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse resposta) {
        resposta.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        return Map.of("saiu", true);
    }

    @GetMapping("/eu")
    @Transactional(readOnly = true)
    public ContasServico.Perfil eu(@AuthenticationPrincipal Identidade ident) {
        return contas.perfil(contas.buscar(ident.usuarioId())
                .orElseThrow(() -> new NaoEncontrado("Esta conta não existe mais.")));
    }

    /** Troca a senha e renova a sessão: as outras sessões desta conta caem. */
    @PostMapping("/conta/senha")
    @Transactional
    public Map<String, Object> trocarSenha(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody TrocaDeSenhaIn dados, HttpServletResponse resposta) {
        var usuario = contas.trocarSenha(ident, dados.senhaAtual(), dados.novaSenha(), Instant.now());
        abrirSessao(resposta, usuario);
        log.info("senha trocada pelo usuário {}", usuario.getId());
        return Map.of("usuario", contas.perfil(usuario));
    }

    /** O que a tela de login precisa saber antes de alguém entrar. */
    @GetMapping("/sessao/config")
    @Transactional(readOnly = true)
    public Map<String, Object> configuracao() {
        var saida = new LinkedHashMap<String, Object>();
        saida.put("modo_demo", config.modoDemo());
        saida.put("contas_demo", config.modoDemo() ? contas.contasDemo() : List.of());
        return saida;
    }

    @PostMapping("/demo/entrar")
    @Transactional(readOnly = true)
    public Map<String, Object> entrarComoDemo(@Valid @RequestBody DemoIn dados, HttpServletRequest pedido,
            HttpServletResponse resposta) {
        if (!config.modoDemo()) {
            throw new NaoEncontrado("Não encontrado.");
        }
        var usuario = contas.entrarComoDemo(dados.email());
        abrirSessao(resposta, usuario);
        log.warn("modo demonstração: entrada sem senha como {} (ip={})", usuario.getEmail(), ip(pedido));
        return Map.of("usuario", contas.perfil(usuario));
    }

    /**
     * O Railway usa este caminho para aprovar um deploy. Sonda o banco: sem isso, um datasource
     * errado passa no healthcheck e só aparece como erro na tela de login.
     */
    @GetMapping("/saude")
    public ResponseEntity<Map<String, Object>> saude() {
        var banco = "ok";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("healthcheck: banco indisponível: {}", e.getMessage());
            banco = "indisponivel";
        }
        var saida = new LinkedHashMap<String, Object>();
        saida.put("ok", banco.equals("ok"));
        saida.put("banco", banco);
        saida.put("vimeo", vimeo.real() ? "api-real" : "acervo-de-demonstracao");
        saida.put("mcp", config.mcpBaseUrl().isBlank() ? null : config.mcpBaseUrl());
        saida.put("mcp_oauth", "github");
        saida.put("modo_demo", config.modoDemo());
        return ResponseEntity.status(banco.equals("ok") ? 200 : 503).body(saida);
    }
}
