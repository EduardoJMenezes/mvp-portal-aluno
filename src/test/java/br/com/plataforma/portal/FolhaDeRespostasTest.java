package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A folha de respostas: o aluno resolve a apostila no papel e lança as letras de uma vez. Antes de
 * enviar, nenhuma questão da folha mostra o gabarito; depois, a folha volta corrigida.
 */
class FolhaDeRespostasTest extends BaseDoPortal {

    private int apostila;
    private List<Integer> linhas;
    private int alunoDoIntensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
        var corpo = comando("criar_questoes_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "questoes": [
                   {"numero": 1, "enunciado": "Um", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4", "E": "5"}, "gabarito": "B"},
                   {"numero": 2, "enunciado": "Dois", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4"}, "gabarito": "D"},
                   {"numero": 3, "enunciado": "Três", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4", "E": "5"}, "gabarito": "E"}]}""")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        linhas = JsonPath.read(corpo, "$.rascunho.itens[*].item_id");
    }

    private org.springframework.test.web.servlet.ResultActions ligar() throws Exception {
        return comando("definir_folha_de_respostas", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila"}""");
    }

    private String folha(String q1, String q2, String q3) {
        return """
                {"respostas": {"%d": "%s", "%d": "%s", "%d": "%s"}}""".formatted(
                linhas.get(0), q1, linhas.get(1), q2, linhas.get(2), q3);
    }

    @Test
    void oProfessorLigaAFolhaPeloClaudeEOCursoMostra() throws Exception {
        ligar().andExpect(status().isOk())
                .andExpect(jsonPath("$.folha_de_respostas").value(true))
                .andExpect(jsonPath("$.questoes").value(3))
                .andExpect(jsonPath("$.mensagem").value(containsString("lança as 3 letras de uma vez")));

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].folha_de_respostas").value(true));
        comando("listar_modulos", "{\"turma\": \"Extensivo 2027\"}")
                .andExpect(jsonPath("$[0].submodulos[?(@.nome == 'Questões da apostila')].folha_de_respostas")
                        .value(Matchers.contains(true)));
    }

    @Test
    void antesDeEnviarNadaDeGabaritoEResponderUmaPorUmaERecusado() throws Exception {
        ligar().andExpect(status().isOk());

        get("/api/aluno/submodulos/" + apostila + "/folha", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviada").value(false))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.acertos").value(nullValue()))
                .andExpect(jsonPath("$.linhas[*].nome").value(Matchers.contains("Q01", "Q02", "Q03")))
                .andExpect(jsonPath("$.linhas[1].letras").value(Matchers.contains("A", "B", "C", "D")))
                .andExpect(jsonPath("$.linhas[*].gabarito").value(Matchers.everyItem(nullValue())));
        get("/api/aluno/itens/" + linhas.get(0) + "/questao", ALUNO)
                .andExpect(jsonPath("$.gabarito").value(nullValue()));

        post("/api/aluno/itens/" + linhas.get(0) + "/responder", "{\"alternativa\": \"B\"}", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("é da folha de respostas")));
        assertThat(contar("item_answers")).isZero();
    }

    @Test
    void enviarGravaTudoDeUmaVezEDevolveAFolhaCorrigida() throws Exception {
        ligar().andExpect(status().isOk());

        post("/api/aluno/submodulos/" + apostila + "/folha", folha("b", "A", "E"), ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviada").value(true))
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.linhas[1].marcada").value("A"))
                .andExpect(jsonPath("$.linhas[1].correta").value(false))
                .andExpect(jsonPath("$.linhas[1].gabarito").value("D"));

        assertThat(contar("item_answers WHERE aluno_id = ?", ALUNO)).isEqualTo(3);
        assertThat(contar("item_answers WHERE aluno_id = ? AND correta", ALUNO)).isEqualTo(2);
        // Cada questão passa a mostrar a correção, e a árvore marca o que ele acertou.
        get("/api/aluno/itens/" + linhas.get(1) + "/questao", ALUNO)
                .andExpect(jsonPath("$.gabarito").value("D"))
                .andExpect(jsonPath("$.marcada").value("A"));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].questao.correta")
                        .value(Matchers.contains(true, false, true)));
    }

    @Test
    void aFolhaSoSaiInteiraEUmaVez() throws Exception {
        ligar().andExpect(status().isOk());

        post("/api/aluno/submodulos/" + apostila + "/folha", folha("B", "", "E"), ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Falta marcar Q02. A folha só é enviada com todas as questões marcadas."));
        post("/api/aluno/submodulos/" + apostila + "/folha", folha("B", "E", "E"), ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Q02 não tem a alternativa E."));
        assertThat(contar("item_answers")).isZero();

        post("/api/aluno/submodulos/" + apostila + "/folha", folha("B", "D", "E"), ALUNO).andExpect(status().isOk());
        post("/api/aluno/submodulos/" + apostila + "/folha", folha("A", "A", "A"), ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Você já enviou esta folha de respostas: vale o primeiro envio."));
        assertThat(contar("item_answers WHERE alternativa_marcada = 'A'")).isZero();
    }

    @Test
    void quemJaTinhaRespondidoLancaSoOQueFalta() throws Exception {
        post("/api/aluno/itens/" + linhas.get(0) + "/responder", "{\"alternativa\": \"C\"}", ALUNO).andExpect(status().isOk());
        ligar().andExpect(jsonPath("$.alunos_que_ja_responderam").value(1));

        post("/api/aluno/submodulos/" + apostila + "/folha", """
                {"respostas": {"%d": "D", "%d": "E"}}""".formatted(linhas.get(1), linhas.get(2)), ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviada").value(true))
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.linhas[0].marcada").value("C"));
    }

    @Test
    void semAFolhaLigadaOuForaDaTurmaNaoHaFolha() throws Exception {
        get("/api/aluno/submodulos/" + apostila + "/folha", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("não é folha de respostas")));

        ligar().andExpect(status().isOk());
        get("/api/aluno/submodulos/" + apostila + "/folha", alunoDoIntensivo).andExpect(status().isNotFound());
        post("/api/aluno/submodulos/" + apostila + "/folha", folha("B", "D", "E"), alunoDoIntensivo)
                .andExpect(status().isNotFound());
        assertThat(contar("item_answers")).isZero();
    }

    @Test
    void oProfessorVeOGabaritoNaPreviaENaoEnvia() throws Exception {
        ligar().andExpect(status().isOk());

        get("/api/aluno/submodulos/" + apostila + "/folha", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviada").value(false))
                .andExpect(jsonPath("$.linhas[*].gabarito").value(Matchers.contains("B", "D", "E")));
        post("/api/aluno/submodulos/" + apostila + "/folha", folha("B", "D", "E"), ADMIN)
                .andExpect(status().isForbidden());
    }

    @Test
    void desligarDevolveARespostaUmaPorUma() throws Exception {
        ligar().andExpect(status().isOk());
        comando("definir_folha_de_respostas", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila", "ativa": false}""")
                .andExpect(jsonPath("$.folha_de_respostas").value(false));

        post("/api/aluno/itens/" + linhas.get(0) + "/responder", "{\"alternativa\": \"B\"}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correta").value(true));
    }
}
