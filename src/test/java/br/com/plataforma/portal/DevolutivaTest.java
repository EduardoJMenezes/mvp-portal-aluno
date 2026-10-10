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

    // --- onde revisar, no curso do próprio aluno -----------------------------------

    @Test
    void ondeRevisarLevaALinhaDoCursoDoAlunoPeloAssuntoDoConteudo() throws Exception {
        var aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        // Um vídeo e um PDF de Mol, um vídeo só de Estequiometria e um de outro assunto.
        var aulaDeMol = criarVideo("700001", "Aula de mol");
        var videoDeMol = criarItem(aulas, aulaDeMol, "Aula de mol", 1, "PUBLICADO");
        // A mesma aula noutro módulo do curso dele: é o mesmo vídeo, e a recomendação a mostra uma vez só.
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K02\"}").andExpect(status().isOk());
        var aulasDoK02 = jdbc.queryForObject("""
                SELECT s.id FROM submodules s JOIN modules m ON m.id = s.modulo_id
                 WHERE m.nome = 'K02' AND s.nome = 'Aulas'""", Integer.class);
        criarItem(aulasDoK02, aulaDeMol, "Aula de mol (revisão)", 1, "PUBLICADO");
        var videoDoAssunto = criarItem(aulas, criarVideo("700002", "Visão geral"), "Visão geral", 2, "PUBLICADO");
        var deOutro = criarItem(aulas, criarVideo("700003", "Rutherford"), "Rutherford", 3, "PUBLICADO");
        var material = jdbc.queryForObject("""
                INSERT INTO materials (titulo, tipo, tamanho, status, criado_por_id, conteudo)
                VALUES ('Lista de mol', 'application/pdf', 4, 'PUBLICADO', ?, decode('25504446', 'hex')) RETURNING id""",
                Integer.class, ADMIN);
        var pdf = com.jayway.jsonpath.JsonPath.<Integer>read(post("/api/admin/submodulos/" + aulas + "/pdf",
                "{\"material\": %d}".formatted(material), ADMIN).andReturn().getResponse().getContentAsString(), "$.item_id");
        put("/api/admin/itens/" + videoDeMol + "/assunto", "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN).andExpect(status().isOk());
        put("/api/admin/itens/" + videoDoAssunto + "/assunto", "{\"assunto\": \"Estequiometria\"}", ADMIN).andExpect(status().isOk());
        put("/api/admin/itens/" + deOutro + "/assunto", "{\"assunto\": \"Atomística\"}", ADMIN).andExpect(status().isOk());
        put("/api/admin/itens/" + pdf + "/assunto", "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN).andExpect(status().isOk());
        // Uma questão de Mol que o aluno ainda não fez.
        var porFazer = naAula(questao("Q7", jdbc.queryForObject("SELECT id FROM subjects WHERE nome = 'Estequiometria'", Integer.class),
                jdbc.queryForObject("SELECT id FROM subtopics WHERE nome = 'Mol'", Integer.class)));

        // Mol é o ponto mais fraco (errou as duas): o exato primeiro, depois o que é só do assunto.
        get("/api/aluno/desempenho/assuntos", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onde_revisar[0].topico").value("Mol"))
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[?(@.tipo == 'VIDEO')].item_id")
                        .value(org.hamcrest.Matchers.contains(videoDeMol, videoDoAssunto)))
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[?(@.tipo == 'PDF')].item_id").value(org.hamcrest.Matchers.contains(pdf)))
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[?(@.tipo == 'QUESTAO')].item_id").value(org.hamcrest.Matchers.contains(porFazer)))
                // As duas que ele errou levam à linha, onde está a resolução.
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[?(@.tipo == 'ERRO')].item_id").value(org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[0].modulo").value("K01"))
                // O vídeo de outro assunto não entra.
                .andExpect(jsonPath("$.onde_revisar[0].no_curso[?(@.item_id == %d)]".formatted(deOutro)).isEmpty())
                // O que já veio como linha do curso não se repete na lista do acervo.
                .andExpect(jsonPath("$.onde_revisar[0].videos").isEmpty());
    }
}
