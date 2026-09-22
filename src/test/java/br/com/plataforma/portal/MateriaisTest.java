package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** O PDF do professor: nasce em rascunho, chega em fatias, e a anotação é de quem risca. */
class MateriaisTest extends BaseDoPortal {

    private static final byte[] PDF = "%PDF-1.4 apostila de exemplo com algum conteudo".getBytes(StandardCharsets.UTF_8);

    private int material;

    @BeforeEach
    void enviar() throws Exception {
        comSenhas();
        var corpo = mvc.perform(multipart("/api/admin/materiais")
                .file(new MockMultipartFile("arquivo", "apostila.pdf", "application/pdf", PDF))
                .param("titulo", "Apostila 1").cookie(sessao(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.tamanho").value(PDF.length))
                .andReturn().getResponse().getContentAsString();
        material = Integer.parseInt(corpo.replaceAll(".*\"material_id\":(\\d+).*", "$1"));
    }

    @Test
    void rascunhoNaoChegaAoAlunoEPublicarSemDestinoRecusa() throws Exception {
        get("/api/aluno/materiais", ALUNO).andExpect(jsonPath("$.length()").value(0));
        patch("/api/admin/materiais/" + material, "{\"status\": \"PUBLICADO\"}", ADMIN)
                .andExpect(status().isBadRequest());
        get("/api/aluno/materiais/" + material + "/arquivo", ALUNO).andExpect(status().isForbidden());
    }

    @Test
    void publicadoParaATurmaChegaAoAlunoEmFaixas() throws Exception {
        patch("/api/admin/materiais/" + material, "{\"turmas\": [\"Extensivo 2027\"], \"status\": \"PUBLICADO\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turmas[0]").value("Extensivo 2027"));
        get("/api/aluno/materiais", ALUNO).andExpect(jsonPath("$[0].titulo").value("Apostila 1"));

        var faixa = mvc.perform(get("/api/aluno/materiais/" + material + "/arquivo").cookie(sessao(ALUNO))
                .header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-4/" + PDF.length))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(faixa, StandardCharsets.UTF_8)).isEqualTo("%PDF-");

        mvc.perform(get("/api/aluno/materiais/" + material + "/arquivo").cookie(sessao(ALUNO))
                .header("Range", "bytes=999-1000"))
                .andExpect(status().isRequestedRangeNotSatisfiable());

        var inteiro = mvc.perform(get("/api/aluno/materiais/" + material + "/arquivo").cookie(sessao(ALUNO)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(PDF.length)))
                .andReturn();
        assertThat(inteiro.getResponse().getContentAsByteArray()).isEqualTo(PDF);
    }

    @Test
    void naoEntraOQueNaoEPdf() throws Exception {
        mvc.perform(multipart("/api/admin/materiais")
                .file(new MockMultipartFile("arquivo", "foto.png", "image/png", new byte[] {1, 2, 3}))
                .cookie(sessao(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Só entra PDF aqui."));
    }

    @Test
    void anotacaoEDeQuemRisca() throws Exception {
        patch("/api/admin/materiais/" + material, "{\"alunos\": [\"Aluno Bruno\"], \"status\": \"PUBLICADO\"}", ADMIN)
                .andExpect(status().isOk());

        put("/api/aluno/materiais/" + material + "/anotacoes/3",
                "{\"v\": 1, \"tracos\": [{\"cor\": \"#f00\", \"pontos\": [[0.1, 0.2], [0.3, 0.4]]}]}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracos").value(1));
        get("/api/aluno/materiais/" + material + "/anotacoes", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.paginas.3.tracos[0].cor").value("#f00"));
        get("/api/aluno/materiais/" + material + "/anotacoes", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.paginas.3").doesNotExist());
        get("/api/aluno/materiais", ALUNO).andExpect(jsonPath("$[0].paginas_anotadas").value(1));
    }
}
