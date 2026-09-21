package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/**
 * A fatia vertical de ponta a ponta: HTTP → filtro → comando → serviço → Postgres real.
 *
 * <p>Cada teste prova uma afirmação do docs/PADRAO-JAVA.md. Se uma delas for falsa, é aqui que
 * ela quebra — e não em produção.
 */
class CriarModuloTest extends BaseDeComando {

    private static String corpo(String turma, String nome) {
        return """
                {"turma": "%s", "nome": "%s"}""".formatted(turma, nome);
    }

    // --- o comando -----------------------------------------------------------

    @Test
    void criaOModuloComOsDoisSubmodulosDeSempre() throws Exception {
        comando("criar_modulo", """
                {"turma": "extensivo", "nome": "  K01 - Introdução à química orgânica  "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modulo_id").isNumber())
                .andExpect(jsonPath("$.modulo").value("K01 - Introdução à química orgânica"))
                .andExpect(jsonPath("$.turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$.submodulos[0]").value("Aulas"))
                .andExpect(jsonPath("$.submodulos[1]").value("Questões da apostila"));

        assertThat(jdbc.queryForObject("SELECT alterado_por_id FROM modules", Integer.class)).isEqualTo(ADMIN);
        assertThat(contar("submodules")).isEqualTo(2);
    }

    @Test
    void nomeRepetidoNaMesmaTurmaVoltaComAMensagemInteira() throws Exception {
        comando("criar_modulo", corpo("Extensivo 2027", "K01")).andExpect(status().isOk());

        comando("criar_modulo", corpo("Extensivo 2027", "k01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'Extensivo 2027' já tem um módulo chamado 'K01'."));
    }

    /** @SQLRestriction esconde o removido da checagem; o índice parcial deixa o banco aceitar. */
    @Test
    void moduloRemovidoLiberaONome() throws Exception {
        comando("criar_modulo", corpo("Extensivo 2027", "K01")).andExpect(status().isOk());
        jdbc.update("UPDATE modules SET removido_em = now()");

        comando("criar_modulo", corpo("Extensivo 2027", "K01")).andExpect(status().isOk());

        assertThat(contar("modules WHERE nome = 'K01'")).isEqualTo(2);
    }

    @Test
    void turmaQueNaoExisteListaAsQueExistem() throws Exception {
        comando("criar_modulo", corpo("Semi 2030", "K01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value("Turma 'Semi 2030' não existe. Turmas: Extensivo 2027, Intensivo 2027."));
    }

    /** A afirmação central do híbrido: um comando é uma transação, tudo ou nada. */
    @Test
    void subModuloRecusadoDesfazOModuloJunto() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K02", "submodulos": ["Aulas", "aulas"]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'K02' já tem um sub-módulo chamado 'Aulas'."));

        assertThat(contar("modules")).isZero();
        assertThat(contar("submodules")).isZero();
    }

    @Test
    void pedidoSemTurmaDizQualCampoFalta() throws Exception {
        comando("criar_modulo", """
                {"nome": "K01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Pedido incompleto: 'turma' é obrigatória: o nome ou o id da turma."));
    }

    // --- quem pode pedir -----------------------------------------------------

    @Test
    void semTokenDeServicoNemEntra() throws Exception {
        mvc.perform(post("/comandos/criar_modulo").contentType(APPLICATION_JSON)
                        .content(corpo("Extensivo 2027", "K01")).header("X-Operador", ADMIN))
                .andExpect(status().isUnauthorized());
        assertThat(contar("modules")).isZero();
    }

    @Test
    void tokenDeServicoErradoNemEntra() throws Exception {
        mvc.perform(post("/comandos/criar_modulo").contentType(APPLICATION_JSON)
                        .content(corpo("Extensivo 2027", "K01"))
                        .header("X-Servico", TOKEN + "x").header("X-Operador", ADMIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alunoNaoOperaMesmoComOTokenCerto() throws Exception {
        comando("criar_modulo", corpo("Extensivo 2027", "K01"), ALUNO).andExpect(status().isForbidden());
        assertThat(contar("modules")).isZero();
    }

    @Test
    void operadorQueNaoExisteNaoEntra() throws Exception {
        comando("criar_modulo", corpo("Extensivo 2027", "K01"), 999).andExpect(status().isUnauthorized());
    }

    /** O deputado confuso: o papel vem do banco a cada comando, nunca de cabeçalho nem de cache. */
    @Test
    void operadorRebaixadoPerdeOAcessoNoComandoSeguinte() throws Exception {
        comando("criar_modulo", corpo("Extensivo 2027", "K01")).andExpect(status().isOk());
        jdbc.update("UPDATE users SET papel = 'ALUNO' WHERE id = ?", ADMIN);

        comando("criar_modulo", corpo("Extensivo 2027", "K02")).andExpect(status().isForbidden());
    }

    // --- a porta interna ------------------------------------------------------

    /** Ela descobre quem é o operador, então não pode exigir o id dele. */
    @Test
    void aPortaInternaTrabalhaSoComOTokenDeServico() throws Exception {
        mvc.perform(post("/interno/operador").contentType(APPLICATION_JSON)
                        .content("""
                                {"identificadores": ["ana@teste.invalid"]}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario_id").value(ADMIN))
                .andExpect(jsonPath("$.papel").value("ADMIN"));
    }

    @Test
    void semOTokenDeServicoAPortaInternaNaoAbre() throws Exception {
        mvc.perform(post("/interno/operador").contentType(APPLICATION_JSON)
                        .content("""
                                {"identificadores": ["ana@teste.invalid"]}"""))
                .andExpect(status().isUnauthorized());
    }

    /** Aluno não é operador: o login existe, e mesmo assim não abre sessão. */
    @Test
    void aPortaInternaNaoReconheceAluno() throws Exception {
        mvc.perform(post("/interno/operador").contentType(APPLICATION_JSON)
                        .content("""
                                {"identificadores": ["bruno@teste.invalid", "ninguem@x.invalid"]}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    // --- o token Bearer opaco -------------------------------------------------

    /** Os hashes são os do {@code hashlib.sha256} do Python: se o cálculo divergir, isto quebra. */
    private void criarToken(String hash, int dono, boolean revogado) {
        jdbc.update("""
                INSERT INTO api_tokens (usuario_id, nome, token_hash, revogado)
                VALUES (?, 'teste', ?, ?)""", dono, hash, revogado);
    }

    @Test
    void oTokenEmClaroViraOperador() throws Exception {
        criarToken("6b7a5cfeb06512c375c5075775b694b31ede1b4ae15a333697b976b38b2fae9a", ADMIN, false);

        mvc.perform(post("/interno/token").contentType(APPLICATION_JSON)
                        .content("""
                                {"token": "pvm_token-de-teste"}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario_id").value(ADMIN))
                .andExpect(jsonPath("$.papel").value("ADMIN"));

        assertThat(contar("api_tokens WHERE ultimo_uso_em IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void tokenRevogadoNaoAbreSessao() throws Exception {
        criarToken("4ab63d81871dc88828c440c05f96d8c46245b03db3f4d84e5f5295a188c2eac5", ADMIN, true);

        mvc.perform(post("/interno/token").contentType(APPLICATION_JSON)
                        .content("""
                                {"token": "pvm_revogado"}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    /** Aluno com token válido nem chega a ver o catálogo de ferramentas. */
    @Test
    void tokenDeAlunoNaoAbreSessao() throws Exception {
        criarToken("e8de8bb09faa08b05a683ba6f9b784998df078004f38f9567ca62a10ed4294e0", ALUNO, false);

        mvc.perform(post("/interno/token").contentType(APPLICATION_JSON)
                        .content("""
                                {"token": "pvm_do-aluno"}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void tokenQueNaoExisteNaoAbreSessao() throws Exception {
        mvc.perform(post("/interno/token").contentType(APPLICATION_JSON)
                        .content("""
                                {"token": "pvm_inventado"}""")
                        .header("X-Servico", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void foraDosComandosSoOHealthResponde() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/qualquer/outra/coisa")).andExpect(status().isForbidden());
    }
}
