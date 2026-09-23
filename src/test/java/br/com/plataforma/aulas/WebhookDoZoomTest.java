package br.com.plataforma.aulas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.portal.BaseDoPortal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** O Zoom avisando: a assinatura é a credencial, e só o que é nosso vira alguma coisa. */
class WebhookDoZoomTest extends BaseDoPortal {

    @Autowired EnvioAoVimeo vimeo;

    private EnvioAoVimeo.DeMentira vimeoDeMentira() {
        return (EnvioAoVimeo.DeMentira) vimeo;
    }

    @BeforeEach
    void preparar() {
        comSenhas();
        vimeoDeMentira().links.clear();
    }

    private ResultActions avisar(String corpo, long segundos, String segredo) throws Exception {
        var ts = String.valueOf(segundos);
        var assinatura = "v0=" + WebhookDoZoom.hmac(segredo, "v0:" + ts + ":" + corpo);
        return mvc.perform(post("/api/zoom/webhook").contentType(MediaType.APPLICATION_JSON).content(corpo)
                .header("x-zm-request-timestamp", ts).header("x-zm-signature", assinatura));
    }

    private ResultActions avisar(String corpo) throws Exception {
        return avisar(corpo, Instant.now().getEpochSecond(), SEGREDO_DO_ZOOM);
    }

    // --- a porta ---------------------------------------------------------------

    @Test
    void oDesafioDeValidacaoVoltaCifradoComOSegredo() throws Exception {
        avisar("{\"event\":\"endpoint.url_validation\",\"payload\":{\"plainToken\":\"abc123\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plainToken").value("abc123"))
                .andExpect(jsonPath("$.encryptedToken").value(WebhookDoZoom.hmac(SEGREDO_DO_ZOOM, "abc123")));
    }

    /** Sem conferir, o desafio viraria um oráculo: HMAC de qualquer texto, e daí um aviso forjado. */
    @Test
    void assinaturaErradaOuVelhaERecusadaAteNoDesafio() throws Exception {
        var desafio = "{\"event\":\"endpoint.url_validation\",\"payload\":{\"plainToken\":\"abc\"}}";
        avisar(desafio, Instant.now().getEpochSecond(), "outro-segredo").andExpect(status().isUnauthorized());
        avisar(desafio, Instant.now().minus(10, ChronoUnit.MINUTES).getEpochSecond(), SEGREDO_DO_ZOOM)
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/zoom/webhook").contentType(MediaType.APPLICATION_JSON).content(desafio))
                .andExpect(status().isUnauthorized());
        // Milissegundos também valem.
        avisar(desafio, Instant.now().toEpochMilli(), SEGREDO_DO_ZOOM).andExpect(status().isOk());
    }

    @Test
    void reuniaoQueNaoENossaEIgnorada() throws Exception {
        avisar(gravacao("99999999999", "tk")).andExpect(status().isOk());
        assertThat(vimeoDeMentira().links).isEmpty();
    }

    // --- a gravação ------------------------------------------------------------

    private int aulaPublicadaComDestino() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        var sub = jdbc.queryForObject(
                "SELECT s.id FROM submodules s JOIN modules m ON m.id = s.modulo_id WHERE s.nome = 'Aulas'",
                Integer.class);
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Revisão ao vivo", "inicio_em": "%s", "minutos": 60,
                 "turmas": ["Extensivo 2027"], "submodulo_id": %d}"""
                .formatted(Instant.now().plus(5, ChronoUnit.MINUTES), sub), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var aula = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());
        return aula;
    }

    private String reuniao(int aula) {
        return jdbc.queryForObject("SELECT zoom_meeting_id FROM live_classes WHERE id = ?", String.class, aula);
    }

    private static String gravacao(String reuniao, String token) {
        return """
                {"event":"recording.completed","download_token":"%s","payload":{"object":{"id":%s,
                 "recording_files":[
                   {"file_type":"M4A","recording_type":"audio_only","download_url":"https://zoom.us/rec/audio"},
                   {"file_type":"MP4","recording_type":"active_speaker","download_url":"https://zoom.us/rec/camera"},
                   {"file_type":"MP4","recording_type":"shared_screen_with_speaker_view","download_url":"https://zoom.us/rec/tela"}]}}}"""
                .formatted(token, reuniao);
    }

    @Test
    void aGravacaoVaiAoVimeoEViraRascunhoNoSubModulo() throws Exception {
        var aula = aulaPublicadaComDestino();

        avisar(gravacao(reuniao(aula), "tk-24h")).andExpect(status().isOk());

        // O Vimeo buscou o arquivo da tela, com o token do aviso.
        assertThat(vimeoDeMentira().links).containsExactly("https://zoom.us/rec/tela?access_token=tk-24h");
        // Chegou como rascunho, no nome de quem agendou: o professor aprova como qualquer conteúdo.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drafts WHERE origem = 'ZOOM' AND tipo = 'ITENS' "
                + "AND status = 'RASCUNHO' AND criado_por_id = ?", Integer.class, ADMIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM items WHERE id = "
                + "(SELECT gravacao_item_id FROM live_classes WHERE id = ?)", String.class, aula)).isEqualTo("RASCUNHO");
        get("/api/admin/aulas", ADMIN)
                .andExpect(jsonPath("$[0].gravacao").value("990000001"))
                .andExpect(jsonPath("$[0].gravacao_item_id").isNumber());

        // O Zoom reenvia: a gravação não sobe duas vezes.
        avisar(gravacao(reuniao(aula), "tk-24h")).andExpect(status().isOk());
        assertThat(vimeoDeMentira().links).hasSize(1);
    }

    @Test
    void semDestinoAGravacaoSoSobeAoVimeo() throws Exception {
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Sem destino", "inicio_em": "%s", "turmas": ["Extensivo 2027"]}"""
                .formatted(Instant.now().plus(5, ChronoUnit.MINUTES)), ADMIN)
                .andReturn().getResponse().getContentAsString();
        var aula = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());

        avisar(gravacao(reuniao(aula), "")).andExpect(status().isOk());

        // Sem token no aviso, vale o do nosso app (no Zoom de mentira, um marcador).
        assertThat(vimeoDeMentira().links).containsExactly("https://zoom.us/rec/tela?access_token=de-mentira");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drafts WHERE origem = 'ZOOM'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT gravacao_vimeo_id FROM live_classes WHERE id = ?", String.class, aula))
                .isEqualTo("990000001");
    }

    // --- a presença ------------------------------------------------------------

    private static String participante(String evento, String reuniao, String email, String campo, String quando) {
        return """
                {"event":"%s","payload":{"object":{"id":"%s","participant":{"email":"%s","%s":"%s"}}}}"""
                .formatted(evento, reuniao, email, campo, quando);
    }

    @Test
    void quemEntrouPeloPortalApareceParaOProfessor() throws Exception {
        var aula = aulaPublicadaComDestino();
        post("/api/aluno/aulas/" + aula + "/entrar", "", ALUNO).andExpect(status().isOk());
        var r = reuniao(aula);

        avisar(participante("meeting.participant_joined", r, "BRUNO@teste.invalid", "join_time", "2026-09-23T03:00:00Z"));
        avisar(participante("meeting.participant_left", r, "bruno@teste.invalid", "leave_time", "2026-09-23T03:40:00Z"));
        // Quem caiu e voltou conta uma vez, da primeira entrada à última saída.
        avisar(participante("meeting.participant_joined", r, "bruno@teste.invalid", "join_time", "2026-09-23T03:10:00Z"));
        // Quem não entrou pelo portal (o professor, um convidado) não vira linha.
        avisar(participante("meeting.participant_joined", r, "professor@escola.demo", "join_time", "2026-09-23T03:00:00Z"));

        get("/api/admin/aulas", ADMIN)
                .andExpect(jsonPath("$[0].presentes.length()").value(1))
                .andExpect(jsonPath("$[0].presentes[0].entrou_em").value("2026-09-23T03:00:00Z"))
                .andExpect(jsonPath("$[0].presentes[0].saiu_em").value("2026-09-23T03:40:00Z"));
        // O aluno não vê a lista de presença de ninguém.
        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$[0].presentes.length()").value(0));
    }
}
