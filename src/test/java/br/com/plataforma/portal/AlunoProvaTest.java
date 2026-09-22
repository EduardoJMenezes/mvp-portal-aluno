package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O aluno fazendo a prova: abrir, responder, entregar e ver o resultado só depois do fechamento. */
class AlunoProvaTest extends BaseDoPortal {

    private int simulado;
    private int q1;
    private int q2;

    @BeforeEach
    void prova() {
        comSenhas();
        q1 = criarQuestao("Qual o pH neutro?", "A", "FACIL", "PUBLICADO");
        q2 = criarQuestao("Quantos mols?", "B", "MEDIA", "PUBLICADO");
        simulado = criarSimuladoAberto("Simulado 01", 10, q1, q2);
    }

    @Test
    void listaSoOsSimuladosDasTurmasDele() throws Exception {
        criarSimuladoAberto("Do Intensivo", 11, q1);

        get("/api/aluno/simulados", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].titulo").value("Simulado 01"))
                .andExpect(jsonPath("$[0].minha_prova.iniciada").value(false))
                .andExpect(jsonPath("$[0].resultado_disponivel").value(false));
    }

    @Test
    void abrirComecaAProvaSemMostrarOGabarito() throws Exception {
        get("/api/aluno/simulados/" + simulado, ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EM_ANDAMENTO"))
                .andExpect(jsonPath("$.prazo_em").isString())
                .andExpect(jsonPath("$.questoes.length()").value(2))
                .andExpect(jsonPath("$.questoes[0].alternativas.A").value("alternativa A"))
                .andExpect(jsonPath("$.questoes[0].gabarito").doesNotExist());

        assertThat(contar("exam_attempts WHERE simulado_id = ? AND aluno_id = ?", simulado, ALUNO)).isEqualTo(1);
        get("/api/aluno/simulados", ALUNO).andExpect(jsonPath("$[0].minha_prova.iniciada").value(true));
    }

    @Test
    void respondeEntregaEOResultadoEsperaOFechamento() throws Exception {
        get("/api/aluno/simulados/" + simulado, ALUNO).andExpect(status().isOk());

        post("/api/aluno/simulados/" + simulado + "/responder",
                "{\"questao_id\": %d, \"alternativa\": \"a\"}".formatted(q1), ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrado").value(true))
                .andExpect(jsonPath("$.respondidas").value(1))
                .andExpect(jsonPath("$.correta").doesNotExist());
        post("/api/aluno/simulados/" + simulado + "/responder",
                "{\"questao_id\": %d, \"alternativa\": \"Z\"}".formatted(q1), ALUNO)
                .andExpect(status().isBadRequest());

        post("/api/aluno/simulados/" + simulado + "/entregar", "", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.entregue").value(true))
                .andExpect(jsonPath("$.respondidas").value(1));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(status().isBadRequest());
        get("/api/aluno/simulados/" + simulado, ALUNO).andExpect(jsonPath("$.estado").value("ENTREGUE"));

        jdbc.update("UPDATE exams SET fecha_em = '2020-06-01T00:00:00Z' WHERE id = ?", simulado);
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.acertos").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.em_branco").value(1))
                .andExpect(jsonPath("$.posicao").value(1))
                .andExpect(jsonPath("$.questoes[1].gabarito").value("B"));
        get("/api/aluno/desempenho", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.simulados[0].percentual").value(50.0));
    }

    @Test
    void alunoDeOutraTurmaNaoAbreAProva() throws Exception {
        var outro = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(outro, 11);
        get("/api/aluno/simulados/" + simulado, outro).andExpect(status().isForbidden());
        get("/api/aluno/simulados", outro).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void professorNaoRespondeProva() throws Exception {
        post("/api/aluno/simulados/" + simulado + "/responder",
                "{\"questao_id\": %d, \"alternativa\": \"A\"}".formatted(q1), ADMIN)
                .andExpect(status().isForbidden());
    }

    @Test
    void conteudoVemComEtagERespondeNaoMudou() throws Exception {
        var etag = get("/api/aluno/conteudo", ALUNO).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-cache"))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).startsWith("\"");
        mvc.perform(get("/api/aluno/conteudo").cookie(sessao(ALUNO)).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }
}
