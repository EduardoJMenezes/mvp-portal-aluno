package br.com.plataforma.portal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O módulo mora numa biblioteca e as turmas o recebem (decisão 0011). Dentro dele, uma aula pode
 * ser só de algumas turmas.
 */
class BibliotecaTest extends BaseDoPortal {

    private int alunoDoIntensivo;
    private int aula1;
    private int aula2;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);

        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        var sub = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        aula1 = criarItem(sub, criarVideo("111111", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        aula2 = criarItem(sub, criarVideo("222222", "Aula 2"), "Aula 2", 2, "PUBLICADO");
    }

    @Test
    void oModuloAtribuidoAVariasTurmasAparecePraTodas() throws Exception {
        get("/api/aluno/conteudo", alunoDoIntensivo).andExpect(jsonPath("$").isEmpty());

        comando("atribuir_turmas", """
                {"modulo": "K01", "turmas": ["Extensivo 2027", "Intensivo 2027"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turmas").value(Matchers.contains("Extensivo 2027", "Intensivo 2027")));

        get("/api/aluno/conteudo", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].turma").value("Intensivo 2027"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens.length()").value(2))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].video.bloqueado").value(false));
    }

    @Test
    void aAulaRestritaSoApareceParaAsTurmasDela() throws Exception {
        comando("atribuir_turmas", """
                {"modulo": "K01", "turmas": ["Extensivo 2027", "Intensivo 2027"]}""").andExpect(status().isOk());
        put("/api/admin/itens/" + aula2 + "/turmas", "{\"turmas\": [\"Intensivo 2027\"]}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turmas[0]").value("Intensivo 2027"));

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Aula 1")));
        get("/api/aluno/conteudo", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome")
                        .value(Matchers.contains("Aula 1", "Aula 2")));

        // O professor vê as duas, com a restrição de cada uma.
        get("/api/admin/modulos?turma=Extensivo 2027", ADMIN)
                .andExpect(jsonPath("$[0].turmas.length()").value(2))
                .andExpect(jsonPath("$[0].submodulos[0].itens[1].turmas[0]").value("Intensivo 2027"));

        // Lista vazia devolve a aula a todos.
        put("/api/admin/itens/" + aula2 + "/turmas", "{\"turmas\": []}", ADMIN).andExpect(status().isOk());
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens.length()").value(2));
    }

    @Test
    void aulaSoDeUmaTurmaLevaOModuloAQuemNaoORecebe() throws Exception {
        // K01 é só do Extensivo, mas a Aula 2 é dada também ao Intensivo.
        comando("atribuir_turmas", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas", "item": "Aula 2",
                 "turmas": ["Intensivo 2027"]}""").andExpect(status().isOk());

        get("/api/aluno/conteudo", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].modulos[0].nome").value("K01"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Aula 2")));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Aula 1")));
    }

    @Test
    void copiarLevaOsModulosEAsAulasRestritasParaOutraTurma() throws Exception {
        put("/api/admin/itens/" + aula2 + "/turmas", "{\"turmas\": [\"Extensivo 2027\"]}", ADMIN).andExpect(status().isOk());

        post("/api/admin/turmas/Intensivo 2027/modulos/copiar", "{\"de\": \"Extensivo 2027\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modulos").value(1))
                .andExpect(jsonPath("$.itens").value(1));

        get("/api/aluno/conteudo", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens.length()").value(2));
        get("/api/admin/biblioteca", ADMIN)
                .andExpect(jsonPath("$[0].turmas").value(Matchers.contains("Extensivo 2027", "Intensivo 2027")));
    }

    @Test
    void aMesmaTurmaNaoRecebeDoisModulosComOMesmoNome() throws Exception {
        comando("criar_modulo", """
                {"turma": "Intensivo 2027", "nome": "k01"}""").andExpect(status().isOk());
        comando("atribuir_turmas", """
                {"turma": "Extensivo 2027", "modulo": "K01", "turmas": ["Extensivo 2027", "Intensivo 2027"]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'Intensivo 2027' já tem um módulo chamado 'k01'."));
    }

    @Test
    void tirarATurmaDoModuloTiraDaTelaDoAlunoMenosAAulaQueEDela() throws Exception {
        comando("atribuir_turmas", """
                {"modulo": "K01", "turmas": ["Extensivo 2027", "Intensivo 2027"]}""").andExpect(status().isOk());
        put("/api/admin/itens/" + aula1 + "/turmas", "{\"turmas\": [\"Intensivo 2027\"]}", ADMIN).andExpect(status().isOk());

        put("/api/admin/modulos/K01/turmas", "{\"turmas\": [\"Extensivo 2027\"]}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turmas").value(Matchers.contains("Extensivo 2027")));

        get("/api/aluno/conteudo", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Aula 1")));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(Matchers.contains("Aula 2")));
    }

    /** A aula ao vivo das duas turmas grava no K01, que só o Extensivo recebe: o Intensivo assiste nas Lives. */
    @Test
    void quemNaoRecebeOModuloAssisteAGravacaoNasLives() throws Exception {
        var sub = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Revisão", "inicio_em": "2020-01-01T10:00:00Z", "turmas": ["Extensivo 2027", "Intensivo 2027"],
                 "submodulo_id": %d}""".formatted(sub), ADMIN).andReturn().getResponse().getContentAsString();
        var aula = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());
        post("/api/admin/aulas/" + aula + "/video", "{\"video\": \"https://vimeo.com/333333333\"}", ADMIN)
                .andExpect(status().isOk());

        get("/api/aluno/aulas", ALUNO)
                .andExpect(jsonPath("$[0].assistir.item_id").isNumber())
                .andExpect(jsonPath("$[0].video").doesNotExist());
        get("/api/aluno/aulas", alunoDoIntensivo)
                .andExpect(jsonPath("$[0].assistir").doesNotExist())
                .andExpect(jsonPath("$[0].video.vimeo_id").value("333333333"));
    }
}
