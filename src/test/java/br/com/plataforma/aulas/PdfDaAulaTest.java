package br.com.plataforma.aulas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.portal.BaseDoPortal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * O PDF da aula (decisão 0013): a aula ao vivo e a linha do módulo apontam para um material, e
 * quem vê a aula abre o PDF — sem publicar o material para a turma.
 */
class PdfDaAulaTest extends BaseDoPortal {

    private static final byte[] PDF = "%PDF-1.4 lista da aula".getBytes(StandardCharsets.UTF_8);

    private int material;
    private int sub;
    private int alunoDoIntensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        var corpo = mvc.perform(multipart("/api/admin/materiais")
                .file(new MockMultipartFile("arquivo", "lista.pdf", "application/pdf", PDF))
                .param("titulo", "Lista K01").cookie(sessao(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        material = Integer.parseInt(corpo.replaceAll(".*\"material_id\":(\\d+).*", "$1"));
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        sub = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
    }

    private int aula(Instant inicio, Integer submodulo) throws Exception {
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Revisão ao vivo", "inicio_em": "%s", "minutos": 60,
                 "turmas": ["Extensivo 2027"], "submodulo_id": %s}"""
                .formatted(inicio, submodulo), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var aula = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());
        return aula;
    }

    private String arquivo() {
        return "/api/aluno/materiais/" + material + "/arquivo";
    }

    @Test
    void quemVeAAulaAbreOPdfEOMaterialApareceNaAbaMateriais() throws Exception {
        var aula = aula(Instant.now().plus(3, ChronoUnit.DAYS), null);
        // Antes de anexar: o material está em rascunho e não chega a ninguém.
        get(arquivo(), ALUNO).andExpect(status().isForbidden());

        put("/api/admin/aulas/" + aula + "/material", "{\"material\": %d}".formatted(material), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.material.titulo").value("Lista K01"))
                .andExpect(jsonPath("$.material_no_dia").value(false));

        get(arquivo(), ALUNO).andExpect(status().isOk());
        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$[0].material.material_id").value(material));
        get("/api/aluno/materiais", ALUNO).andExpect(jsonPath("$[*].titulo").value(Matchers.contains("Lista K01")));
        // A outra turma não tem a aula, então nem o PDF.
        get(arquivo(), alunoDoIntensivo).andExpect(status().isForbidden());
        get("/api/aluno/materiais", alunoDoIntensivo).andExpect(jsonPath("$.length()").value(0));
        // O professor vê onde o material está anexado.
        get("/api/admin/materiais", ADMIN)
                .andExpect(jsonPath("$[0].usos").value(Matchers.contains("Aula ao vivo: Revisão ao vivo")));
    }

    @Test
    void soNoDiaEscondeOPdfAteODiaDaAula() throws Exception {
        var aula = aula(Instant.now().plus(3, ChronoUnit.DAYS), null);
        put("/api/admin/aulas/" + aula + "/material", "{\"material\": %d, \"so_no_dia\": true}".formatted(material), ADMIN)
                .andExpect(status().isOk());

        get(arquivo(), ALUNO).andExpect(status().isForbidden());
        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$[0].material").doesNotExist());
        // O professor continua vendo o anexo.
        get("/api/admin/aulas", ADMIN).andExpect(jsonPath("$[0].material.material_id").value(material));

        // Tirar o PDF: a aula fica sem.
        put("/api/admin/aulas/" + aula + "/material", "{\"material\": null}", ADMIN)
                .andExpect(jsonPath("$.material").doesNotExist());
    }

    /** O "dia" é o de Brasília: meia-noite de lá, não a do servidor. */
    @Test
    void oDiaDaAulaComecaAMeiaNoiteDeBrasilia() {
        var a = new Aula("Revisão", null, Instant.parse("2027-03-10T22:00:00Z"), 60, true, null, true, ADMIN); // 19h, dia 10
        a.anexarMaterial(null, true);
        assertThat(AulasServico.pdfLiberado(a, Instant.parse("2027-03-10T02:59:00Z"))).isFalse(); // 23h59 do dia 9
        assertThat(AulasServico.pdfLiberado(a, Instant.parse("2027-03-10T03:00:00Z"))).isTrue();  // 00h do dia 10
    }

    @Test
    void aGravacaoNasceComOPdfDaAula() throws Exception {
        var aula = aula(Instant.now().plus(5, ChronoUnit.MINUTES), sub);
        put("/api/admin/aulas/" + aula + "/material", "{\"material\": %d}".formatted(material), ADMIN)
                .andExpect(status().isOk());
        var reuniao = jdbc.queryForObject("SELECT zoom_meeting_id FROM live_classes WHERE id = ?", String.class, aula);
        var corpo = """
                {"event":"recording.completed","download_token":"tk","payload":{"object":{"id":%s,
                 "recording_files":[{"file_type":"MP4","recording_type":"shared_screen","download_url":"https://zoom.us/rec/tela"}]}}}"""
                .formatted(reuniao);
        var ts = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/zoom/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo)
                        .header("x-zm-request-timestamp", ts)
                        .header("x-zm-signature", "v0=" + WebhookDoZoom.hmac(SEGREDO_DO_ZOOM, "v0:" + ts + ":" + corpo)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT material_id FROM items WHERE id = "
                + "(SELECT gravacao_item_id FROM live_classes WHERE id = ?)", Integer.class, aula)).isEqualTo(material);
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$..itens[0].material.titulo").value(Matchers.contains("Lista K01")));

        // Trocar o PDF da aula troca o da gravação também.
        put("/api/admin/aulas/" + aula + "/material", "{\"material\": null}", ADMIN).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT material_id FROM items WHERE id = "
                + "(SELECT gravacao_item_id FROM live_classes WHERE id = ?)", Integer.class, aula)).isNull();
    }

    @Test
    void aLinhaSoDePdfSegueAsRegrasDoModulo() throws Exception {
        var corpo = post("/api/admin/submodulos/" + sub + "/pdf", "{\"material\": %d}".formatted(material), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Lista K01"))
                .andReturn().getResponse().getContentAsString();
        var item = Integer.parseInt(corpo.replaceAll(".*\"item_id\":(\\d+).*", "$1"));

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$..itens[0].nome").value(Matchers.contains("Lista K01")))
                .andExpect(jsonPath("$..itens[0].video").value(Matchers.contains((Object) null)));
        get(arquivo(), ALUNO).andExpect(status().isOk());
        // O Intensivo não recebe o módulo: nem a linha, nem o PDF.
        get(arquivo(), alunoDoIntensivo).andExpect(status().isForbidden());

        // A linha é só o PDF: tirar o PDF seria deixá-la vazia.
        put("/api/admin/itens/" + item + "/material", "{\"material\": null}", ADMIN)
                .andExpect(status().isBadRequest());
    }
}
