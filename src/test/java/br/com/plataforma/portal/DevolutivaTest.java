package br.com.plataforma.portal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A devolutiva por assunto: resposta de aula e de simulado na mesma conta, a nota que desconfia de
 * amostra pequena e o que cada um pode ver.
 */
class DevolutivaTest extends BaseDoPortal {

    private int apostila;
    private int maria;
    private int q1;
    private int q2;
    private int q3;
    private int q4;
    private int q5;
    private int q6;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        matricular(maria, 10);

        var estequiometria = jdbc.queryForObject("INSERT INTO subjects (nome) VALUES ('Estequiometria') RETURNING id", Integer.class);
        var mol = jdbc.queryForObject("INSERT INTO subtopics (assunto_id, nome) VALUES (?, 'Mol') RETURNING id", Integer.class, estequiometria);
        var atomistica = jdbc.queryForObject("INSERT INTO subjects (nome) VALUES ('Atomística') RETURNING id", Integer.class);

        q1 = questao("Q1", estequiometria, mol);
        q2 = questao("Q2", estequiometria, mol);
        q3 = questao("Q3", estequiometria, null);
        q4 = questao("Q4", atomistica, null);
        q5 = questao("Q5", atomistica, null);
        // Sem etiqueta: responde, mas não entra em assunto nenhum.
        q6 = criarQuestao("Q6", "A", "MEDIA", "PUBLICADO");

        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);

        // Na aula: erra as duas de Mol, acerta a de Atomística e a sem etiqueta.
        responderNaAula(naAula(q1), "B", ALUNO);
        responderNaAula(naAula(q2), "C", ALUNO);
        responderNaAula(naAula(q4), "A", ALUNO);
        responderNaAula(naAula(q6), "A", ALUNO);

        // Num simulado que já fechou: erra Q3, acerta Q5.
        var fechado = criarSimulado("Simulado 1", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-02T10:00:00Z");
        porNaProva(fechado, q3, 1);
        porNaProva(fechado, q5, 2);
        prova(fechado, ALUNO, q3, false, q5, true);
    }

    private int questao(String enunciado, int assunto, Integer subassunto) {
        var id = criarQuestao(enunciado, "A", "MEDIA", "PUBLICADO");
        jdbc.update("INSERT INTO question_subjects (questao_id, assunto_id, subassunto_id) VALUES (?, ?, ?)", id, assunto, subassunto);
        return id;
    }

    /** Põe a questão do acervo como linha da aula e devolve o item. */
    private int naAula(int questao) throws Exception {
        var corpo = post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<Integer>read(corpo, "$.id");
    }

    private void responderNaAula(int item, String letra, int aluno) throws Exception {
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"%s\"}".formatted(letra), aluno)
                .andExpect(status().isOk());
    }

    /** Uma prova entregue, com duas respostas. */
    private void prova(int simulado, int aluno, int primeira, boolean acertou1, int segunda, boolean acertou2) {
        var tentativa = jdbc.queryForObject(
                "INSERT INTO exam_attempts (simulado_id, aluno_id, finalizado_em) VALUES (?, ?, now()) RETURNING id",
                Integer.class, simulado, aluno);
        jdbc.update("INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta) VALUES (?, ?, 'A', ?), (?, ?, 'A', ?)",
                tentativa, primeira, acertou1, tentativa, segunda, acertou2);
    }

    // --- o aluno -----------------------------------------------------------------

    @Test
    void aulaESimuladoEntramNaMesmaContaPorAssunto() throws Exception {
        get("/api/aluno/desempenho/assuntos", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respostas").value(6))
                .andExpect(jsonPath("$.acertos").value(3))
                .andExpect(jsonPath("$.percentual").value(50.0))
                .andExpect(jsonPath("$.sem_assunto").value(1))
                .andExpect(jsonPath("$.questoes_sem_assunto").value(1))
                // O pior vem primeiro.
                .andExpect(jsonPath("$.assuntos[0].nome").value("Estequiometria"))
                .andExpect(jsonPath("$.assuntos[0].respostas").value(3))
                .andExpect(jsonPath("$.assuntos[0].acertos").value(0))
                .andExpect(jsonPath("$.assuntos[0].da_aula").value(2))
                .andExpect(jsonPath("$.assuntos[0].de_simulado").value(1))
                .andExpect(jsonPath("$.assuntos[0].nivel").value("ATENCAO"))
                .andExpect(jsonPath("$.assuntos[0].subassuntos[0].nome").value("Mol"))
                .andExpect(jsonPath("$.assuntos[0].subassuntos[0].respostas").value(2))
                .andExpect(jsonPath("$.assuntos[1].nome").value("Atomística"))
                .andExpect(jsonPath("$.assuntos[1].acertos").value(2))
                .andExpect(jsonPath("$.assuntos[1].percentual").value(100.0));
    }

    @Test
    void aNotaAjustadaDesconfiaDePoucasRespostas() throws Exception {
        // Geral 50%. Estequiometria: 0 de 3 -> (0 + 4 x 0,5) / (3 + 4) = 28,6%.
        // Atomística: 2 de 2 -> (2 + 2) / (2 + 4) = 66,7%: cem por cento em duas questões ainda não é "indo bem".
        get("/api/aluno/desempenho/assuntos", ALUNO)
                .andExpect(jsonPath("$.assuntos[0].ajustado").value(28.6))
                .andExpect(jsonPath("$.assuntos[1].ajustado").value(66.7))
                .andExpect(jsonPath("$.assuntos[1].nivel").value("DESENVOLVENDO"));
    }

    @Test
    void ondeRevisarApontaOPontoMaisFracoENaoOQueNaoTemErro() throws Exception {
        get("/api/aluno/desempenho/assuntos", ALUNO)
                .andExpect(jsonPath("$.onde_revisar.length()").value(1))
                .andExpect(jsonPath("$.onde_revisar[0].topico").value("Mol"))
                .andExpect(jsonPath("$.onde_revisar[0].erros").value(2));
    }

    @Test
    void simuladoAbertoNaoEntraParaOAlunoMasEntraParaOProfessor() throws Exception {
        var aberto = criarSimulado("Simulado 2", "PUBLICADO", "2026-01-01T10:00:00Z", "2099-01-01T10:00:00Z");
        porNaProva(aberto, q4, 1);
        porNaProva(aberto, q5, 2);
        prova(aberto, ALUNO, q4, true, q5, true);

        // O aluno não vê o resultado antes de fechar, então a devolutiva dele também não conta.
        get("/api/aluno/desempenho/assuntos", ALUNO).andExpect(jsonPath("$.respostas").value(6));
        get("/api/admin/alunos/" + ALUNO + "/assuntos", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respostas").value(8))
                .andExpect(jsonPath("$.acertos").value(5));
    }

    @Test
    void emBrancoEmProvaEntregueContaComoErro() throws Exception {
        var outro = criarSimulado("Simulado 3", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-03T10:00:00Z");
        porNaProva(outro, q4, 1);
        porNaProva(outro, q5, 2);
        // Maria entregou sem marcar nada.
        jdbc.update("INSERT INTO exam_attempts (simulado_id, aluno_id, finalizado_em) VALUES (?, ?, now())", outro, maria);

        get("/api/aluno/desempenho/assuntos", maria)
                .andExpect(jsonPath("$.respostas").value(2))
                .andExpect(jsonPath("$.acertos").value(0))
                .andExpect(jsonPath("$.assuntos[0].nome").value("Atomística"))
                .andExpect(jsonPath("$.assuntos[0].de_simulado").value(2));
    }

    @Test
    void quemNaoRespondeuNadaRecebeADevolutivaVazia() throws Exception {
        get("/api/aluno/desempenho/assuntos", maria)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respostas").value(0))
                .andExpect(jsonPath("$.percentual").doesNotExist())
                .andExpect(jsonPath("$.assuntos").isEmpty())
                .andExpect(jsonPath("$.onde_revisar").isEmpty());
    }

    // --- a turma -----------------------------------------------------------------

    @Test
    void aTurmaMostraOsAssuntosEAsQuestoesDaAulaComOQueCadaUmMarcou() throws Exception {
        var item1 = jdbc.queryForObject("SELECT id FROM items WHERE questao_id = ?", Integer.class, q1);
        responderNaAula(item1, "A", maria);

        get("/api/admin/turmas/Extensivo 2027/devolutiva", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunos").value(2))
                .andExpect(jsonPath("$.responderam").value(2))
                .andExpect(jsonPath("$.geral.respostas").value(7))
                .andExpect(jsonPath("$.geral.assuntos[0].nome").value("Estequiometria"))
                .andExpect(jsonPath("$.geral.assuntos[0].respostas").value(4))
                .andExpect(jsonPath("$.geral.assuntos[0].acertos").value(1))
                // A visão da turma não recomenda vídeo: isso é do aluno.
                .andExpect(jsonPath("$.geral.onde_revisar").isEmpty())
                .andExpect(jsonPath("$.questoes_da_aula.length()").value(4))
                // A mais errada primeiro: Q2, que só o aluno respondeu, e errou.
                .andExpect(jsonPath("$.questoes_da_aula[0].questao_id").value(q2))
                .andExpect(jsonPath("$.questoes_da_aula[0].percentual").value(0.0))
                .andExpect(jsonPath("$.questoes_da_aula[0].distribuicao.C").value(1))
                .andExpect(jsonPath("$.questoes_da_aula[0].gabarito").value("A"))
                .andExpect(jsonPath("$.questoes_da_aula[0].topico").value("Mol"))
                .andExpect(jsonPath("$.questoes_da_aula[1].questao_id").value(q1))
                .andExpect(jsonPath("$.questoes_da_aula[1].respostas").value(2))
                .andExpect(jsonPath("$.questoes_da_aula[1].distribuicao.A").value(1))
                .andExpect(jsonPath("$.questoes_da_aula[1].distribuicao.B").value(1))
                .andExpect(jsonPath("$.questoes_da_aula[1].modulo").value("K01"));
    }

    @Test
    void respostaDeAlunoDeOutraTurmaNaoEntraNaContaDaTurma() throws Exception {
        get("/api/admin/turmas/Intensivo 2027/devolutiva", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunos").value(0))
                .andExpect(jsonPath("$.geral.respostas").value(0))
                .andExpect(jsonPath("$.questoes_da_aula").isEmpty());
    }

    // --- quem pode ---------------------------------------------------------------

    @Test
    void cadaUmNaSuaVisao() throws Exception {
        get("/api/admin/alunos/" + ALUNO + "/assuntos", ALUNO).andExpect(status().isForbidden());
        get("/api/admin/turmas/Extensivo 2027/devolutiva", ALUNO).andExpect(status().isForbidden());
        get("/api/aluno/desempenho/assuntos", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("visão do aluno")));
    }
}
