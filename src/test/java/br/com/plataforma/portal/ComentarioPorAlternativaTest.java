package br.com.plataforma.portal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O comentário de cada alternativa: o professor escreve no cadastro, e o aluno só o lê depois de
 * responder — como o gabarito.
 */
class ComentarioPorAlternativaTest extends BaseDoPortal {

    private static final String QUESTAO = """
            {"enunciado": "Qual a massa de 2 mol de água?",
             "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"},
             "gabarito": "A",
             "comentarios": {"A": "2 mol x 18 g/mol = 36 g.", "B": "  18 g é a massa de 1 mol: faltou multiplicar por 2.  "}}""";

    private int apostila;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
    }

    private int criar() throws Exception {
        var corpo = post("/api/admin/questoes", QUESTAO, ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Integer>read(corpo, "$.questoes[0].questao_id");
    }

    private int naAula(int questao) throws Exception {
        var corpo = post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Integer>read(corpo, "$.id");
    }

    // --- o professor escreve -----------------------------------------------------

    @Test
    void oComentarioEntraNoCadastroEVoltaNoDetalhe() throws Exception {
        var questao = criar();

        get("/api/admin/questoes/" + questao, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comentarios.A").value("2 mol x 18 g/mol = 36 g."))
                // Sem os espaços das pontas.
                .andExpect(jsonPath("$.comentarios.B").value("18 g é a massa de 1 mol: faltou multiplicar por 2."))
                // Quem não foi comentada não aparece.
                .andExpect(jsonPath("$.comentarios.C").doesNotExist());
    }

    @Test
    void editarMexeSoNasLetrasInformadasEVazioTiraOComentario() throws Exception {
        var questao = criar();

        patch("/api/admin/questoes/" + questao, """
                {"comentarios": {"C": "20 g não corresponde a nenhuma conta com H2O.", "B": ""}}""", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comentarios.A").value("2 mol x 18 g/mol = 36 g."))
                .andExpect(jsonPath("$.comentarios.C").value("20 g não corresponde a nenhuma conta com H2O."))
                .andExpect(jsonPath("$.comentarios.B").doesNotExist());
    }

    @Test
    void comentarioDeLetraQueAQuestaoNaoTemERecusado() throws Exception {
        var questao = criar();

        patch("/api/admin/questoes/" + questao, "{\"comentarios\": {\"E\": \"não existe\"}}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A questão não tem a alternativa E para comentar."));
    }

    @Test
    void comentarioLongoDemaisERecusado() throws Exception {
        var questao = criar();

        patch("/api/admin/questoes/" + questao, "{\"comentarios\": {\"A\": \"%s\"}}".formatted("x".repeat(1001)), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("o limite é 1000")));
    }

    @Test
    void oComentarioMudaMesmoComAProvaAbertaComoAResolucao() throws Exception {
        var questao = criar();
        var aberto = criarSimulado("Simulado", "PUBLICADO", "2026-01-01T10:00:00Z", "2099-01-01T10:00:00Z");
        porNaProva(aberto, questao, 1);

        // O enunciado trava; o comentário, que o aluno só lê depois, não.
        patch("/api/admin/questoes/" + questao, "{\"enunciado\": \"Outro\"}", ADMIN).andExpect(status().isBadRequest());
        patch("/api/admin/questoes/" + questao, "{\"comentarios\": {\"D\": \"34 g é a massa do H2O2.\"}}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comentarios.D").value("34 g é a massa do H2O2."));
    }

    @Test
    void oRascunhoDoClaudeMostraOsComentariosParaQuemAprova() throws Exception {
        comando("criar_questao_rascunho", QUESTAO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questoes[0].comentarios.B").value("18 g é a massa de 1 mol: faltou multiplicar por 2."));
    }

    // --- o aluno lê --------------------------------------------------------------

    @Test
    void naAulaOComentarioSoSaiDepoisDaResposta() throws Exception {
        var item = naAula(criar());

        get("/api/aluno/itens/" + item + "/questao", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comentarios").isEmpty())
                .andExpect(jsonPath("$.gabarito").doesNotExist());

        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"B\"}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comentarios.B").value("18 g é a massa de 1 mol: faltou multiplicar por 2."))
                .andExpect(jsonPath("$.comentarios.A").value("2 mol x 18 g/mol = 36 g."));
    }

    @Test
    void noSimuladoOComentarioVemComOResultadoDepoisQueFecha() throws Exception {
        var questao = criar();
        var fechado = criarSimulado("Simulado", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-02T10:00:00Z");
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", fechado);
        porNaProva(fechado, questao, 1);
        responder(fechado, ALUNO, questao, false);

        get("/api/aluno/simulados/" + fechado + "/resultado", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questoes[0].comentarios.B").value("18 g é a massa de 1 mol: faltou multiplicar por 2."));
    }
}
