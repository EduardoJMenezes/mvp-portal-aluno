package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.Papel;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A prévia do aluno: o professor vê o portal como um aluno da turma escolhida — o mesmo filtro do
 * backend, nada a mais, nada a menos — e não grava nada em nome de ninguém.
 */
class PreviaDoAlunoTest extends BaseDoPortal {

    private static final int EXTENSIVO = 10;
    private static final int INTENSIVO = 11;
    private static final String QUESTAO = """
            {"nome": "Q01", "nova": {"enunciado": "Qual a massa de 2 mol de água?",
              "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"},
              "gabarito": "A", "resolucao_comentada": "2 x 18 = 36."}}""";

    @Autowired private br.com.plataforma.exercicios.ExerciciosServico exercicios;
    @Autowired private br.com.plataforma.simulados.ProvaDoAluno prova;
    @Autowired private br.com.plataforma.progresso.ProgressoServico progresso;
    @Autowired private br.com.plataforma.materiais.MateriaisServico materiais;
    @Autowired private br.com.plataforma.aulas.AulasServico aulas;

    private int linhaDeQuestao;
    private int videoDoExtensivo;
    private int simuladoDoExtensivo;
    private int materialDoExtensivo;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        comando("criar_modulo", "{\"turma\": \"Intensivo 2027\", \"nome\": \"K02\"}").andExpect(status().isOk());
        var aulasK01 = submodulo("K01", "Aulas");
        var aulasK02 = submodulo("K02", "Aulas");

        videoDoExtensivo = criarItem(aulasK01, criarVideo("900000001", "Mol"), "Aula 1", 1, "PUBLICADO");
        criarItem(aulasK01, criarVideo("900000002", "Rascunho"), "Aula em rascunho", 2, "RASCUNHO");
        // A aula do K01 que só o Intensivo recebe (item_classes).
        var soDoIntensivo = criarItem(aulasK01, criarVideo("900000003", "Revisão"), "Revisão do Intensivo", 3, "PUBLICADO");
        jdbc.update("INSERT INTO item_classes (item_id, turma_id) VALUES (?, ?)", soDoIntensivo, INTENSIVO);
        criarItem(aulasK02, criarVideo("900000004", "Gases"), "Aula de gases", 1, "PUBLICADO");

        var corpo = post("/api/admin/submodulos/" + submodulo("K01", "Questões da apostila") + "/questao", QUESTAO, ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        linhaDeQuestao = JsonPath.<Integer>read(corpo, "$.id");

        var q1 = criarQuestao("Questão do simulado do Extensivo", "B", "MEDIA", "PUBLICADO");
        var q2 = criarQuestao("Questão do simulado do Intensivo", "C", "MEDIA", "PUBLICADO");
        simuladoDoExtensivo = criarSimuladoAberto("Simulado Extensivo", EXTENSIVO, q1);
        criarSimuladoAberto("Simulado Intensivo", INTENSIVO, q2);

        materialDoExtensivo = material("Apostila do Extensivo", "Extensivo 2027");
        material("Apostila do Intensivo", "Intensivo 2027");
    }

    private int submodulo(String modulo, String nome) {
        return jdbc.queryForObject("""
                SELECT s.id FROM submodules s JOIN modules m ON m.id = s.modulo_id
                 WHERE m.nome = ? AND s.nome = ?""", Integer.class, modulo, nome);
    }

    private int material(String titulo, String turma) throws Exception {
        var pdf = ("%PDF-1.4 " + titulo).getBytes(StandardCharsets.UTF_8);
        var corpo = mvc.perform(multipart("/api/admin/materiais")
                        .file(new MockMultipartFile("arquivo", "apostila.pdf", "application/pdf", pdf))
                        .param("titulo", titulo).cookie(sessao(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var id = JsonPath.<Integer>read(corpo, "$.material_id");
        patch("/api/admin/materiais/" + id, "{\"turmas\": [\"%s\"], \"status\": \"PUBLICADO\"}".formatted(turma), ADMIN)
                .andExpect(status().isOk());
        return id;
    }

    /** Entra na prévia como a tela faz e devolve o cookie que o backend gravou. */
    private Cookie previa(String turma) throws Exception {
        var resposta = post("/api/admin/previa", "{\"turma\": \"%s\"}".formatted(turma), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turma").value(turma))
                .andReturn().getResponse();
        var c = resposta.getCookie(PreviaPortal.COOKIE);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        return new Cookie(c.getName(), c.getValue());
    }

    private ResultActions comPrevia(MockHttpServletRequestBuilder pedido, Cookie previa, int usuario) throws Exception {
        return mvc.perform(pedido.cookie(sessao(usuario), previa));
    }

    private ResultActions lerNaPrevia(String rota, Cookie previa) throws Exception {
        return comPrevia(MockMvcRequestBuilders.get(rota), previa, ADMIN);
    }

    private ResultActions gravarNaPrevia(MockHttpServletRequestBuilder pedido, String corpo, Cookie previa) throws Exception {
        return comPrevia(pedido.contentType(APPLICATION_JSON).content(corpo), previa, ADMIN);
    }

    private int gravadoPeloAdmin() {
        return contar("item_answers WHERE aluno_id = ?", ADMIN) + contar("exam_attempts WHERE aluno_id = ?", ADMIN)
                + contar("item_progress WHERE aluno_id = ?", ADMIN)
                + contar("material_annotations WHERE usuario_id = ?", ADMIN)
                + contar("live_class_attendance WHERE usuario_id = ?", ADMIN);
    }

    // --- a turma escolhida, e só ela ---------------------------------------------

    @Test
    void aPreviaMostraOCursoDaTurmaEscolhidaComoOAlunoDelaVe() throws Exception {
        var extensivo = previa("Extensivo 2027");
        lerNaPrevia("/api/aluno/conteudo", extensivo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$[0].modulos[*].nome").value(containsInAnyOrder("K01")))
                .andExpect(jsonPath("$..itens[*].nome").value(containsInAnyOrder("Aula 1", "Q01")));

        // A mesma tela, outra turma: o K02 e, no K01, só a aula endereçada ao Intensivo.
        var intensivo = previa("Intensivo 2027");
        lerNaPrevia("/api/aluno/conteudo", intensivo)
                .andExpect(jsonPath("$[0].turma").value("Intensivo 2027"))
                .andExpect(jsonPath("$[0].modulos[*].nome").value(containsInAnyOrder("K01", "K02")))
                .andExpect(jsonPath("$..itens[*].nome").value(containsInAnyOrder("Revisão do Intensivo", "Aula de gases")));
    }

    @Test
    void simuladosEMateriaisSaoOsDaTurmaEscolhida() throws Exception {
        var extensivo = previa("Extensivo 2027");
        lerNaPrevia("/api/aluno/simulados", extensivo)
                .andExpect(jsonPath("$[*].titulo").value(containsInAnyOrder("Simulado Extensivo")));
        lerNaPrevia("/api/aluno/materiais", extensivo)
                .andExpect(jsonPath("$[*].titulo").value(containsInAnyOrder("Apostila do Extensivo")));
        lerNaPrevia("/api/aluno/materiais/" + materialDoExtensivo + "/arquivo", extensivo).andExpect(status().isOk());

        var intensivo = previa("Intensivo 2027");
        lerNaPrevia("/api/aluno/simulados/" + simuladoDoExtensivo, intensivo).andExpect(status().isForbidden());
        lerNaPrevia("/api/aluno/materiais/" + materialDoExtensivo + "/arquivo", intensivo).andExpect(status().isForbidden());
    }

    @Test
    void naPreviaOGabaritoNaoSaiAntesDaResposta() throws Exception {
        // Sem a prévia, o professor vê a questão inteira, como prévia do editor.
        get("/api/aluno/itens/" + linhaDeQuestao + "/questao", ADMIN).andExpect(jsonPath("$.gabarito").value("A"));
        lerNaPrevia("/api/aluno/itens/" + linhaDeQuestao + "/questao", previa("Extensivo 2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondida").value(false))
                .andExpect(jsonPath("$.gabarito").doesNotExist())
                .andExpect(jsonPath("$.resolucao_comentada").doesNotExist());
    }

    // --- não grava ---------------------------------------------------------------

    @Test
    void abrirOSimuladoNaPreviaMostraAProvaSemComecarTentativa() throws Exception {
        lerNaPrevia("/api/aluno/simulados/" + simuladoDoExtensivo, previa("Extensivo 2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EM_ANDAMENTO"))
                .andExpect(jsonPath("$.questoes[0].enunciado").value("Questão do simulado do Extensivo"))
                .andExpect(jsonPath("$.segundos_restantes").value(org.hamcrest.Matchers.greaterThan(0)));
        assertThat(contar("exam_attempts")).isZero();
    }

    @Test
    void nenhumaEscritaDoAlunoPassaNaPrevia() throws Exception {
        var previa = previa("Extensivo 2027");
        var simulado = "/api/aluno/simulados/" + simuladoDoExtensivo;
        gravarNaPrevia(MockMvcRequestBuilders.post("/api/aluno/itens/" + linhaDeQuestao + "/responder"), "{\"alternativa\": \"A\"}", previa)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("prévia")));
        gravarNaPrevia(MockMvcRequestBuilders.post("/api/aluno/itens/" + videoDoExtensivo + "/progresso"),
                "{\"posicao_segundos\": 30, \"duracao_segundos\": 600}", previa).andExpect(status().isForbidden());
        gravarNaPrevia(MockMvcRequestBuilders.put("/api/aluno/itens/" + videoDoExtensivo + "/concluido"), "{\"concluido\": true}", previa)
                .andExpect(status().isForbidden());
        gravarNaPrevia(MockMvcRequestBuilders.post(simulado + "/responder"), "{\"questao_id\": 1, \"alternativa\": \"B\"}", previa)
                .andExpect(status().isForbidden());
        gravarNaPrevia(MockMvcRequestBuilders.post(simulado + "/entregar"), "{}", previa).andExpect(status().isForbidden());
        gravarNaPrevia(MockMvcRequestBuilders.put("/api/aluno/materiais/" + materialDoExtensivo + "/anotacoes/1"),
                "{\"v\": 1, \"tracos\": []}", previa).andExpect(status().isForbidden());
        gravarNaPrevia(MockMvcRequestBuilders.post("/api/aluno/aulas/1/entrar"), "{}", previa).andExpect(status().isForbidden());

        assertThat(gravadoPeloAdmin()).isZero();
    }

    /** A tranca do filtro é a segunda: cada serviço recusa sozinho, venha a prévia por onde vier. */
    @Test
    void osServicosRecusamAEscritaDaPreviaSemDependerDoFiltro() {
        var naPrevia = new Identidade(ADMIN, "Professora Ana", "ana@teste.invalid", Papel.ADMIN, Canal.PORTAL)
                .emPreviaDa(EXTENSIVO);
        var agora = Instant.now();
        assertThatThrownBy(() -> exercicios.responder(naPrevia, linhaDeQuestao, "A", agora)).isInstanceOf(NaoAutorizado.class);
        assertThatThrownBy(() -> progresso.registrar(naPrevia, videoDoExtensivo, 30, 600, agora)).isInstanceOf(NaoAutorizado.class);
        assertThatThrownBy(() -> progresso.marcar(naPrevia, videoDoExtensivo, true, agora)).isInstanceOf(NaoAutorizado.class);
        var simulado = String.valueOf(simuladoDoExtensivo);
        assertThatThrownBy(() -> prova.responder(naPrevia, simulado, 1, "B", agora)).isInstanceOf(NaoAutorizado.class);
        assertThatThrownBy(() -> prova.entregar(naPrevia, simulado, agora)).isInstanceOf(NaoAutorizado.class);
        assertThatThrownBy(() -> materiais.salvarAnotacao(naPrevia, String.valueOf(materialDoExtensivo), 1,
                java.util.Map.of("tracos", java.util.List.of()), agora)).isInstanceOf(NaoAutorizado.class);
        assertThatThrownBy(() -> aulas.entrar(naPrevia, "1", agora)).isInstanceOf(NaoAutorizado.class);
        assertThat(gravadoPeloAdmin()).isZero();

        // E só operador entra na prévia.
        var aluno = new Identidade(ALUNO, "Aluno Bruno", "bruno@teste.invalid", Papel.ALUNO, Canal.PORTAL);
        assertThatThrownBy(() -> aluno.emPreviaDa(INTENSIVO)).isInstanceOf(NaoAutorizado.class);
    }

    // --- quem pode, e como sai ---------------------------------------------------

    @Test
    void oCookieDaPreviaNaoValeParaAluno() throws Exception {
        post("/api/admin/previa", "{\"turma\": \"Intensivo 2027\"}", ALUNO).andExpect(status().isForbidden());

        // Com o cookie forjado, o aluno continua vendo a turma dele e gravando o que é dele.
        var forjado = new Cookie(PreviaPortal.COOKIE, String.valueOf(INTENSIVO));
        comPrevia(MockMvcRequestBuilders.get("/api/aluno/conteudo"), forjado, ALUNO)
                .andExpect(jsonPath("$[*].turma").value(containsInAnyOrder("Extensivo 2027")))
                .andExpect(jsonPath("$..itens[*].nome").value(not(hasItem("Aula de gases"))));
        comPrevia(MockMvcRequestBuilders.post("/api/aluno/itens/" + linhaDeQuestao + "/responder")
                .contentType(APPLICATION_JSON).content("{\"alternativa\": \"A\"}"), forjado, ALUNO)
                .andExpect(status().isOk());
        assertThat(contar("item_answers WHERE aluno_id = ?", ALUNO)).isEqualTo(1);
    }

    @Test
    void foraDasRotasDoAlunoOProfessorContinuaProfessor() throws Exception {
        var previa = previa("Extensivo 2027");
        comPrevia(MockMvcRequestBuilders.get("/api/admin/turmas"), previa, ADMIN).andExpect(status().isOk());
        comPrevia(MockMvcRequestBuilders.get("/api/eu"), previa, ADMIN)
                .andExpect(jsonPath("$.papel").value("ADMIN"))
                .andExpect(jsonPath("$.previa.turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$.previa.turma_id").value(EXTENSIVO));
        get("/api/eu", ADMIN).andExpect(jsonPath("$.previa").doesNotExist());
    }

    @Test
    void voltarAoModoProfessorESairDaContaApagamAPrevia() throws Exception {
        var previa = previa("Extensivo 2027");
        comPrevia(MockMvcRequestBuilders.delete("/api/admin/previa"), previa, ADMIN)
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(PreviaPortal.COOKIE, 0));
        comPrevia(MockMvcRequestBuilders.post("/api/logout"), previa, ADMIN)
                .andExpect(cookie().maxAge(PreviaPortal.COOKIE, 0));
        post("/api/admin/previa", "{\"turma\": \"Turma que não existe\"}", ADMIN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Extensivo 2027")));
    }
}
