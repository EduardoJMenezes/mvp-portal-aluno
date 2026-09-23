package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** Pelo chat, a aula nasce em rascunho e sem sala: quem abre a sala no Zoom é o professor, no portal. */
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
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Admin › Aulas ao vivo")));

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
}
