package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O assunto de cada linha do curso. Ele é do conteúdo — a questão, o vídeo ou o PDF —, nunca da
 * linha: trocar pela linha troca lá, e não existe uma segunda informação para a mesma coisa.
 */
class AssuntoDasLinhasTest extends BaseDoPortal {

    private int modulo;
    private int aulas;
    private int apostila;
    private int video;
    private int linhaDoVideo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K03\"}").andExpect(status().isOk());
        modulo = jdbc.queryForObject("SELECT id FROM modules WHERE nome = 'K03'", Integer.class);
        aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
        comando("cadastrar_assunto", "{\"nome\": \"Estequiometria\", \"subassuntos\": [\"Mol\", \"Rendimento\"]}").andExpect(status().isOk());
        comando("cadastrar_assunto", "{\"nome\": \"Atomística\"}").andExpect(status().isOk());
        video = criarVideo("555001", "Aula 1");
        linhaDoVideo = criarItem(aulas, video, "Aula 1", 1, "PUBLICADO");
    }

    private String assunto(int linha) {
        return "/api/admin/itens/" + linha + "/assunto";
    }

    private int ligacoes(String tabela, String coluna, int id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, id);
    }

    // --- vídeo -------------------------------------------------------------------

    @Test
    void oAssuntoDaLinhaDeVideoFicaNoVideoEApareceNaArvore() throws Exception {
        put(assunto(linhaDoVideo), "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assuntos[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.assuntos[0].subassunto").value("Mol"));

        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].assuntos[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].assuntos[0].subassunto").value("Mol"));
    }

    @Test
    void trocarOAssuntoSubstituiEVazioTira() throws Exception {
        put(assunto(linhaDoVideo), "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN).andExpect(status().isOk());
        put(assunto(linhaDoVideo), "{\"assunto\": \"Atomística\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assuntos.length()").value(1))
                .andExpect(jsonPath("$.assuntos[0].assunto").value("Atomística"))
                .andExpect(jsonPath("$.assuntos[0].subassunto").doesNotExist());
        // Um assunto por conteúdo: o anterior não fica de entulho na tabela de ligação.
        assertThat(ligacoes("video_subjects", "video_id", video)).isEqualTo(1);

        put(assunto(linhaDoVideo), "{\"assunto\": \"\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assuntos").isEmpty());
        assertThat(ligacoes("video_subjects", "video_id", video)).isZero();
    }

    @Test
    void oMesmoVideoEmDuasLinhasTemUmAssuntoSo() throws Exception {
        var outraLinha = criarItem(apostila, video, "Aula 1 (de novo)", 1, "PUBLICADO");

        put(assunto(outraLinha), "{\"assunto\": \"Atomística\"}", ADMIN).andExpect(status().isOk());

        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].assuntos[0].assunto").value("Atomística"))
                .andExpect(jsonPath("$[0].submodulos[1].itens[0].assuntos[0].assunto").value("Atomística"));
    }

    @Test
    void subassuntoDeOutroAssuntoERecusado() throws Exception {
        put(assunto(linhaDoVideo), "{\"assunto\": \"Atomística\", \"subassunto\": \"Mol\"}", ADMIN)
                .andExpect(status().isNotFound());
        put(assunto(linhaDoVideo), "{\"assunto\": \"\", \"subassunto\": \"Mol\"}", ADMIN)
                .andExpect(status().isBadRequest());
    }

    // --- questão -----------------------------------------------------------------

    @Test
    void naLinhaDeQuestaoOAssuntoEODoCadastroDela() throws Exception {
        var corpo = post("/api/admin/questoes", """
                {"enunciado": "Qual a massa de 2 mol de água?",
                 "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"},
                 "gabarito": "A", "assunto": "Estequiometria", "subassunto": "Mol"}""", ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int questao = JsonPath.<Integer>read(corpo, "$.questoes[0].questao_id");
        var linha = JsonPath.<Integer>read(post("/api/admin/submodulos/" + apostila + "/questao",
                "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        // A linha mostra o que a questão já tem...
        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[1].itens[0].assuntos[0].subassunto").value("Mol"));

        // ...e trocar pela linha troca o cadastro da questão: é o mesmo campo, não um segundo.
        put(assunto(linha), "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Rendimento\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assuntos[0].subassunto").value("Rendimento"));
        get("/api/admin/questoes/" + questao, ADMIN)
                .andExpect(jsonPath("$.classificacao.length()").value(1))
                .andExpect(jsonPath("$.classificacao[0].subassunto").value("Rendimento"));
        assertThat(ligacoes("question_subjects", "questao_id", questao)).isEqualTo(1);
    }

    // --- PDF ---------------------------------------------------------------------

    @Test
    void aLinhaDePdfGuardaOAssuntoNoMaterial() throws Exception {
        var material = jdbc.queryForObject("""
                INSERT INTO materials (titulo, tipo, tamanho, status, criado_por_id, conteudo)
                VALUES ('Lista 1', 'application/pdf', 1, 'PUBLICADO', 1, '\\x25'::bytea) RETURNING id""", Integer.class);
        var linha = JsonPath.<Integer>read(post("/api/admin/submodulos/" + aulas + "/pdf",
                "{\"material\": %d}".formatted(material), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.item_id");

        put(assunto(linha), "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assuntos[0].subassunto").value("Mol"));
        assertThat(ligacoes("material_subjects", "material_id", material)).isEqualTo(1);

        put(assunto(linha), "{\"assunto\": \"Atomística\"}", ADMIN).andExpect(status().isOk());
        assertThat(ligacoes("material_subjects", "material_id", material)).isEqualTo(1);
    }

    // --- de uma vez --------------------------------------------------------------

    @Test
    void oModuloInteiroDeUmaVezERespeitaQuemJaTem() throws Exception {
        var aula2 = criarItem(aulas, criarVideo("555002", "Aula 2"), "Aula 2", 2, "PUBLICADO");
        put(assunto(aula2), "{\"assunto\": \"Atomística\"}", ADMIN).andExpect(status().isOk());

        post("/api/admin/modulos/" + modulo + "/assunto",
                "{\"assunto\": \"Estequiometria\", \"so_sem_assunto\": true}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificadas").value(1))
                .andExpect(jsonPath("$.puladas").value(1));

        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].assuntos[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$[0].submodulos[0].itens[1].assuntos[0].assunto").value("Atomística"));

        // Sem a ressalva, vale para todas.
        post("/api/admin/submodulos/" + aulas + "/assunto",
                "{\"assunto\": \"Estequiometria\", \"subassunto\": \"Mol\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificadas").value(2));
        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[0].itens[1].assuntos[0].subassunto").value("Mol"));
    }

    @Test
    void oAlunoNaoTrocaAssunto() throws Exception {
        put(assunto(linhaDoVideo), "{\"assunto\": \"Atomística\"}", ALUNO).andExpect(status().isForbidden());
    }
}
