package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A linha de questão dentro do módulo: o professor monta, o aluno responde uma vez, e o gabarito
 * só sai do backend depois da resposta.
 */
class QuestaoNaAulaTest extends BaseDoPortal {

    private static final String NOVA = """
            {"nome": "Q04", "nova": {"enunciado": "Qual a massa de 2 mol de água?",
              "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"},
              "gabarito": "A", "resolucao_comentada": "2 x 18 = 36.", "vimeo_id": "920000202"}}""";

    private int apostila;
    private int alunoDoIntensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
    }

    /** A linha que o professor acabou de montar pelo portal. */
    private int linha() throws Exception {
        var corpo = post("/api/admin/submodulos/" + apostila + "/questao", NOVA, ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Integer>read(corpo, "$.id");
    }

    // --- o professor monta -----------------------------------------------------

    @Test
    void aQuestaoCriadaNoPortalJaSaiPublicadaComAAprovacaoDoProfessor() throws Exception {
        post("/api/admin/submodulos/" + apostila + "/questao", NOVA, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Q04"))
                .andExpect(jsonPath("$.status").value("PUBLICADO"))
                .andExpect(jsonPath("$.questao.status").value("PUBLICADO"))
                .andExpect(jsonPath("$.video_id").value(nullValue()));

        // Publicou direto, mas não por fora da regra: a aprovação humana está gravada.
        assertThat(contar("drafts WHERE status = 'PUBLICADO' AND aprovado_por_id = ? AND aprovado_via = 'PORTAL'",
                ADMIN)).isEqualTo(1);

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].nome").value("Questões da apostila"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].nome").value("Q04"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].questao.respondida").value(false));
    }

    @Test
    void semNomeALinhaViraQuestaoN() throws Exception {
        var semNome = NOVA.replace("\"nome\": \"Q04\", ", "");
        post("/api/admin/submodulos/" + apostila + "/questao", semNome, ADMIN)
                .andExpect(jsonPath("$.nome").value("Questão 1"));
        post("/api/admin/submodulos/" + apostila + "/questao", semNome.replace("2 mol", "3 mol"), ADMIN)
                .andExpect(jsonPath("$.nome").value("Questão 2"));
    }

    @Test
    void aBuscaDoBancoAchaAQuestaoPeloNumeroComOuSemCerquilha() throws Exception {
        var massa = criarQuestao("Calcule a massa molar", "C", "MEDIA", "PUBLICADO");
        var outra = criarQuestao("Uma amostra de %d g de calcário".formatted(massa), "A", "MEDIA", "PUBLICADO");
        var questoes = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/questoes")
                .param("status", "PUBLICADO").cookie(sessao(ADMIN));

        // Com a cerquilha, é o número da questão e mais nada.
        mvc.perform(questoes.param("busca", "#" + massa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questao_id").value(massa));
        // Sem ela, o número também pode estar no enunciado: a questão daquele número vem na frente.
        get("/api/admin/questoes?status=PUBLICADO&busca=" + massa, ADMIN)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].questao_id").value(massa))
                .andExpect(jsonPath("$[1].questao_id").value(outra));
        get("/api/admin/questoes?busca=massa molar", ADMIN)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questao_id").value(massa));
    }

    @Test
    void questaoDoAcervoComNomeEntraComONomeDado() throws Exception {
        var questao = criarQuestao("Calcule a massa molar", "C", "MEDIA", "PUBLICADO");
        post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d, \"nome\": \"Q05\"}".formatted(questao), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Q05"));
    }

    @Test
    void questaoDoAcervoViraLinhaMasNaoDuasVezesNoMesmoSubmodulo() throws Exception {
        var questao = criarQuestao("Calcule a massa molar", "C", "MEDIA", "PUBLICADO");

        post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questao.questao_id").value(questao));
        post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("já tem esta questão")));
    }

    @Test
    void questaoComFiguraPendenteNaoVaiParaAAula() throws Exception {
        var questao = criarQuestao("Veja a figura", "A", "MEDIA", "PUBLICADO");
        jdbc.update("UPDATE questions SET imagem_pendente = true WHERE id = ?", questao);

        post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("figura pendente")));
        assertThat(contar("items")).isZero();
    }

    // --- o aluno responde ------------------------------------------------------

    @Test
    void antesDeResponderOGabaritoNemSaiDoBackend() throws Exception {
        var item = linha();

        get("/api/aluno/itens/" + item + "/questao", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enunciado").value("Qual a massa de 2 mol de água?"))
                .andExpect(jsonPath("$.alternativas.D").value("34 g"))
                .andExpect(jsonPath("$.alternativas.E").doesNotExist())
                .andExpect(jsonPath("$.respondida").value(false))
                .andExpect(jsonPath("$.gabarito").value(nullValue()))
                .andExpect(jsonPath("$.resolucao_comentada").value(nullValue()))
                .andExpect(jsonPath("$.resolucao").value(nullValue()));
    }

    @Test
    void responderDevolveACorrecaoEGravaOQueOAlunoMarcou() throws Exception {
        var item = linha();

        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"b\"}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondida").value(true))
                .andExpect(jsonPath("$.marcada").value("B"))
                .andExpect(jsonPath("$.correta").value(false))
                .andExpect(jsonPath("$.gabarito").value("A"))
                .andExpect(jsonPath("$.resolucao_comentada").value("2 x 18 = 36."))
                .andExpect(jsonPath("$.resolucao.vimeo_id").value("920000202"))
                .andExpect(jsonPath("$.resolucao.bloqueado").value(false));

        assertThat(contar("item_answers WHERE item_id = ? AND aluno_id = ? AND alternativa_marcada = 'B' AND NOT correta",
                item, ALUNO)).isEqualTo(1);

        // Quem volta à linha encontra a correção, e a lista marca a questão como feita.
        get("/api/aluno/itens/" + item + "/questao", ALUNO)
                .andExpect(jsonPath("$.marcada").value("B"))
                .andExpect(jsonPath("$.gabarito").value("A"));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].questao.respondida").value(true))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].questao.correta").value(false));
    }

    @Test
    void marcouValeu() throws Exception {
        var item = linha();
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"B\"}", ALUNO).andExpect(status().isOk());

        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"A\"}", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Você já respondeu esta questão: vale a primeira resposta."));

        assertThat(contar("item_answers WHERE item_id = ?", item)).isEqualTo(1);
        assertThat(contar("item_answers WHERE item_id = ? AND alternativa_marcada = 'B'", item)).isEqualTo(1);
    }

    @Test
    void alternativaQueAQuestaoNaoTemERecusada() throws Exception {
        var item = linha();

        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"E\"}", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esta questão não tem a alternativa E."));
        assertThat(contar("item_answers")).isZero();
    }

    @Test
    void alunoDeOutraTurmaNaoAbreNemResponde() throws Exception {
        var item = linha();

        get("/api/aluno/itens/" + item + "/questao", alunoDoIntensivo).andExpect(status().isNotFound());
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"A\"}", alunoDoIntensivo)
                .andExpect(status().isNotFound());
        assertThat(contar("item_answers")).isZero();
    }

    @Test
    void oProfessorVeAPreviaComGabaritoENaoResponde() throws Exception {
        var item = linha();

        get("/api/aluno/itens/" + item + "/questao", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gabarito").value("A"))
                .andExpect(jsonPath("$.respondida").value(false));
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"A\"}", ADMIN)
                .andExpect(status().isForbidden());
    }

    // --- o reuso é avisado, não bloqueado ----------------------------------------

    @Test
    void questaoQueEstaEmAulaEntraNoSimuladoComAviso() throws Exception {
        linha();
        var questao = jdbc.queryForObject("SELECT id FROM questions", Integer.class);

        comando("detalhar_questao", "{\"questao\": \"%d\"}".formatted(questao))
                .andExpect(jsonPath("$.aulas[0]").value("K01 › Questões da apostila › Q04"));
        comando("buscar_questoes", "{}")
                .andExpect(jsonPath("$[0].aulas[0]").value("K01 › Questões da apostila › Q04"));

        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 01", "questoes": [%d]}""".formatted(questao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado.questoes[0].aulas[0]").value("K01 › Questões da apostila › Q04"))
                .andExpect(jsonPath("$.simulado.avisos[0]").value(containsString("também está em aula")));
    }

    // --- pelo Claude continua sendo rascunho --------------------------------------

    @Test
    void peloMcpAsQuestoesNascemEmRascunhoESoChegamAoAlunoDepoisDoOk() throws Exception {
        var corpo = comando("criar_questoes_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "questoes": [
                   {"numero": 4, "enunciado": "Quantos elétrons tem o Ca²⁺?",
                    "alternativas": {"A": "16", "B": "18", "C": "20", "D": "22", "E": "40"}, "gabarito": "B"},
                   {"nome": "Desafio", "enunciado": "E o Na⁺?",
                    "alternativas": {"A": "10", "B": "11", "C": "12", "D": "23"}, "gabarito": "A"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aviso").value("Nada foi publicado. O rascunho precisa da aprovação do professor."))
                .andExpect(jsonPath("$.rascunho.itens[*].nome").value(Matchers.contains("Q04", "Desafio")))
                .andExpect(jsonPath("$.rascunho.itens[0].status").value("RASCUNHO"))
                .andExpect(jsonPath("$.rascunho.itens[0].questao_id").isNumber())
                .andExpect(jsonPath("$.rascunho.questoes.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        int rascunho = JsonPath.<Integer>read(corpo, "$.rascunho.rascunho_id");
        int item = JsonPath.<Integer>read(corpo, "$.rascunho.itens[0].item_id");

        get("/api/aluno/conteudo", ALUNO).andExpect(jsonPath("$").isEmpty());
        get("/api/aluno/itens/" + item + "/questao", ALUNO).andExpect(status().isNotFound());
        // O agente não publica o que ele mesmo propôs.
        comando("publicar_rascunho", "{\"rascunho\": %d}".formatted(rascunho)).andExpect(status().isBadRequest());
        assertThat(contar("items WHERE status = 'PUBLICADO'")).isZero();

        post("/api/admin/rascunhos/" + rascunho + "/publicar", "{}", ADMIN).andExpect(status().isOk());

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Q04", "Desafio")));
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"B\"}", ALUNO)
                .andExpect(jsonPath("$.correta").value(true));
    }

    @Test
    void questaoDeOutroRascunhoNaoViraLinha() throws Exception {
        var emRascunho = criarQuestao("Ainda não aprovada", "A", "MEDIA", "RASCUNHO");

        comando("criar_questoes_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Questões da apostila",
                 "questoes": [%d]}""".formatted(emRascunho))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ainda é rascunho")));
        assertThat(contar("items")).isZero();
        assertThat(contar("drafts")).isZero();
    }

    // --- o resto que o professor monta no portal também sai publicado ---------------

    @Test
    void videoColocadoPeloPortalJaSaiPublicado() throws Exception {
        post("/api/admin/turmas/Extensivo 2027/modulos/K01/submodulos/Aulas/itens", """
                {"videos": [{"vimeo_id": "111111", "titulo": "Aula 1 — cadeias carbônicas"}]}""", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rascunho.publicado").value(true))
                .andExpect(jsonPath("$.rascunho.itens[0].status").value("PUBLICADO"));

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].nome").value("Aula 1 — cadeias carbônicas"));
    }

    @Test
    void questaoAvulsaCadastradaNoPortalEntraNoAcervoPublicada() throws Exception {
        post("/api/admin/questoes", """
                {"enunciado": "Defina entalpia", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4", "E": "5"},
                 "gabarito": "E"}""", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicado").value(true));

        assertThat(contar("questions WHERE status = 'PUBLICADO'")).isEqualTo(1);
    }
}
