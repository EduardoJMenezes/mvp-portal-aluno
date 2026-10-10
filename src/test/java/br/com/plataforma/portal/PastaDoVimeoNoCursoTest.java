package br.com.plataforma.portal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A pasta inteira do Vimeo entrando num sub-módulo, pela tela de montar o curso. */
class PastaDoVimeoNoCursoTest extends BaseDoPortal {

    private static final String PASTA = """
            {"videos": [
              {"vimeo_id": "920000201", "titulo": "Estequiometria — Questão 01 — Balanceamento", "embed_url": "https://player.vimeo.com/video/920000201"},
              {"vimeo_id": "920000202", "titulo": "Estequiometria — Questão 02 — Mol e massa molar", "embed_url": "https://player.vimeo.com/video/920000202"}]}""";

    private int modulo;
    private int aulas;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        modulo = jdbc.queryForObject("SELECT id FROM modules WHERE nome = 'K01'", Integer.class);
        aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
    }

    private String itens() {
        return "/api/admin/turmas/biblioteca/modulos/" + modulo + "/submodulos/" + aulas + "/itens";
    }

    @Test
    void aPastaVemInteiraComOQueOPlayerPrecisa() throws Exception {
        get("/api/admin/vimeo/pastas/demo-2/videos", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pasta.nome").value("Estequiometria"))
                .andExpect(jsonPath("$.videos.length()").value(5))
                .andExpect(jsonPath("$.videos[0].vimeo_id").value("920000201"))
                .andExpect(jsonPath("$.videos[0].embed_url").value("https://player.vimeo.com/video/920000201"))
                .andExpect(jsonPath("$.videos[4].titulo").value(containsString("Questão 05")))
                .andExpect(jsonPath("$.videos[0].avisos").isEmpty());
    }

    @Test
    void asPastasDizemOndeMoram() throws Exception {
        get("/api/admin/vimeo/pastas?limite=500", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pastas[0].id").value("demo-1"))
                .andExpect(jsonPath("$.pastas[0].pai_id").doesNotExist());
    }

    @Test
    void aBuscaPeloTituloDizDeQuePastaEOVideo() throws Exception {
        get("/api/admin/vimeo/videos?busca=reagente limitante", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("920000203"))
                .andExpect(jsonPath("$[0].pasta_id").value("demo-2"))
                .andExpect(jsonPath("$[0].pasta").value("Estequiometria"));
    }

    @Test
    void oEnderecoDoPlayerSoSaiNaArvoreDoProfessor() throws Exception {
        post(itens(), PASTA, ADMIN).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(
                comando("listar_modulos", "{}", ADMIN).andReturn().getResponse().getContentAsString())
                .contains("https://player.vimeo.com/video/920000201");
        // A porta de comandos entrega a árvore a quem o MCP disser. Para o aluno, quem libera o vídeo
        // é o AcessoServico, na tela dele: esta árvore não pode ser um atalho por fora.
        org.assertj.core.api.Assertions.assertThat(
                comando("listar_modulos", "{}", ALUNO).andReturn().getResponse().getContentAsString())
                .doesNotContain("player.vimeo.com");
    }

    @Test
    void oAlunoNaoLeOVimeo() throws Exception {
        get("/api/admin/vimeo/pastas/demo-2/videos", ALUNO).andExpect(status().isForbidden());
    }

    @Test
    void pastaQueNaoExisteDizQueNaoExiste() throws Exception {
        get("/api/admin/vimeo/pastas/nao-existe/videos", ADMIN).andExpect(status().isNotFound());
    }

    @Test
    void osVideosDaPastaEntramNaOrdemEJaPublicados() throws Exception {
        post(itens(), PASTA, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty());

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[*].nome").value(contains(
                        "Estequiometria — Questão 01 — Balanceamento",
                        "Estequiometria — Questão 02 — Mol e massa molar")));
    }

    @Test
    void trazerAPastaDeNovoNaoDuplicaOQueJaEstava() throws Exception {
        post(itens(), PASTA, ADMIN).andExpect(status().isOk());

        // A pasta ganhou um vídeo: só ele entra, e os dois que já estavam são avisados.
        post(itens(), PASTA.replace("]}", """
                , {"vimeo_id": "920000203", "titulo": "Estequiometria — Questão 03 — Reagente limitante"}]}"""), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros.length()").value(2));

        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].submodulos[0].itens.length()").value(3))
                .andExpect(jsonPath("$[0].submodulos[0].itens[2].vimeo_id").value("920000203"))
                // A linha leva o endereço do player: é a prévia do vídeo na tela de montar o curso.
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].embed_url")
                        .value("https://player.vimeo.com/video/920000201"));
    }
}
