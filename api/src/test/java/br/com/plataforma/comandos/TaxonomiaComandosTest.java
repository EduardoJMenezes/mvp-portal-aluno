package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** Assuntos, sub-assuntos e a etiqueta em lote nos vídeos. */
class TaxonomiaComandosTest extends BaseDeComando {

    /** "K01" com três questões em 'Questões da apostila', cada uma com seu vídeo. */
    private void montarTresQuestoes() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        var sub = idDo("submodules", "Questões da apostila");
        for (int n = 1; n <= 3; n++) {
            criarItem(sub, criarVideo("v" + n, "Resolução Q0" + n), "Q0" + n, n, "PUBLICADO");
        }
    }

    // --- cadastrar_assunto ---------------------------------------------------

    @Test
    void cadastraOAssuntoComOsSubassuntosDeUmaVez() throws Exception {
        comando("cadastrar_assunto", """
                {"nome": "  Estequiometria  ",
                 "subassuntos": ["Pureza e rendimento", "Reagente limitante"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assunto_id").isNumber())
                .andExpect(jsonPath("$.assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.subassuntos[0]").value("Pureza e rendimento"))
                .andExpect(jsonPath("$.subassuntos[1]").value("Reagente limitante"));

        assertThat(contar("subtopics")).isEqualTo(2);
    }

    @Test
    void cadastrarDeNovoNaoDuplica() throws Exception {
        var corpo = """
                {"nome": "Estequiometria", "subassuntos": ["Pureza e rendimento"]}""";
        comando("cadastrar_assunto", corpo).andExpect(status().isOk());
        comando("cadastrar_assunto", corpo).andExpect(status().isOk());

        assertThat(contar("subjects")).isEqualTo(1);
        assertThat(contar("subtopics")).isEqualTo(1);
    }

    @Test
    void listarAssuntosVemEmOrdemComOsSubassuntos() throws Exception {
        comando("cadastrar_assunto", """
                {"nome": "Termoquímica"}""").andExpect(status().isOk());
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Reagente limitante", "Pureza"]}""")
                .andExpect(status().isOk());

        comando("listar_assuntos", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Estequiometria"))
                .andExpect(jsonPath("$[0].subassuntos[0].nome").value("Pureza"))
                .andExpect(jsonPath("$[0].subassuntos[1].nome").value("Reagente limitante"))
                .andExpect(jsonPath("$[1].nome").value("Termoquímica"))
                .andExpect(jsonPath("$[1].subassuntos").isEmpty());
    }

    // --- classificar_videos --------------------------------------------------

    @Test
    void classificaOSubmoduloInteiroQuandoNaoDizemAFaixa() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Pureza e rendimento"]}""")
                .andExpect(status().isOk());

        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria", "subassunto": "Pureza"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videos_classificados.length()").value(3))
                .andExpect(jsonPath("$.assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.subassunto").value("Pureza e rendimento"));

        assertThat(contar("video_subjects")).isEqualTo(3);
    }

    @Test
    void classificaSoAFaixaPedida() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());

        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria", "itens": "Q01-Q02"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videos_classificados[0]").value("Q01"))
                .andExpect(jsonPath("$.videos_classificados[1]").value("Q02"))
                .andExpect(jsonPath("$.videos_classificados.length()").value(2))
                .andExpect(jsonPath("$.subassunto").doesNotExist());

        assertThat(contar("video_subjects")).isEqualTo(2);
    }

    @Test
    void classificarDeNovoNaoDuplicaAEtiqueta() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());
        var corpo = """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria"}""";

        comando("classificar_videos", corpo).andExpect(status().isOk());
        comando("classificar_videos", corpo).andExpect(status().isOk());

        assertThat(contar("video_subjects")).isEqualTo(3);
    }

    @Test
    void faixaQueNaoCasaComNadaERecusada() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());

        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria", "itens": "Q90-Q99"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Nenhum item casou com a faixa informada."));
    }

    @Test
    void assuntoQueNaoExisteEnsinaComoCriar() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Termoquímica"}""").andExpect(status().isOk());

        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Cinética"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Assunto 'Cinética' não existe. Assuntos: 'Termoquímica'. "
                                + "Para criar um novo, use criar_assunto."));
    }

    @Test
    void subassuntoQueNaoExisteListaOsQueHa() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Pureza e rendimento"]}""")
                .andExpect(status().isOk());

        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria", "subassunto": "Mol"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Sub-assunto 'Mol' não existe em 'Estequiometria'. Há: 'Pureza e rendimento'."));
    }

    /** A etiqueta vai no vídeo: classificar por uma turma vale para as outras onde ele aparece. */
    @Test
    void aEtiquetaFicaNoVideoNaoNoItem() throws Exception {
        montarTresQuestoes();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());
        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "assunto": "Estequiometria", "itens": "Q01"}""").andExpect(status().isOk());

        var video = jdbc.queryForObject(
                "SELECT video_id FROM video_subjects", Integer.class);
        assertThat(jdbc.queryForObject(
                "SELECT vimeo_id FROM videos WHERE id = ?", String.class, video)).isEqualTo("v1");
        // e o vídeo foi carimbado por quem classificou
        assertThat(jdbc.queryForObject(
                "SELECT alterado_por_id FROM videos WHERE id = ?", Integer.class, video)).isEqualTo(ADMIN);
    }
}
