package br.com.plataforma.portal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O menu do aluno é montado pelo professor, por turma: feature + categoria (decisão 0009). */
class MenuTest extends BaseDoPortal {

    private static final String MENU_DO_QUADRO = """
            {"botoes": [
              {"rotulo": "Lives", "funcionalidade": "aulas", "categoria": "Aula"},
              {"rotulo": "Extensivo 2027", "funcionalidade": "CURSO", "categoria": "Extensivo"},
              {"rotulo": "Monitoria online", "funcionalidade": "AULAS", "categoria": " Monitoria "},
              {"rotulo": "Simulados Rodmelo", "funcionalidade": "SIMULADOS", "categoria": "Rodmelo"},
              {"rotulo": "Apostilas e listas", "funcionalidade": "MATERIAIS"}]}""";

    @BeforeEach
    void senhas() {
        comSenhas();
    }

    @Test
    void semMenuMontadoValeODeSempre() throws Exception {
        get("/api/aluno/menu", ALUNO)
                .andExpect(jsonPath("$[*].rotulo").value(Matchers.contains("Curso", "Simulados", "Aulas ao vivo", "Materiais")))
                .andExpect(jsonPath("$[0].ao_vivo").doesNotExist());
        get("/api/admin/turmas/Extensivo 2027/menu", ADMIN).andExpect(jsonPath("$.padrao").value(true));
    }

    @Test
    void oProfessorMontaOAlunoVeNaOrdemEVazioVoltaAoDeSempre() throws Exception {
        put("/api/admin/turmas/Extensivo 2027/menu", MENU_DO_QUADRO, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.padrao").value(false))
                .andExpect(jsonPath("$.botoes[2].categoria").value("Monitoria"))
                .andExpect(jsonPath("$.botoes[4].categoria").doesNotExist());

        get("/api/aluno/menu", ALUNO)
                .andExpect(jsonPath("$[*].rotulo").value(Matchers.contains(
                        "Lives", "Extensivo 2027", "Monitoria online", "Simulados Rodmelo", "Apostilas e listas")))
                .andExpect(jsonPath("$[0].funcionalidade").value("AULAS"));

        put("/api/admin/turmas/Extensivo 2027/menu", "{\"botoes\": []}", ADMIN)
                .andExpect(jsonPath("$.padrao").value(true));
        get("/api/aluno/menu", ALUNO).andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void botaoSemNomeOuSemDestinoERecusadoENadaMuda() throws Exception {
        put("/api/admin/turmas/Extensivo 2027/menu", """
                {"botoes": [{"rotulo": "Agenda", "funcionalidade": "AGENDA"}]}""", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("CURSO (aulas gravadas), AULAS")));
        put("/api/admin/turmas/Extensivo 2027/menu", """
                {"botoes": [{"rotulo": " ", "funcionalidade": "CURSO"}]}""", ADMIN)
                .andExpect(status().isBadRequest());
        get("/api/admin/turmas/Extensivo 2027/menu", ADMIN).andExpect(jsonPath("$.padrao").value(true));
        // O aluno não monta menu.
        put("/api/admin/turmas/Extensivo 2027/menu", MENU_DO_QUADRO, ALUNO).andExpect(status().isForbidden());
    }

    @Test
    void copiarLevaOMenuDeUmaTurmaParaOutraPeloChatTambem() throws Exception {
        put("/api/admin/turmas/Extensivo 2027/menu", MENU_DO_QUADRO, ADMIN).andExpect(status().isOk());

        comando("copiar_menu", """
                {"de": "Extensivo 2027", "para": "Intensivo 2027"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turma").value("Intensivo 2027"))
                .andExpect(jsonPath("$.botoes.length()").value(5));
        comando("listar_menu", """
                {"turma": "Intensivo 2027"}""")
                .andExpect(jsonPath("$.botoes[3].rotulo").value("Simulados Rodmelo"));
        comando("definir_menu", """
                {"turma": "Intensivo 2027", "botoes": [{"rotulo": "Só o curso", "funcionalidade": "CURSO"}]}""")
                .andExpect(jsonPath("$.botoes.length()").value(1));
    }

    // --- o aviso de ao vivo --------------------------------------------------------

    private int aulaPublicada(String categoria, Integer submodulo) throws Exception {
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Plantão", "inicio_em": "%s", "minutos": 60, "turmas": ["Extensivo 2027"],
                 "categoria": "%s", "submodulo_id": %s}"""
                .formatted(Instant.now().plus(5, ChronoUnit.MINUTES), categoria, submodulo), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoria").value(categoria))
                .andReturn().getResponse().getContentAsString();
        var aula = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());
        return aula;
    }

    @Test
    void aAulaAcendeOBotaoDaCategoriaDelaEODoCapituloOndeMora() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01", "categoria": "Extensivo"}""").andExpect(status().isOk());
        var sub = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        put("/api/admin/turmas/Extensivo 2027/menu", MENU_DO_QUADRO, ADMIN).andExpect(status().isOk());

        // Monitoria sem capítulo, com a sala aberta: só "Monitoria online", e ainda em breve.
        var monitoria = aulaPublicada("Monitoria", null);
        get("/api/aluno/menu", ALUNO)
                .andExpect(jsonPath("$[0].ao_vivo").doesNotExist())
                .andExpect(jsonPath("$[1].ao_vivo").doesNotExist())
                .andExpect(jsonPath("$[2].ao_vivo").value("EM_BREVE"));

        // O professor iniciou: agora é ao vivo.
        jdbc.update("UPDATE live_classes SET iniciada_em = now() WHERE id = ?", monitoria);
        get("/api/aluno/menu", ALUNO).andExpect(jsonPath("$[2].ao_vivo").value("AGORA"));

        // Aula do capítulo K01 (categoria Extensivo): acende "Lives" e "Extensivo 2027".
        aulaPublicada("Aula", sub);
        get("/api/aluno/menu", ALUNO)
                .andExpect(jsonPath("$[0].ao_vivo").value("EM_BREVE"))
                .andExpect(jsonPath("$[1].ao_vivo").value("EM_BREVE"))
                .andExpect(jsonPath("$[3].ao_vivo").doesNotExist());
        get("/api/aluno/conteudo", ALUNO).andExpect(jsonPath("$[0].modulos[0].categoria").value("Extensivo"));
        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$[*].categoria")
                .value(Matchers.containsInAnyOrder("Monitoria", "Aula")));
    }
}
