package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** Pelo chat, a aula nasce em rascunho; com o ok do professor no chat, o Claude já publica. */
class AulasComandosTest extends BaseDeComando {

    @Test
    void agendaEmRascunhoSemSalaComODestinoDaGravacao() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());

        comando("agendar_aula", """
                {"titulo": "Revisão ao vivo", "inicio": "2090-10-10T19:00", "minutos": 90,
                 "turmas": ["Extensivo 2027"], "modulo": "K01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aula.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.aula.tem_sala").value(false))
                // 19h em Brasília
                .andExpect(jsonPath("$.aula.inicio_em").value("2090-10-10T22:00:00Z"))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("publicar")));

        // Sem sub-módulo dito, a gravação vai para "Aulas".
        assertThat(jdbc.queryForObject("SELECT s.nome FROM live_classes a JOIN submodules s ON s.id = a.submodulo_id",
                String.class)).isEqualTo("Aulas");
        comando("listar_aulas", "{}").andExpect(jsonPath("$[0].titulo").value("Revisão ao vivo"));
    }

    @Test
    void destinoSemTurmaERecusado() throws Exception {
        comando("agendar_aula", """
                {"titulo": "Solta", "inicio": "2090-10-10T19:00", "modulo": "K01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "O destino da gravação é um sub-módulo de uma turma: informe a turma."));
    }

    @Test
    void comPublicarASalaNasceEATurmaJaVeNoCapitulo() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());

        // Quinta às 19h: longe da outra plataforma.
        comando("agendar_aula", """
                {"titulo": "Revisão ao vivo", "inicio": "2090-10-12T19:00", "minutos": 90,
                 "turmas": ["Extensivo 2027"], "modulo": "K01", "publicar": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aula.status").value("PUBLICADO"))
                .andExpect(jsonPath("$.aula.tem_sala").value(true))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("no capítulo")));
    }

    /** A conta do Zoom é dividida: pelo chat, a aula não encosta nos horários da outra plataforma. */
    @Test
    void publicarNoHorarioDaOutraPlataformaERecusadoENadaFica() throws Exception {
        // Terça 18h30 cruza terça 17h–19h; quarta 18h com 90 min cruza quarta 19h–21h.
        for (var inicio : java.util.List.of("2090-10-10T18:30", "2090-10-11T18:00")) {
            comando("agendar_aula", """
                    {"titulo": "Na hora errada", "inicio": "%s", "minutos": 90,
                     "turmas": ["Extensivo 2027"], "publicar": true}""".formatted(inicio))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("outra plataforma")));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM live_classes", Integer.class)).isZero();

        // Terça 19h em ponto já não cruza: a outra aula acabou.
        comando("agendar_aula", """
                {"titulo": "Logo depois", "inicio": "2090-10-10T19:00", "minutos": 60,
                 "turmas": ["Extensivo 2027"], "publicar": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aula.status").value("PUBLICADO"));
    }
}
