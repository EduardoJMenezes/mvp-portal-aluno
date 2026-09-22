package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O que só o portal faz: turmas, alunos, senha de aluno e tokens do MCP. */
class AdminContasTest extends BaseDoPortal {

    @BeforeEach
    void senhas() {
        comSenhas();
    }

    @Test
    void matricularCriaOAlunoComSenhaTemporariaMostradaUmaVez() throws Exception {
        var corpo = post("/api/admin/turmas/Extensivo 2027/alunos",
                "{\"email\": \"dani@teste.invalid\", \"nome\": \"Dani Souza\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$.aluno.senha_temporaria").value(true))
                .andExpect(jsonPath("$.senha_temporaria").isString())
                .andReturn().getResponse().getContentAsString();
        var senha = corpo.replaceAll(".*\"senha_temporaria\":\"([a-z0-9]+)\".*", "$1");
        assertThat(senha).hasSize(12);

        login("dani@teste.invalid", senha).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.trocar_senha").value(true));
        post("/api/admin/turmas/10/alunos", "{\"email\": \"dani@teste.invalid\", \"nome\": \"Dani\"}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dani Souza já está em Extensivo 2027."));
        get("/api/admin/turmas/10/alunos", ADMIN).andExpect(jsonPath("$.alunos.length()").value(2));
    }

    @Test
    void desmatricularTiraDaTurmaESemNomeNaoCriaConta() throws Exception {
        delete("/api/admin/turmas/10/alunos/bruno@teste.invalid", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.removido").value(true));
        get("/api/admin/turmas/10/alunos", ADMIN).andExpect(jsonPath("$.alunos.length()").value(0));
        post("/api/admin/turmas/10/alunos", "{\"email\": \"novo@teste.invalid\"}", ADMIN)
                .andExpect(status().isBadRequest());
    }

    @Test
    void redefinirSenhaDerrubaAAntiga() throws Exception {
        var corpo = post("/api/admin/alunos/" + ALUNO + "/senha", "", ADMIN).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var senha = corpo.replaceAll(".*\"senha_temporaria\":\"([a-z0-9]+)\".*", "$1");
        login("bruno@teste.invalid", SENHA).andExpect(status().isUnauthorized());
        login("bruno@teste.invalid", senha).andExpect(status().isOk());
    }

    @Test
    void tokenDoMcpNasceUmaVezEMorreRevogado() throws Exception {
        var corpo = post("/api/admin/tokens", "{\"nome\": \"Claude Code\"}", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("pvm_")))
                .andReturn().getResponse().getContentAsString();
        var token = corpo.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        var id = Integer.parseInt(corpo.replaceAll(".*\"id\":(\\d+).*", "$1"));

        get("/api/admin/tokens", ADMIN).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("Claude Code"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
        mvc.perform(get("/api/admin/turmas").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        delete("/api/admin/tokens/" + id, ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.revogado").value(true));
        mvc.perform(get("/api/admin/turmas").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM api_tokens WHERE id = ?", String.class, id))
                .doesNotContain(token);
    }

    @Test
    void turmaNasceEMudaDeNome() throws Exception {
        post("/api/admin/turmas", "{\"nome\": \"Semi 2028\", \"ano\": 2028}", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
        post("/api/admin/turmas", "{\"nome\": \"semi 2028\", \"ano\": 2028}", ADMIN).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Já existe uma turma chamada 'semi 2028'."));
        patch("/api/admin/turmas/Semi 2028", "{\"nome\": \"Semi 2029\", \"ano\": 2029}", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Semi 2029"));
        post("/api/admin/turmas", "{\"nome\": \"\", \"ano\": 2028}", ADMIN).andExpect(status().isUnprocessableContent());
    }

    @Test
    void rascunhoEDescartadoSoPeloPortal() throws Exception {
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        var video = criarVideo("v1", "Q01");
        var corpo = comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "v1", "titulo": "Q01"}]}""")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rascunho = Integer.parseInt(corpo.replaceAll(".*\"rascunho_id\":(\\d+).*", "$1"));

        get("/api/admin/rascunhos", ADMIN).andExpect(jsonPath("$[0].rascunho_id").value(rascunho));
        delete("/api/admin/rascunhos/" + rascunho, ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.descartado").value(true));
        assertThat(contar("drafts")).isZero();
        assertThat(contar("items")).isZero();
        assertThat(contar("videos WHERE id = ?", video)).isEqualTo(1);
    }

    @Test
    void publicarPeloPortalCarimbaAAprovacaoHumana() throws Exception {
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        var corpo = comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "v1", "titulo": "Q01"}]}""")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rascunho = Integer.parseInt(corpo.replaceAll(".*\"rascunho_id\":(\\d+).*", "$1"));

        post("/api/admin/rascunhos/" + rascunho + "/publicar", "", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicado").value(true))
                .andExpect(jsonPath("$.aprovado_via").value("PORTAL"));
        get("/api/aluno/conteudo", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].video.embed_url").doesNotExist());
    }
}
