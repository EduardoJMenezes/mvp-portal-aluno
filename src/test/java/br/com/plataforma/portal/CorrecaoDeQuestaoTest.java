package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A questão publicada se corrige mesmo com simulado aberto: o aluno recebe a versão nova na próxima
 * leitura, o acerto de quem já respondeu é refeito em silêncio, e a alternativa removida deixa de
 * valer como resposta.
 */
class CorrecaoDeQuestaoTest extends BaseDoPortal {

    private int questao;
    private int prova;
    private int carla;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        carla = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(carla, 10);
        questao = criarQuestao("Qual a massa de 2 mol de água?", "A", "MEDIA", "PUBLICADO");
        prova = criarSimuladoAberto("Simulado 03", 10, questao, criarQuestao("Outra questão", "B", "MEDIA", "PUBLICADO"));
    }

    private void marcar(int aluno, String letra) throws Exception {
        get("/api/aluno/simulados/" + prova, aluno).andExpect(status().isOk());
        post("/api/aluno/simulados/" + prova + "/responder",
                "{\"questao_id\": %d, \"alternativa\": \"%s\"}".formatted(questao, letra), aluno)
                .andExpect(status().isOk());
    }

    private boolean acertou(int aluno) {
        return jdbc.queryForObject("""
                SELECT a.correta FROM exam_answers a JOIN exam_attempts t ON t.id = a.tentativa_id
                WHERE t.aluno_id = ? AND a.questao_id = ?""", Boolean.class, aluno, questao);
    }

    private org.springframework.test.web.servlet.ResultActions corrigir(String mudanca) throws Exception {
        return patch("/api/admin/questoes/" + questao, mudanca, ADMIN);
    }

    @Test
    void gabaritoCorrigidoRefazOAcertoDeQuemJaRespondeu() throws Exception {
        marcar(ALUNO, "A");
        marcar(carla, "C");
        assertThat(acertou(ALUNO)).isTrue();
        assertThat(acertou(carla)).isFalse();

        corrigir("{\"gabarito\": \"C\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gabarito").value("C"))
                .andExpect(jsonPath("$.respostas").value(2))
                .andExpect(jsonPath("$.historico[0].resumo").value("Gabarito de A para C; 2 respostas mudaram de acerto."))
                .andExpect(jsonPath("$.historico[0].antes").value(containsString("Gabarito: A")));

        assertThat(acertou(ALUNO)).isFalse();
        assertThat(acertou(carla)).isTrue();
        // A resposta do aluno continua a mesma: só o acerto foi refeito.
        get("/api/aluno/simulados/" + prova, ALUNO).andExpect(jsonPath("$.questoes[0].marcada").value("A"));
    }

    @Test
    void oAlunoRecebeOTextoCorrigidoNaProximaLeituraComARespostaMantida() throws Exception {
        marcar(ALUNO, "B");
        corrigir("{\"enunciado\": \"Qual a massa de 3 mol de água?\", \"alternativas\": {\"B\": \"54 g\"}}")
                .andExpect(status().isOk());

        get("/api/aluno/simulados/" + prova, ALUNO)
                .andExpect(jsonPath("$.questoes[0].enunciado").value("Qual a massa de 3 mol de água?"))
                .andExpect(jsonPath("$.questoes[0].alternativas.B").value("54 g"))
                .andExpect(jsonPath("$.questoes[0].marcada").value("B"));
    }

    @Test
    void alternativaRemovidaDeixaBuracoEInvalidaARespostaDeQuemAMarcou() throws Exception {
        marcar(ALUNO, "C");
        marcar(carla, "D");

        // Em branco é remover. As outras letras não mudam: ficam A, B, D e E.
        corrigir("{\"alternativas\": {\"C\": \"\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alternativas.C").doesNotExist())
                .andExpect(jsonPath("$.alternativas.D").value("alternativa D"))
                .andExpect(jsonPath("$.historico[0].resumo").value("Alternativa C removida."));

        // Quem marcou a D continua com a D. Quem marcou a C vê a questão sem resposta.
        get("/api/aluno/simulados/" + prova, carla).andExpect(jsonPath("$.questoes[0].marcada").value("D"));
        get("/api/aluno/simulados/" + prova, ALUNO)
                .andExpect(jsonPath("$.questoes[0].marcada").value(nullValue()))
                .andExpect(jsonPath("$.questoes[0].alternativas.C").doesNotExist());

        // Entregar assim é recusado, com um recado que não fala em correção.
        post("/api/aluno/simulados/" + prova + "/entregar", "{}", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A questão 1 está com uma resposta inválida. Volte a ela e marque de novo."));
        post("/api/aluno/simulados/" + prova + "/responder", "{\"questao_id\": %d, \"alternativa\": \"C\"}".formatted(questao), ALUNO)
                .andExpect(status().isBadRequest());

        marcar(ALUNO, "A");
        post("/api/aluno/simulados/" + prova + "/entregar", "{}", ALUNO).andExpect(status().isOk());
        assertThat(acertou(ALUNO)).isTrue();
    }

    @Test
    void tirarOGabaritoExigeOutroEASobraNuncaFicaComMenosDeDuas() throws Exception {
        corrigir("{\"alternativas\": {\"A\": \"\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O gabarito é A, mas a questão não tem a alternativa A."));
        corrigir("{\"alternativas\": {\"A\": \"\"}, \"gabarito\": \"B\"}").andExpect(status().isOk());

        corrigir("{\"alternativas\": {\"C\": \"\", \"D\": \"\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alternativas.length()").value(2));
        corrigir("{\"alternativas\": {\"E\": \"\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A questão precisa de pelo menos duas alternativas."));
    }

    /** Remover alternativa é correção de questão publicada: no rascunho a regra de A a D não muda. */
    @Test
    void emRascunhoNadaDissoVale() throws Exception {
        var rascunho = criarQuestao("Ainda em rascunho", "A", "MEDIA", "RASCUNHO");
        patch("/api/admin/questoes/" + rascunho, "{\"alternativas\": {\"C\": \"\"}, \"enunciado\": \"Outro texto\"}", ADMIN)
                .andExpect(status().isOk())
                // A letra continua lá (vazia, como sempre foi no rascunho), e nada entra no histórico.
                .andExpect(jsonPath("$.alternativas.C").exists())
                .andExpect(jsonPath("$.historico.length()").value(0));
    }

    @Test
    void aQuestaoQuePerdeuUmaAlternativaAindaEntraNaAulaEOAcertoDeLaTambemERefeito() throws Exception {
        corrigir("{\"alternativas\": {\"C\": \"\"}}").andExpect(status().isOk());

        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        var apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
        var linha = JsonPath.<Integer>read(post("/api/admin/submodulos/" + apostila + "/questao",
                "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        jdbc.update("INSERT INTO item_answers (item_id, aluno_id, questao_id, alternativa_marcada, correta) VALUES (?, ?, ?, 'A', true)",
                linha, ALUNO, questao);
        jdbc.update("INSERT INTO item_answers (item_id, aluno_id, questao_id, alternativa_marcada, correta) VALUES (?, ?, ?, 'D', false)",
                linha, carla, questao);

        corrigir("{\"gabarito\": \"D\"}")
                .andExpect(jsonPath("$.historico[0].resumo").value("Gabarito de A para D; 2 respostas mudaram de acerto."));
        assertThat(jdbc.queryForObject("SELECT correta FROM item_answers WHERE aluno_id = ?", Boolean.class, ALUNO)).isFalse();
        assertThat(jdbc.queryForObject("SELECT correta FROM item_answers WHERE aluno_id = ?", Boolean.class, carla)).isTrue();
    }
}
