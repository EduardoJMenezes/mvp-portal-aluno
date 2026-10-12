package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Várias questões de uma vez no Banco de questões — as que o professor leu de um .docx e conferiu
 * na tela. Entram todas num rascunho só; publicar junto é escolha de quem conferiu.
 */
class ImportarQuestoesNoBancoTest extends BaseDoPortal {

    private static final String UMA = """
            {"enunciado": "%s", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4"}, "gabarito": "%s"%s}""";

    @BeforeEach
    void cenario() {
        comSenhas();
    }

    private static String lote(String extra, String... questoes) {
        return "{\"arquivo\": \"SIMULADO 02.docx\", \"questoes\": [" + String.join(", ", questoes) + "]" + extra + "}";
    }

    private static String questao(String enunciado) {
        return UMA.formatted(enunciado, "A", "");
    }

    @Test
    void asQuestoesEntramEmRascunhoNumEnvioSo() throws Exception {
        post("/api/admin/questoes/lote", lote("", questao("Primeira"), questao("Segunda"), questao("Terceira")), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questoes.length()").value(3))
                .andExpect(jsonPath("$.publicadas").value(0))
                .andExpect(jsonPath("$.em_rascunho").value(3));

        assertThat(contar("questions WHERE status = 'RASCUNHO'")).isEqualTo(3);
        // Um envio, um rascunho — e o resumo diz de onde vieram.
        assertThat(contar("drafts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resumo FROM drafts", String.class))
                .isEqualTo("3 questões de SIMULADO 02.docx");
        // No Banco aparecem em cima, para conferir: a última da lista é a mais recente.
        get("/api/admin/questoes?ordem=recentes", ADMIN)
                .andExpect(jsonPath("$[0].enunciado").value("Terceira"))
                .andExpect(jsonPath("$[0].status").value("RASCUNHO"));
    }

    @Test
    void quemConferiuPodePublicarJunto() throws Exception {
        post("/api/admin/questoes/lote", lote(", \"publicar\": true", questao("Primeira"), questao("Segunda")), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicadas").value(2))
                .andExpect(jsonPath("$.em_rascunho").value(0));

        assertThat(contar("questions WHERE status = 'PUBLICADO'")).isEqualTo(2);
        var rascunho = jdbc.queryForMap("SELECT status, aprovado_por_id, aprovado_via FROM drafts");
        assertThat(rascunho.get("status")).isEqualTo("PUBLICADO");
        assertThat(rascunho.get("aprovado_por_id")).isEqualTo(ADMIN);
        assertThat(rascunho.get("aprovado_via")).isEqualTo("PORTAL");
    }

    @Test
    void aDeImagemPendenteFicaEmRascunhoMesmoPublicandoAsOutras() throws Exception {
        post("/api/admin/questoes/lote", lote(", \"publicar\": true", questao("Com a figura no lugar"),
                UMA.formatted("Falta a figura:\\n\\n![](figura:pendente)", "B", "")), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicadas").value(1))
                .andExpect(jsonPath("$.em_rascunho").value(1))
                .andExpect(jsonPath("$.com_imagem_pendente").value(1));

        assertThat(jdbc.queryForObject("SELECT status FROM questions WHERE enunciado LIKE 'Falta a figura%'", String.class))
                .isEqualTo("RASCUNHO");
        // O rascunho continua aberto: ainda há questão dele por publicar.
        assertThat(jdbc.queryForObject("SELECT status FROM drafts", String.class)).isEqualTo("RASCUNHO");
    }

    @Test
    void umaQuestaoQueNaoFechaSeguraTodasEDizQualE() throws Exception {
        post("/api/admin/questoes/lote", lote("", questao("Primeira"), UMA.formatted("Gabarito fora das alternativas", "E", "")), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Questão 2:")));

        assertThat(contar("questions")).isZero();
        assertThat(contar("drafts")).isZero();
    }

    @Test
    void soOProfessorImporta() throws Exception {
        post("/api/admin/questoes/lote", lote("", questao("Primeira")), ALUNO).andExpect(status().isForbidden());
        post("/api/admin/questoes/lote", "{\"questoes\": []}", ADMIN).andExpect(status().is(422));

        assertThat(contar("questions")).isZero();
    }
}
