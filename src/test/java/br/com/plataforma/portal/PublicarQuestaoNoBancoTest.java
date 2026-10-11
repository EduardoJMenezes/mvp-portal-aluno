package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A questão que o Claude escreve fica em rascunho no Banco de questões, em cima da lista, e é dali
 * que o professor a publica: sem passar pela tela de Rascunhos, e com a aprovação gravada igual.
 */
class PublicarQuestaoNoBancoTest extends BaseDoPortal {

    private static final String QUESTAO = """
            {"enunciado": "Qual a massa de 2 mol de água?",
             "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"},
             "gabarito": "A"%s}""";

    @BeforeEach
    void cenario() {
        comSenhas();
    }

    private int doClaude(String extra) throws Exception {
        var resposta = comando("criar_questao_rascunho", QUESTAO.formatted(extra))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.questoes[0].questao_id");
    }

    private String situacao(int questao) {
        return jdbc.queryForObject("SELECT status FROM questions WHERE id = ?", String.class, questao);
    }

    @Test
    void aQuestaoDoClaudeEPublicadaPeloBancoComAAprovacaoGravada() throws Exception {
        var antiga = criarQuestao("Questão que já estava no acervo", "B", "MEDIA", "PUBLICADO");
        var nova = doClaude("");

        // No Banco de questões ela aparece em cima, ainda em rascunho.
        get("/api/admin/questoes?ordem=recentes", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questao_id").value(nova))
                .andExpect(jsonPath("$[0].status").value("RASCUNHO"))
                .andExpect(jsonPath("$[1].questao_id").value(antiga));
        // Sem pedir a ordem, a lista continua como sempre foi.
        get("/api/admin/questoes", ADMIN).andExpect(jsonPath("$[0].questao_id").value(antiga));

        post("/api/admin/questoes/" + nova + "/publicacao", "{}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLICADO"));

        // O rascunho dela fecha junto, aprovado por quem publicou.
        var rascunho = jdbc.queryForMap("""
                SELECT d.status, d.aprovado_por_id, d.aprovado_via FROM drafts d
                JOIN questions q ON q.rascunho_id = d.id WHERE q.id = ?""", nova);
        assertThat(rascunho.get("status")).isEqualTo("PUBLICADO");
        assertThat(rascunho.get("aprovado_por_id")).isEqualTo(ADMIN);
        assertThat(rascunho.get("aprovado_via")).isEqualTo("PORTAL");

        post("/api/admin/questoes/" + nova + "/publicacao", "{}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("já está publicada")));
    }

    @Test
    void soOProfessorPublica() throws Exception {
        var nova = doClaude("");

        post("/api/admin/questoes/" + nova + "/publicacao", "{}", ALUNO).andExpect(status().isForbidden());
        assertThat(situacao(nova)).isEqualTo("RASCUNHO");
    }

    @Test
    void imagemPendenteSeguraAPublicacao() throws Exception {
        var nova = doClaude(", \"imagem_pendente\": true");

        post("/api/admin/questoes/" + nova + "/publicacao", "{}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("imagem pendente")));
        assertThat(situacao(nova)).isEqualTo("RASCUNHO");
    }

    @Test
    void aQuestaoDeSimuladoEmRascunhoNaoSaiSozinha() throws Exception {
        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 05",
                 "questoes": [{"enunciado": "Nasceu na prova", "alternativas":
                 {"A": "1", "B": "2", "C": "3", "D": "4"}, "gabarito": "A"}]}""").andExpect(status().isOk());
        var daProva = jdbc.queryForObject("SELECT id FROM questions WHERE enunciado = 'Nasceu na prova'", Integer.class);

        post("/api/admin/questoes/" + daProva + "/publicacao", "{}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("publicada junto com ele")));
        assertThat(situacao(daProva)).isEqualTo("RASCUNHO");
    }
}
