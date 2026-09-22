package br.com.plataforma.portal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A sala é do Zoom (de mentira, aqui), a porta é nossa. */
class AulasTest extends BaseDoPortal {

    @BeforeEach
    void senhas() {
        comSenhas();
    }

    private int agendar(Instant inicio) throws Exception {
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Revisão de estequiometria", "inicio_em": "%s", "minutos": 60,
                 "turmas": ["Extensivo 2027"]}""".formatted(inicio), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.tem_sala").value(false))
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
    }

    @Test
    void publicarAbreASalaEOAlunoEntraPeloLinkDele() throws Exception {
        var aula = agendar(Instant.now().plus(5, ChronoUnit.MINUTES));
        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$.length()").value(0));

        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tem_sala").value(true))
                .andExpect(jsonPath("$.estado").value("ABERTA"));

        get("/api/aluno/aulas", ALUNO).andExpect(jsonPath("$[0].titulo").value("Revisão de estequiometria"));
        post("/api/aluno/aulas/" + aula + "/entrar", "", ALUNO).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://zoom.example/j/")));
        post("/api/admin/aulas/" + aula + "/iniciar", "", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("zak=")));
    }

    @Test
    void agendadaNaoAbreAntesDaHoraEHorarioSemFusoRecusa() throws Exception {
        var aula = agendar(Instant.now().plus(2, ChronoUnit.HOURS));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN)
                .andExpect(jsonPath("$.estado").value("AGENDADA"));
        post("/api/aluno/aulas/" + aula + "/entrar", "", ALUNO).andExpect(status().isBadRequest());

        post("/api/admin/aulas", """
                {"titulo": "Sem fuso", "inicio_em": "2026-10-10T14:00", "turmas": ["Extensivo 2027"]}""", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O horário da aula precisa de fuso."));
    }

    @Test
    void publicarSemNinguemRecusaERemoverDesmarcaASala() throws Exception {
        var corpo = post("/api/admin/aulas", """
                {"titulo": "Vazia", "inicio_em": "%s"}""".formatted(Instant.now().plus(1, ChronoUnit.HOURS)), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var vazia = Integer.parseInt(corpo.replaceAll(".*\"aula_id\":(\\d+).*", "$1"));
        patch("/api/admin/aulas/" + vazia, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isBadRequest());

        var aula = agendar(Instant.now().plus(1, ChronoUnit.HOURS));
        patch("/api/admin/aulas/" + aula, "{\"status\": \"PUBLICADO\"}", ADMIN).andExpect(status().isOk());
        delete("/api/admin/aulas/" + aula, ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.reversivel").value(true));
        get("/api/admin/aulas", ADMIN).andExpect(jsonPath("$[?(@.aula_id == %d)]".formatted(aula)).doesNotExist());
    }
}
