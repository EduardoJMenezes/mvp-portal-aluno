package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.contas.Senhas;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
