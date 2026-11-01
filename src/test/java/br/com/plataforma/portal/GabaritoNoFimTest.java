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
 * As questões da apostila com gabarito no fim: o aluno responde uma por uma na plataforma, sem saber
 * se acertou, e a correção de todas sai quando ele responde a última.
 */
class GabaritoNoFimTest extends BaseDoPortal {

    private List<Integer> linhas;
    private int alunoDoIntensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
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
        return comando("definir_gabarito_no_fim", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila"}""");
    }

    private org.springframework.test.web.servlet.ResultActions responder(int linha, String letra) throws Exception {
        return post("/api/aluno/itens/" + linhas.get(linha) + "/responder", "{\"alternativa\": \"%s\"}".formatted(letra), ALUNO);
    }

    @Test
    void oProfessorLigaPeloClaudeEOCursoMostra() throws Exception {
        ligar().andExpect(status().isOk())
                .andExpect(jsonPath("$.gabarito_no_fim").value(true))
                .andExpect(jsonPath("$.questoes").value(3))
                .andExpect(jsonPath("$.mensagem").value(containsString("só vê a correção, de todas, depois da última")));

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].gabarito_no_fim").value(true));
        comando("listar_modulos", "{\"turma\": \"Extensivo 2027\"}")
                .andExpect(jsonPath("$[0].submodulos[?(@.nome == 'Questões da apostila')].gabarito_no_fim")
                        .value(Matchers.contains(true)));
    }

    @Test
    void oAlunoVeAQuestaoInteiraEResponde() throws Exception {
        ligar().andExpect(status().isOk());

        get("/api/aluno/itens/" + linhas.get(0) + "/questao", ALUNO)
                .andExpect(jsonPath("$.enunciado").value("Um"))
                .andExpect(jsonPath("$.alternativas.E").value("5"))
                .andExpect(jsonPath("$.gabarito_no_fim").value(true))
                .andExpect(jsonPath("$.faltam").value(3))
                .andExpect(jsonPath("$.gabarito").value(nullValue()));

        // Respondeu: fica gravado, mas nada de acerto, gabarito nem resolução.
        responder(0, "B").andExpect(status().isOk())
                .andExpect(jsonPath("$.respondida").value(true))
                .andExpect(jsonPath("$.marcada").value("B"))
                .andExpect(jsonPath("$.correta").value(nullValue()))
                .andExpect(jsonPath("$.gabarito").value(nullValue()))
                .andExpect(jsonPath("$.resolucao_comentada").value(nullValue()))
                .andExpect(jsonPath("$.faltam").value(2));
        get("/api/aluno/itens/" + linhas.get(0) + "/questao", ALUNO)
                .andExpect(jsonPath("$.marcada").value("B"))
                .andExpect(jsonPath("$.gabarito").value(nullValue()));
        // A árvore marca como respondida, sem dizer se acertou.
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].questao.respondida").value(true))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].questao.correta").value(nullValue()));
        assertThat(contar("item_answers WHERE aluno_id = ? AND correta", ALUNO)).isEqualTo(1);
    }

    @Test
    void aUltimaRespostaSoltaACorrecaoDeTodas() throws Exception {
        ligar().andExpect(status().isOk());
        responder(0, "B").andExpect(status().isOk());
        responder(1, "A").andExpect(jsonPath("$.faltam").value(1));

        responder(2, "E").andExpect(status().isOk())
                .andExpect(jsonPath("$.faltam").value(0))
                .andExpect(jsonPath("$.correta").value(true))
                .andExpect(jsonPath("$.gabarito").value("E"));

        get("/api/aluno/itens/" + linhas.get(1) + "/questao", ALUNO)
                .andExpect(jsonPath("$.marcada").value("A"))
                .andExpect(jsonPath("$.correta").value(false))
                .andExpect(jsonPath("$.gabarito").value("D"));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].questao.correta")
                        .value(Matchers.contains(true, false, true)));
    }

    @Test
    void marcouValeuTambemComGabaritoNoFim() throws Exception {
        ligar().andExpect(status().isOk());
        responder(0, "B").andExpect(status().isOk());

        responder(0, "A").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Você já respondeu esta questão: vale a primeira resposta."));
        responder(1, "E").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esta questão não tem a alternativa E."));
    }

    @Test
    void oDesempenhoDoAlunoNaoContaOQueAindaEstaGuardado() throws Exception {
        jdbc.update("INSERT INTO subjects (nome) VALUES ('Soluções')");
        var assunto = jdbc.queryForObject("SELECT id FROM subjects WHERE nome = 'Soluções'", Integer.class);
        linhas.forEach(l -> jdbc.update("""
                INSERT INTO question_subjects (questao_id, assunto_id)
                SELECT questao_id, ? FROM items WHERE id = ?""", assunto, l));
        ligar().andExpect(status().isOk());

        responder(0, "B").andExpect(status().isOk());
        get("/api/aluno/desempenho/assuntos", ALUNO).andExpect(jsonPath("$.respostas").value(0));

        responder(1, "D").andExpect(status().isOk());
        responder(2, "A").andExpect(status().isOk());
        get("/api/aluno/desempenho/assuntos", ALUNO).andExpect(jsonPath("$.respostas").value(3));
        // O professor vê desde a primeira: a regra é do que o aluno vê.
        get("/api/admin/alunos/" + ALUNO + "/assuntos", ADMIN).andExpect(jsonPath("$.respostas").value(3));
    }

    @Test
    void oProfessorVeAPreviaComGabaritoENaoResponde() throws Exception {
        ligar().andExpect(status().isOk());

        get("/api/aluno/itens/" + linhas.get(0) + "/questao", ADMIN)
                .andExpect(jsonPath("$.gabarito").value("B"))
                .andExpect(jsonPath("$.respondida").value(false));
        post("/api/aluno/itens/" + linhas.get(0) + "/responder", "{\"alternativa\": \"B\"}", ADMIN)
                .andExpect(status().isForbidden());
    }

    @Test
    void desligarSoltaACorrecaoDoQueJaFoiRespondido() throws Exception {
        ligar().andExpect(status().isOk());
        responder(0, "C").andExpect(jsonPath("$.correta").value(nullValue()));

        comando("definir_gabarito_no_fim", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila", "ativa": false}""")
                .andExpect(jsonPath("$.gabarito_no_fim").value(false));

        get("/api/aluno/itens/" + linhas.get(0) + "/questao", ALUNO)
                .andExpect(jsonPath("$.correta").value(false))
                .andExpect(jsonPath("$.gabarito").value("B"))
                .andExpect(jsonPath("$.gabarito_no_fim").value(false));
        responder(1, "D").andExpect(jsonPath("$.correta").value(true));
    }
}
