package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** A prova no tempo: o que trava quando ela abre, e o ranking. */
class SimuladosComandosTest extends BaseDeComando {

    private static final String PASSADO_ABRE = "2020-01-01T10:00:00Z";
    private static final String PASSADO_FECHA = "2020-01-01T14:00:00Z";
    private static final String FUTURO = "2090-01-01T10:00:00Z";

    /** Prova publicada com janela e questão, pronta para os testes de trava. */
    private int questaoDaProvaAberta;

    private int provaAberta(String titulo) {
        var s = criarSimulado(titulo, "PUBLICADO", PASSADO_ABRE, FUTURO);
        questaoDaProvaAberta = criarQuestao("Uma questão", "A", "MEDIA", "PUBLICADO");
        porNaProva(s, questaoDaProvaAberta, 1);
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", s);
        jdbc.update("UPDATE exams SET duracao_minutos = 60 WHERE id = ?", s);
        return s;
    }

    // --- listar e detalhar ---------------------------------------------------

    @Test
    void listaComSituacaoTurmasEQuantidadeDeTentativas() throws Exception {
        var s = provaAberta("Simulado 01");
        responder(s, ALUNO, questaoDaProvaAberta, true);

        comando("listar_simulados", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].titulo").value("Simulado 01"))
                .andExpect(jsonPath("$[0].situacao").value("ABERTO"))
                .andExpect(jsonPath("$[0].turmas[0]").value("Extensivo 2027"))
                .andExpect(jsonPath("$[0].total_questoes").value(1))
                .andExpect(jsonPath("$[0].tentativas").value(1));
    }

    @Test
    void detalharMostraAProvaEOQueFaltaParaPublicar() throws Exception {
        var s = criarSimulado("Sem agenda", "RASCUNHO", null, null);
        porNaProva(s, criarQuestao("Enunciado", "C", "MEDIA", "PUBLICADO"), 1);

        comando("detalhar_simulado", """
                {"simulado": "Sem agenda"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("RASCUNHO"))
                .andExpect(jsonPath("$.questoes[0].ordem").value(1))
                .andExpect(jsonPath("$.questoes[0].gabarito").value("C"))
                .andExpect(jsonPath("$.pendencias_para_publicar", org.hamcrest.Matchers.hasItems(
                        "nenhuma turma", "abertura e fechamento não definidos",
                        "tempo de prova não definido")));
    }

    @Test
    void simuladoQueNaoExisteListaOsQueHa() throws Exception {
        criarSimulado("Simulado 01", "RASCUNHO", null, null);

        comando("detalhar_simulado", """
                {"simulado": "Simulado 99"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value("Simulado 'Simulado 99' não existe. Simulados: #1 Simulado 01."));
    }

    // --- editar: o que trava quando abre -------------------------------------

    @Test
    void emRascunhoTudoMuda() throws Exception {
        var s = criarSimulado("Provão", "RASCUNHO", null, null);
        porNaProva(s, criarQuestao("Enunciado", "A", "MEDIA", "PUBLICADO"), 1);

        comando("editar_simulado", """
                {"simulado": "Provão", "titulo": "Provão 2027", "abre_em": "2090-03-01T14:00",
                 "fecha_em": "2090-03-01T18:00", "duracao_minutos": 180, "turmas": ["Extensivo 2027"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Provão 2027"))
                .andExpect(jsonPath("$.duracao_minutos").value(180))
                .andExpect(jsonPath("$.turmas[0]").value("Extensivo 2027"))
                .andExpect(jsonPath("$.abre_em").value(org.hamcrest.Matchers.startsWith("2090-03-01T14:00")));
    }

    @Test
    void abertoSoOTituloEOFechamentoEsticado() throws Exception {
        provaAberta("Em andamento");

        comando("editar_simulado", """
                {"simulado": "Em andamento", "duracao_minutos": 30}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "já abriu em 01/01/2020 às 07:00: questões, gabarito, turmas e tempo de prova travaram")));

        comando("editar_simulado", """
                {"simulado": "Em andamento", "titulo": "Em andamento (adiado)",
                 "fecha_em": "2091-01-01T10:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Em andamento (adiado)"));
    }

    /** Encurtar tiraria tempo de quem está fazendo a prova. */
    @Test
    void abertoNaoAceitaAntecipeOFechamento() throws Exception {
        provaAberta("Em andamento");

        comando("editar_simulado", """
                {"simulado": "Em andamento", "fecha_em": "2089-01-01T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "'Em andamento' já abriu: o fechamento só pode ser estendido, não antecipado."));
    }

    /** Reabrir devolveria a prova a quem já viu o gabarito. */
    @Test
    void encerradoSoOTitulo() throws Exception {
        criarSimulado("Já era", "PUBLICADO", PASSADO_ABRE, PASSADO_FECHA);

        comando("editar_simulado", """
                {"simulado": "Já era", "fecha_em": "2090-01-01T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "fechou em 01/01/2020 às 11:00 e o resultado já saiu: só o título muda")));

        comando("editar_simulado", """
                {"simulado": "Já era", "titulo": "Simulado 01 (2020)"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Simulado 01 (2020)"));
    }

    @Test
    void trocaAsQuestoesEnquantoERascunho() throws Exception {
        var s = criarSimulado("Provão", "RASCUNHO", null, null);
        var a = criarQuestao("Questão A", "A", "MEDIA", "PUBLICADO");
        var b = criarQuestao("Questão B", "B", "MEDIA", "PUBLICADO");
        porNaProva(s, a, 1);

        comando("editar_simulado", """
                {"simulado": "Provão", "questoes": [%d, %d]}""".formatted(b, a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_questoes").value(2));

        assertThat(jdbc.queryForObject(
                "SELECT questao_id FROM exam_questions WHERE simulado_id = ? AND ordem = 1",
                Integer.class, s)).isEqualTo(b);
    }

    // --- remover -------------------------------------------------------------

    @Test
    void naoRemoveComAProvaAberta() throws Exception {
        provaAberta("Em andamento");

        comando("remover_simulado", """
                {"simulado": "Em andamento"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "'Em andamento' está aberto agora, com alunos fazendo a prova. Espere fechar."));
    }

    /** O rascunho leva junto as questões novas que nasceram com ele. */
    @Test
    void removerRascunhoLevaAsQuestoesNovas() throws Exception {
        var doAcervo = criarQuestao("Do acervo", "A", "MEDIA", "PUBLICADO");
        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 05",
                 "questoes": [%d, {"enunciado": "Nasceu aqui", "alternativas":
                 {"A": "1", "B": "2", "C": "3", "D": "4", "E": "5"}, "gabarito": "A"}]}"""
                .formatted(doAcervo)).andExpect(status().isOk());

        comando("remover_simulado", """
                {"simulado": "Simulado 05"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questoes_novas_removidas").value(1))
                .andExpect(jsonPath("$.reversivel").value(true));

        // A do acervo continua lá; só a que nasceu na proposta saiu.
        assertThat(contar("questions WHERE removido_em IS NULL")).isEqualTo(1);
    }

    // --- ranking -------------------------------------------------------------

    @Test
    void rankingOrdenaPorAcertosEEmpateDivideAPosicao() throws Exception {
        var s = criarSimulado("Simulado 06", "PUBLICADO", PASSADO_ABRE, PASSADO_FECHA);
        var q1 = criarQuestao("Q1", "A", "MEDIA", "PUBLICADO");
        var q2 = criarQuestao("Q2", "A", "MEDIA", "PUBLICADO");
        porNaProva(s, q1, 1);
        porNaProva(s, q2, 2);
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", s);
        jdbc.update("""
                INSERT INTO users (id, nome, email, senha_hash, papel) VALUES
                  (3, 'Aluna Carla', 'carla@teste.invalid', 'x', 'ALUNO'),
                  (4, 'Aluno Diego', 'diego@teste.invalid', 'x', 'ALUNO')""");
        jdbc.update("INSERT INTO enrollments (usuario_id, turma_id) VALUES (2, 10), (3, 10), (4, 10)");

        responder(s, ALUNO, q1, true);   // Bruno: 1 acerto
        responder(s, 3, q1, true);       // Carla: 2 acertos
        jdbc.update("""
                INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta)
                SELECT id, ?, 'A', true FROM exam_attempts WHERE aluno_id = 3""", q2);
        responder(s, 4, q1, true);       // Diego: 1 acerto

        comando("buscar_ranking_simulado", """
                {"simulado": "Simulado 06"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcial").value(false))
                .andExpect(jsonPath("$.participantes").value(3))
                .andExpect(jsonPath("$.ranking[0].aluno").value("Aluna Carla"))
                .andExpect(jsonPath("$.ranking[0].posicao").value(1))
                .andExpect(jsonPath("$.ranking[0].percentual").value(100.0))
                .andExpect(jsonPath("$.ranking[0].turmas[0]").value("Extensivo 2027"))
                // empate em 1 acerto: os dois em 2º, e não haveria 3º
                .andExpect(jsonPath("$.ranking[1].aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.ranking[1].posicao").value(2))
                .andExpect(jsonPath("$.ranking[2].aluno").value("Aluno Diego"))
                .andExpect(jsonPath("$.ranking[2].posicao").value(2))
                .andExpect(jsonPath("$.ranking[2].percentual").value(50.0));
    }

    /** Antes de fechar, a posição ainda muda — o ranking sai marcado como parcial. */
    @Test
    void rankingDeProvaAbertaEParcial() throws Exception {
        provaAberta("Em andamento");

        comando("buscar_ranking_simulado", """
                {"simulado": "Em andamento"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcial").value(true))
                .andExpect(jsonPath("$.participantes").value(0));
    }
}
