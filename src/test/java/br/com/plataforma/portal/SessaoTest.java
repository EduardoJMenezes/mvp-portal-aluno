package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.contas.Senhas;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Entrar, sair, trocar a senha, e as duas credenciais que abrem a API. */
class SessaoTest extends BaseDoPortal {

    @BeforeEach
    void senhas() {
        comSenhas();
    }

    @Test
    void loginAbreASessaoNumCookieEDevolveOPerfil() throws Exception {
        var cookie = login("bruno@teste.invalid", SENHA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nome").value("Aluno Bruno"))
                .andExpect(jsonPath("$.usuario.papel").value("ALUNO"))
                .andExpect(jsonPath("$.usuario.turmas[0]").value("Extensivo 2027"))
                .andExpect(jsonPath("$.usuario.trocar_senha").value(false))
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(cookie).startsWith("sessao=").contains("HttpOnly").contains("Path=/api").contains("SameSite=Strict");
    }

    @Test
    void senhaErradaEEmailInexistenteDaoAMesmaResposta() throws Exception {
        login("bruno@teste.invalid", "errada-errada")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha incorretos."));
        login("ninguem@teste.invalid", SENHA)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha incorretos."));
    }

    @Test
    void cincoFalhasTravamAConta() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("bruno@teste.invalid", "errada-errada").andExpect(status().isUnauthorized());
        }
        login("bruno@teste.invalid", SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void semCookieOuComCookieInvalidoNaoEntra() throws Exception {
        mvc.perform(get("/api/eu")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Faça login para continuar."));
        mvc.perform(get("/api/eu").cookie(new Cookie("sessao", "x.y.z"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sessão expirada. Entre de novo."));
        get("/api/eu", ALUNO).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("bruno@teste.invalid"));
    }

    @Test
    void logoutApagaOCookie() throws Exception {
        var cookie = mvc.perform(post("/api/logout")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saiu").value(true))
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("Max-Age=0");
    }

    @Test
    void senhaTemporariaSoAlcancaATroca() throws Exception {
        var novo = criarUsuario("Novo Aluno", "novo@teste.invalid", "temporaria-123", "ALUNO", true);
        get("/api/aluno/conteudo", novo).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Troque a senha temporária para continuar."));
        get("/api/eu", novo).andExpect(status().isOk()).andExpect(jsonPath("$.trocar_senha").value(true));

        post("/api/conta/senha", """
                {"senha_atual": "temporaria-123", "nova_senha": "minha-nova-senha-boa"}""", novo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.trocar_senha").value(false));
        get("/api/aluno/conteudo", novo).andExpect(status().isOk());
    }

    @Test
    void trocarASenhaDerrubaASessaoAntiga() throws Exception {
        var antiga = new Cookie("sessao", sessoes.criar(contas.buscar(ALUNO).orElseThrow(), Instant.now().minusSeconds(5)));

        mvc.perform(post("/api/conta/senha").cookie(antiga).contentType(APPLICATION_JSON).content("""
                {"senha_atual": "%s", "nova_senha": "outra-senha-forte-2028"}""".formatted(SENHA)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/eu").cookie(antiga)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("A senha mudou. Entre de novo."));
        login("bruno@teste.invalid", "outra-senha-forte-2028").andExpect(status().isOk());
    }

    @Test
    void senhaFracaOuIgualERecusada() throws Exception {
        post("/api/conta/senha", """
                {"senha_atual": "%s", "nova_senha": "1234567890"}""".formatted(SENHA), ALUNO)
                .andExpect(status().isBadRequest());
        post("/api/conta/senha", """
                {"senha_atual": "%s", "nova_senha": "%s"}""".formatted(SENHA, SENHA), ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A nova senha precisa ser diferente da atual."));
    }

    /** Duas trancas: o CORS do Spring barra a origem desconhecida, e o filtro confere o Host. */
    @Test
    void escritaVindaDeOutraOrigemERecusada() throws Exception {
        mvc.perform(post("/api/conta/senha").cookie(sessao(ALUNO)).header("Origin", "https://outro.exemplo")
                .contentType(APPLICATION_JSON).content("{\"senha_atual\": \"x\", \"nova_senha\": \"y\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/conta/senha").cookie(sessao(ALUNO)).header("Origin", "http://localhost:3000")
                .header("Host", "portal.exemplo")
                .contentType(APPLICATION_JSON).content("{\"senha_atual\": \"x\", \"nova_senha\": \"y\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void alunoNaoEntraNaAreaDoProfessor() throws Exception {
        get("/api/admin/turmas", ALUNO).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Área restrita a ADMIN/GERENCIADOR."));
        get("/api/admin/turmas", ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$[0].nome").value("Extensivo 2027"));
    }

    /**
     * O roteador casa a rota pelo caminho decodificado; o portão tem de olhar o mesmo caminho. Com a
     * URI crua, {@code /api/%61dmin/...} passava pelo teste de prefixo e caía no handler de admin.
     */
    @Test
    void caminhoCodificadoNaoFuraAAreaDoProfessor() throws Exception {
        for (var rota : List.of("/api/%61dmin/simulados", "/api/%61dmin/vimeo/videos", "/api/%61dmin/vimeo/pastas",
                "/api/%61dmin/assuntos", "/api/admi%6e/turmas", "/%61pi/admin/assuntos")) {
            var status = mvc.perform(MockMvcRequestBuilders.get(URI.create(rota)).cookie(sessao(ALUNO)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("aluno em %s", rota).isIn(401, 403, 404);
        }
        get("/api/admin/assuntos", ADMIN).andExpect(status().isOk());
        get("/api/admin/simulados", ADMIN).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(URI.create("/api/%61dmin/assuntos")).cookie(sessao(ADMIN)))
                .andExpect(status().isOk());
    }

    /**
     * A trava por endereço usa o IP que a borda informa (X-Real-IP), não o X-Forwarded-For, que o
     * cliente escreve à vontade: trocar o cabeçalho a cada tentativa não zera a contagem.
     */
    @Test
    void aTravaPorEnderecoNaoSeguraOQueOClienteEscreve() throws Exception {
        for (int i = 0; i < 20; i++) {
            loginDe("ninguem%d@teste.invalid".formatted(i), "errada-errada", "203.0.113.9", "198.51.100." + i)
                    .andExpect(status().isUnauthorized());
        }
        loginDe("bruno@teste.invalid", SENHA, "203.0.113.9", "198.51.100.200").andExpect(status().isTooManyRequests());
        loginDe("bruno@teste.invalid", SENHA, "203.0.113.10", "198.51.100.200").andExpect(status().isOk());
    }

    /** Endereço que não é um IP não vira chave: cai num balde só, e a falha da conta continua gravada. */
    @Test
    void enderecoMalformadoNaoDerrubaATravaDaConta() throws Exception {
        var estranho = "x".repeat(200);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/login").with(pedido -> {
                pedido.setRemoteAddr(estranho);
                return pedido;
            }).contentType(APPLICATION_JSON).content("{\"email\": \"bruno@teste.invalid\", \"senha\": \"errada-errada\"}"))
                    .andExpect(status().isUnauthorized());
        }
        login("bruno@teste.invalid", SENHA).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempts WHERE chave LIKE 'conta:%'", Integer.class))
                .isEqualTo(5);
    }

    private ResultActions loginDe(String email, String senha, String ipDaBorda, String encaminhado) throws Exception {
        return mvc.perform(post("/api/login").header("X-Real-IP", ipDaBorda).header("X-Forwarded-For", encaminhado)
                .contentType(APPLICATION_JSON).content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    /** O token do MCP abre a API — com o canal MCP, que não aprova nem emite token. */
    @Test
    void tokenDoMcpAbreAApiMasNaoPassaPorHumano() throws Exception {
        jdbc.update("INSERT INTO api_tokens (usuario_id, nome, token_hash, revogado) VALUES (?, 'Claude', ?, false)",
                ADMIN, Senhas.sha256("pvm_token-de-teste"));

        mvc.perform(get("/api/admin/turmas").header("Authorization", "Bearer pvm_token-de-teste"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/tokens").header("Authorization", "Bearer pvm_token-de-teste")
                .contentType(APPLICATION_JSON).content("{\"nome\": \"outro\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/turmas").header("Authorization", "Bearer pvm_invalido"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sessão expirada ou inválida."));
    }

    @Test
    void modoDemoEntraNasContasDeExemploSemSenha() throws Exception {
        criarUsuario("Professora Demo", "professora@escola.demo", "qualquer", "ADMIN", false);

        mvc.perform(get("/api/sessao/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modo_demo").value(true))
                .andExpect(jsonPath("$.contas_demo[0].email").value("professora@escola.demo"));
        mvc.perform(post("/api/demo/entrar").contentType(APPLICATION_JSON)
                .content("{\"email\": \"professora@escola.demo\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.usuario.papel").value("ADMIN"));
        mvc.perform(post("/api/demo/entrar").contentType(APPLICATION_JSON)
                .content("{\"email\": \"ana@teste.invalid\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void saudeSondaOBanco() throws Exception {
        mvc.perform(get("/api/saude")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.banco").value("ok"))
                .andExpect(jsonPath("$.vimeo").value("acervo-de-demonstracao"));
    }

    @Test
    void rotaDeApiQueNaoExisteVoltaJsonNaoHtml() throws Exception {
        get("/api/nada", ADMIN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Não encontrado."));
    }
}
