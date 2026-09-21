package br.com.plataforma.comandos;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** A leitura do professor: como o aluno foi, como a turma foi, e para onde mandar quem errou. */
class AnalyticsComandosTest extends BaseDeComando {

    private int simulado;
    private int q1;
    private int q2;

    /** Prova encerrada com duas questões e dois alunos, um melhor que o outro. */
    private void provaFeita() {
        simulado = criarSimulado("Simulado 01", "PUBLICADO", "2020-01-01T10:00:00Z", "2020-01-01T14:00:00Z");
        q1 = criarQuestao("Calcule a massa molar", "A", "MEDIA", "PUBLICADO");
        q2 = criarQuestao("Qual o reagente limitante?", "A", "MEDIA", "PUBLICADO");
        porNaProva(simulado, q1, 1);
        porNaProva(simulado, q2, 2);
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", simulado);
        jdbc.update("""
                INSERT INTO users (id, nome, email, senha_hash, papel)
                VALUES (3, 'Aluna Carla', 'carla@teste.invalid', 'x', 'ALUNO')""");
        jdbc.update("INSERT INTO enrollments (usuario_id, turma_id) VALUES (2, 10), (3, 10)");

        // Bruno erra a q2; Carla acerta as duas.
        responder(simulado, ALUNO, q1, true);
        jdbc.update("""
                INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta)
                SELECT id, ?, 'C', false FROM exam_attempts WHERE aluno_id = 2""", q2);
        responder(simulado, 3, q1, true);
        jdbc.update("""
                INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta)
                SELECT id, ?, 'A', true FROM exam_attempts WHERE aluno_id = 3""", q2);
    }

    // --- buscar_desempenho_aluno ---------------------------------------------

    @Test
    void mostraComoOAlunoFoiQuestaoAQuestao() throws Exception {
        provaFeita();

        comando("buscar_desempenho_aluno", """
                {"aluno": "Bruno"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encontrou_dados").value(true))
                .andExpect(jsonPath("$.aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.simulado").value("Simulado 01"))
                .andExpect(jsonPath("$.situacao").value("ENCERRADO"))
                .andExpect(jsonPath("$.acertos").value(1))
                .andExpect(jsonPath("$.total_questoes").value(2))
                .andExpect(jsonPath("$.percentual").value(50.0))
                .andExpect(jsonPath("$.em_branco").value(0))
                .andExpect(jsonPath("$.questoes[1].marcada").value("C"))
                .andExpect(jsonPath("$.questoes[1].gabarito").value("A"))
                .andExpect(jsonPath("$.questoes[1].correta").value(false));
    }

    /** Do erro para o vídeo que explica aquilo — é o elo que a plataforma existe para fazer. */
    @Test
    void recomendaOVideoDoAssuntoQueOAlunoErrou() throws Exception {
        provaFeita();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());
        comando("editar_questao", """
                {"questao": "%d", "assunto": "Estequiometria"}""".formatted(q2))
                .andExpect(status().isOk());

        // Um vídeo do acervo etiquetado com o mesmo assunto.
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        criarItem(idDo("submodules", "Aulas"), criarVideo("v9", "Aula de estequiometria"),
                "Aula 1", 1, "PUBLICADO");
        comando("classificar_videos", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "assunto": "Estequiometria"}""").andExpect(status().isOk());

        comando("buscar_desempenho_aluno", """
                {"aluno": "Bruno"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros_por_topico[0].topico").value("Estequiometria"))
                .andExpect(jsonPath("$.erros_por_topico[0].erros").value(1))
                .andExpect(jsonPath("$.recomendacoes[0].topico").value("Estequiometria"))
                .andExpect(jsonPath("$.recomendacoes[0].videos[0].titulo").value("Aula de estequiometria"))
                // operador enxerga o acervo inteiro, então vem liberado com o embed
                .andExpect(jsonPath("$.recomendacoes[0].videos[0].bloqueado").value(false));
    }

    @Test
    void alunoSemProvaDizIsso() throws Exception {
        comando("buscar_desempenho_aluno", """
                {"aluno": "Bruno"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encontrou_dados").value(false))
                .andExpect(jsonPath("$.mensagem").value("Aluno Bruno ainda não fez nenhum simulado."));
    }

    @Test
    void alunoQueNaoExisteListaOsQueHa() throws Exception {
        comando("buscar_desempenho_aluno", """
                {"aluno": "Fulano"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value("Aluno 'Fulano' não encontrado. Alunos: Aluno Bruno."));
    }

    // --- buscar_estatisticas_simulado ----------------------------------------

    @Test
    void estatisticasTrazemQuestaoAQuestaoEAMaiorDificuldade() throws Exception {
        provaFeita();

        comando("buscar_estatisticas_simulado", """
                {"simulado": "Simulado 01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encontrou_dados").value(true))
                .andExpect(jsonPath("$.parcial").value(false))
                .andExpect(jsonPath("$.alunos_matriculados").value(2))
                .andExpect(jsonPath("$.alunos_responderam").value(2))
                .andExpect(jsonPath("$.media_percentual").value(75.0))
                .andExpect(jsonPath("$.por_questao[0].percentual_acerto").value(100.0))
                .andExpect(jsonPath("$.por_questao[1].percentual_acerto").value(50.0))
                .andExpect(jsonPath("$.por_questao[1].distribuicao.A").value(1))
                .andExpect(jsonPath("$.por_questao[1].distribuicao.C").value(1))
                .andExpect(jsonPath("$.maior_dificuldade.questao_id").value(q2))
                .andExpect(jsonPath("$.por_aluno[0].aluno").value("Aluna Carla"))
                .andExpect(jsonPath("$.por_aluno[0].percentual").value(100.0));
    }

    @Test
    void semNinguemQueFezOSimuladoDizIsso() throws Exception {
        criarSimulado("Vazio", "PUBLICADO", "2020-01-01T10:00:00Z", "2020-01-01T14:00:00Z");

        comando("buscar_estatisticas_simulado", """
                {"simulado": "Vazio"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encontrou_dados").value(false))
                .andExpect(jsonPath("$.mensagem").value("Nenhum aluno começou este simulado ainda."))
                .andExpect(jsonPath("$.alunos_responderam").value(0));
    }
}
