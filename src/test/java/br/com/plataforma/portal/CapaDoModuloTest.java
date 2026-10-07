package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** A capa do cartão do módulo: um ícone do catálogo ou uma foto — e quem pode ver a foto. */
class CapaDoModuloTest extends BaseDoPortal {

    // O começo de um PNG de verdade: é pela assinatura que o servidor reconhece a imagem.
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private int modulo;
    private int alunoDoIntensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01 - Da alquimia ao modelo atômico", "icone": "atomo"}""")
                .andExpect(status().isOk());
        modulo = jdbc.queryForObject("SELECT id FROM modules", Integer.class);
        // O aluno só vê o módulo que tem aula publicada.
        var aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        criarItem(aulas, criarVideo("111111", "Aula 1"), "Aula 1", 1, "PUBLICADO");
    }

    private ResultActions enviarFoto(byte[] bytes, int usuario) throws Exception {
        return mvc.perform(multipart("/api/admin/modulos/" + modulo + "/foto")
                .file(new MockMultipartFile("arquivo", "capa.png", "image/png", bytes)).cookie(sessao(usuario)));
    }

    // --- o ícone -----------------------------------------------------------------

    @Test
    void oIconeEscolhidoNaCriacaoChegaAoProfessorEAoAluno() throws Exception {
        get("/api/admin/modulos?turma=Extensivo 2027", ADMIN)
                .andExpect(jsonPath("$[0].icone").value("atomo"))
                .andExpect(jsonPath("$[0].foto_versao").value(nullValue()));
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].icone").value("atomo"))
                .andExpect(jsonPath("$[0].modulos[0].foto_versao").value(nullValue()));
    }

    @Test
    void iconeQueNaoExisteERecusadoAntesDeCriarOModulo() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K02", "icone": "foguete"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Ícone 'foguete' não existe. Ícones: atomo, frasco")));

        assertThat(contar("modules")).isEqualTo(1);
    }

    @Test
    void oIconeMudaPeloComandoEVoltaAoAutomatico() throws Exception {
        comando("editar_modulo", """
                {"turma": "Extensivo 2027", "modulo": "K01", "novo_icone": "frasco"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icone").value("frasco"));

        // "automatico" devolve a escolha ao portal, que decide pelo nome do capítulo.
        patch("/api/admin/turmas/Extensivo 2027/modulos/" + modulo, "{\"icone\": \"automatico\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icone").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT icone FROM modules", String.class)).isNull();
    }

    // --- a foto ------------------------------------------------------------------

    @Test
    void aFotoEnviadaPeloProfessorChegaAoAlunoDaTurma() throws Exception {
        var corpo = enviarFoto(PNG, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foto_versao").isNumber())
                // O ícone fica guardado: é o que aparece se a foto sair.
                .andExpect(jsonPath("$.icone").value("atomo"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).contains("\"modulo_id\":" + modulo);

        get("/api/aluno/conteudo", ALUNO).andExpect(jsonPath("$[0].modulos[0].foto_versao").isNumber());
        get("/api/aluno/modulos/" + modulo + "/foto", ALUNO)
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"));
    }

    @Test
    void alunoDeOutraTurmaNaoAlcancaAFoto() throws Exception {
        enviarFoto(PNG, ADMIN).andExpect(status().isOk());

        get("/api/aluno/modulos/" + modulo + "/foto", alunoDoIntensivo).andExpect(status().isNotFound());
        // O professor vê a de qualquer módulo.
        get("/api/aluno/modulos/" + modulo + "/foto", ADMIN).andExpect(status().isOk());
    }

    @Test
    void escolherIconeTiraAFoto() throws Exception {
        enviarFoto(PNG, ADMIN).andExpect(status().isOk());

        patch("/api/admin/turmas/Extensivo 2027/modulos/" + modulo, "{\"icone\": \"funil\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icone").value("funil"));

        get("/api/aluno/modulos/" + modulo + "/foto", ALUNO).andExpect(status().isNotFound());
        assertThat(contar("modules WHERE foto IS NULL AND foto_em IS NULL AND foto_tipo IS NULL")).isEqualTo(1);
    }

    @Test
    void arquivoQueNaoEImagemERecusado() throws Exception {
        enviarFoto("isto é um texto, não uma foto".getBytes(java.nio.charset.StandardCharsets.UTF_8), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Formato não aceito. Envie PNG, JPEG, WEBP ou GIF."));
        assertThat(contar("modules WHERE foto IS NOT NULL")).isZero();
    }

    @Test
    void fotoGrandeDemaisERecusada() throws Exception {
        var grande = new byte[1024 * 1024 + 16];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);

        enviarFoto(grande, ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("o limite é 1024 KB")));
    }

    @Test
    void alunoNaoMexeNaCapa() throws Exception {
        enviarFoto(PNG, ALUNO).andExpect(status().isForbidden());
        assertThat(contar("modules WHERE foto IS NOT NULL")).isZero();
    }
}
