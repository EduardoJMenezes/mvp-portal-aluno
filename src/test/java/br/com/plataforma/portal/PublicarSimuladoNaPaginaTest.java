package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O simulado em rascunho é publicado na página dele, sem a tela de Rascunhos: a aprovação fica
 * gravada do mesmo jeito, e o que barra a publicação continua barrando.
 */
class PublicarSimuladoNaPaginaTest extends BaseDoPortal {

    private static final String QUESTAO = """
            {"enunciado": "Nasceu na prova", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4"}, "gabarito": "A"}""";

    @BeforeEach
    void cenario() {
        comSenhas();
    }

    private int proposto(String agenda) throws Exception {
        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 07", "questoes": [%s]%s}"""
                .formatted(QUESTAO, agenda)).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM exams WHERE titulo = 'Simulado 07'", Integer.class);
    }

    private String situacao(String tabela, String coluna, Object valor) {
        return jdbc.queryForObject("SELECT status FROM %s WHERE %s = ?".formatted(tabela, coluna), String.class, valor);
    }

    @Test
    void publicaComAAprovacaoGravadaELevaAQuestaoNovaJunto() throws Exception {
        var simulado = proposto("""
                , "abre_em": "2090-01-10T14:00", "fecha_em": "2090-01-10T18:00", "duracao_minutos": 120""");

        post("/api/admin/simulados/" + simulado + "/publicacao", "{}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicado").value(true))
                .andExpect(jsonPath("$.simulado_publicado").value("Simulado 07"))
                .andExpect(jsonPath("$.aprovado_via").value("PORTAL"));

        assertThat(situacao("exams", "id", simulado)).isEqualTo("PUBLICADO");
        assertThat(situacao("questions", "enunciado", "Nasceu na prova")).isEqualTo("PUBLICADO");
        var rascunho = jdbc.queryForMap("""
                SELECT d.status, d.aprovado_por_id FROM drafts d JOIN exams e ON e.rascunho_id = d.id WHERE e.id = ?""", simulado);
        assertThat(rascunho.get("status")).isEqualTo("PUBLICADO");
        assertThat(rascunho.get("aprovado_por_id")).isEqualTo(ADMIN);

        post("/api/admin/simulados/" + simulado + "/publicacao", "{}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("já está publicado")));
    }

    @Test
    void semAgendaContinuaSemPublicar() throws Exception {
        var simulado = proposto("");

        post("/api/admin/simulados/" + simulado + "/publicacao", "{}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("abertura e fechamento não definidos")));
        assertThat(situacao("exams", "id", simulado)).isEqualTo("RASCUNHO");
    }

    @Test
    void soOProfessorPublica() throws Exception {
        var simulado = proposto("""
                , "abre_em": "2090-01-10T14:00", "fecha_em": "2090-01-10T18:00", "duracao_minutos": 120""");

        post("/api/admin/simulados/" + simulado + "/publicacao", "{}", ALUNO).andExpect(status().isForbidden());
        assertThat(situacao("exams", "id", simulado)).isEqualTo("RASCUNHO");
    }
}
