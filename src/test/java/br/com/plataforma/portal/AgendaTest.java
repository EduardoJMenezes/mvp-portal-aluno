package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Papel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A agenda (decisão 0012): eventos por turma, que levam ao conteúdo e o liberam na hora do evento.
 * O aluno vê só até hoje.
 */
class AgendaTest extends BaseDoPortal {

    @Autowired AcessoServico acesso;

    private int alunoDoIntensivo;
    private int modulo;
    private int aula1;
    private int aula2;
    private int video2;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        alunoDoIntensivo = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(alunoDoIntensivo, 11);
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        comando("atribuir_turmas", """
                {"modulo": "K01", "turmas": ["Extensivo 2027", "Intensivo 2027"]}""").andExpect(status().isOk());
        modulo = jdbc.queryForObject("SELECT id FROM modules WHERE nome = 'K01'", Integer.class);
        var sub = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        aula1 = criarItem(sub, criarVideo("111111", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        video2 = criarVideo("222222", "Aula 2");
        aula2 = criarItem(sub, video2, "Aula 2", 2, "PUBLICADO");
    }

    private static String quando(long horas) {
        return Instant.now().plus(horas, ChronoUnit.HOURS).toString();
    }

    private int evento(String titulo, long horas, String turma, String tipo, int id) throws Exception {
        var ligacao = tipo == null ? "null" : "{\"tipo\": \"%s\", \"id\": %d}".formatted(tipo, id);
        var corpo = post("/api/admin/agenda", """
                {"titulo": "%s", "inicio_em": "%s", "turmas": ["%s"], "categoria": "Aula", "ligacao": %s}"""
                .formatted(titulo, quando(horas), turma, ligacao), ADMIN)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(corpo.replaceAll(".*\"evento_id\":(\\d+).*", "$1"));
    }

    private List<String> aulasQueVe(int aluno) throws Exception {
        var corpo = get("/api/aluno/conteudo", aluno).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(corpo, "$..itens[*].nome");
    }

    @Test
    void oAlunoVeOsEventosDaTurmaDeleSoAteHojeEOCliqueLevaAAula() throws Exception {
        evento("Aula 1 - K01", -48, "Extensivo 2027", "AULA", aula1);
        evento("Só do Intensivo", -48, "Intensivo 2027", null, 0);
        evento("Semana que vem", 24 * 7, "Extensivo 2027", null, 0);

        get("/api/aluno/agenda", ALUNO)
                .andExpect(jsonPath("$[*].titulo").value(Matchers.contains("Aula 1 - K01")))
                .andExpect(jsonPath("$[0].liberado").value(true))
                .andExpect(jsonPath("$[0].destino.tipo").value("AULA"))
                .andExpect(jsonPath("$[0].destino.id").value(aula1))
                .andExpect(jsonPath("$[0].destino.modulo_id").value(modulo));
        // O professor vê tudo.
        get("/api/admin/agenda", ADMIN).andExpect(jsonPath("$.length()").value(3));
        get("/api/admin/agenda?turma=Intensivo 2027", ADMIN).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aAulaLigadaFicaEscondidaParaATurmaAteAHoraDoEvento() throws Exception {
        var ev = evento("Aula 2 - K01", 5, "Extensivo 2027", "AULA", aula2);

        assertThat(aulasQueVe(ALUNO)).containsExactly("Aula 1");
        // A outra turma não tem evento: vê tudo, como sempre.
        assertThat(aulasQueVe(alunoDoIntensivo)).containsExactly("Aula 1", "Aula 2");
        // Nem o vídeo sai por outro caminho (a recomendação de estudo, por exemplo).
        var aluno = new Identidade(ALUNO, "Aluno Bruno", "bruno@teste.invalid", Papel.ALUNO, Canal.PORTAL);
        assertThat(acesso.videosLiberados(aluno, List.of(video2), Instant.now())).isEmpty();

        patch("/api/admin/agenda/" + ev, "{\"inicio_em\": \"%s\"}".formatted(quando(-1)), ADMIN)
                .andExpect(status().isOk());
        assertThat(aulasQueVe(ALUNO)).containsExactly("Aula 1", "Aula 2");
        assertThat(acesso.videosLiberados(aluno, List.of(video2), Instant.now())).containsExactly(video2);
    }

    @Test
    void oEventoDaAulaValeMaisQueODoModulo() throws Exception {
        evento("K01", 24, "Extensivo 2027", "MODULO", modulo);
        assertThat(aulasQueVe(ALUNO)).isEmpty();

        evento("Aula 2 adiantada", -1, "Extensivo 2027", "AULA", aula2);
        assertThat(aulasQueVe(ALUNO)).containsExactly("Aula 2");
    }

    @Test
    void semTurmaOuComFimAntesDoInicioERecusado() throws Exception {
        post("/api/admin/agenda", """
                {"titulo": "Solto", "inicio_em": "%s", "turmas": []}""".formatted(quando(1)), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Diga para que turmas o evento vale."));
        post("/api/admin/agenda", """
                {"titulo": "Recesso", "inicio_em": "%s", "fim_em": "%s", "turmas": ["Extensivo 2027"]}"""
                .formatted(quando(24), quando(1)), ADMIN)
                .andExpect(status().isBadRequest());
        get("/api/admin/agenda", ALUNO).andExpect(status().isForbidden());
    }

    @Test
    void peloChatCriaVariosLigandoPeloNomeETodosOuNenhum() throws Exception {
        comando("criar_eventos", """
                {"eventos": [
                  {"titulo": "K01", "inicio": "2020-02-02T08:00", "turmas": ["Extensivo 2027"], "modulo": "K01"},
                  {"titulo": "Aula 2", "inicio": "2020-02-03T19:00", "turmas": ["Intensivo 2027"],
                   "modulo": "K01", "submodulo": "Aulas", "aula": "Aula 2", "categoria": "Aula"},
                  {"titulo": "Carnaval", "inicio": "2020-02-15T00:00", "fim": "2020-02-22T23:59",
                   "turmas": ["Extensivo 2027", "Intensivo 2027"], "categoria": "Feriado"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].destino.tipo").value("MODULO"))
                .andExpect(jsonPath("$[1].destino.nome").value("Aula 2"))
                // 08:00 em Brasília
                .andExpect(jsonPath("$[0].inicio_em").value(Matchers.startsWith("2020-02-02T08:00")));

        comando("criar_eventos", """
                {"eventos": [
                  {"titulo": "Certo", "inicio": "2020-03-01T08:00", "turmas": ["Extensivo 2027"]},
                  {"titulo": "Errado", "inicio": "2020-03-02T08:00", "turmas": ["Extensivo 2027"], "modulo": "K99"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.startsWith("Evento 2 ('Errado'):")));
        comando("listar_agenda", "{}").andExpect(jsonPath("$.length()").value(3));
    }
}
