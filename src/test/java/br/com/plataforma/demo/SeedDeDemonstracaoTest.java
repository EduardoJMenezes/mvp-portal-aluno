package br.com.plataforma.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.portal.BaseDoPortal;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O seed grava direto nas tabelas; aqui o que ele gravou é lido pelas portas de verdade — a do
 * aluno e a do MCP —, para que uma mudança de schema ou de regra não o deixe para trás.
 */
class SeedDeDemonstracaoTest extends BaseDoPortal {

    @Autowired private SeedDeDemonstracao seed;

    /** Sem os dois usuários e as duas turmas do cenário de sempre. */
    private void semearNoBancoVazio() {
        jdbc.execute("TRUNCATE users, classes RESTART IDENTITY CASCADE");
        assertThat(seed.semear(Instant.now())).isTrue();
    }

    private int conta(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    @Test
    void comGenteNoBancoNaoGravaNada() {
        assertThat(seed.semear(Instant.now())).isFalse();

        assertThat(contar("users")).isEqualTo(2);
        assertThat(contar("classes")).isEqualTo(2);
        assertThat(contar("modules")).isZero();
        assertThat(contar("questions")).isZero();
    }

    @Test
    void rodarDeNovoNaoDuplica() {
        semearNoBancoVazio();

        assertThat(seed.semear(Instant.now())).isFalse();
        assertThat(contar("users")).isEqualTo(5);
        assertThat(contar("videos")).isEqualTo(11);
        assertThat(contar("questions")).isEqualTo(15);
    }

    @Test
    void asContasEntramPeloModoDemo() throws Exception {
        semearNoBancoVazio();

        mvc.perform(get("/api/sessao/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contas_demo.length()").value(5));
        mvc.perform(post("/api/demo/entrar").contentType(APPLICATION_JSON)
                .content("{\"email\": \"joao@aluno.demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nome").value("João"));
    }

    @Test
    void cadaAlunoVeSoOQueATurmaDeleRecebe() throws Exception {
        semearNoBancoVazio();

        // Cinética existe no Extensivo 2027, mas vazia: o aluno não a vê.
        get("/api/aluno/conteudo", conta("joao@aluno.demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].turma").value(SeedDeDemonstracao.TURMA_2027))
                .andExpect(jsonPath("$[0].modulos[*].nome").value(Matchers.contains("Atomística", "Estequiometria")))
                .andExpect(jsonPath("$[0].modulos[1].submodulos[0].itens.length()").value(5))
                .andExpect(jsonPath("$[0].modulos[1].submodulos[0].itens[0].video.bloqueado").value(false));

        get("/api/aluno/conteudo", conta("pedro@aluno.demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].turma").value(SeedDeDemonstracao.TURMA_2026))
                .andExpect(jsonPath("$[0].modulos[*].nome").value(Matchers.contains("Atomística", "Cinética")))
                .andExpect(jsonPath("$[0].modulos[1].submodulos[0].itens.length()").value(3));
    }

    @Test
    void oSimuladoEncerradoJaTemResultadoEOAbertoEsperaOAluno() throws Exception {
        semearNoBancoVazio();
        var joao = conta("joao@aluno.demo");
        var encerrado = jdbc.queryForObject("SELECT id FROM exams WHERE titulo = ?", Integer.class,
                SeedDeDemonstracao.SIMULADO_ENCERRADO);

        get("/api/aluno/simulados/" + encerrado + "/resultado", joao).andExpect(status().isOk())
                .andExpect(jsonPath("$.acertos").value(4))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.posicao").value(1))
                .andExpect(jsonPath("$.participantes").value(2));
        get("/api/aluno/simulados/" + encerrado + "/resultado", conta("maria@aluno.demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.em_branco").value(1));

        get("/api/aluno/simulados", joao).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.titulo == '%s')].situacao".formatted(SeedDeDemonstracao.SIMULADO_ABERTO))
                        .value(Matchers.contains("ABERTO")));
        // O simulado de Cinética vale para as duas turmas; o diagnóstico, só para o 2027.
        get("/api/aluno/simulados", conta("pedro@aluno.demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].titulo").value(Matchers.contains(SeedDeDemonstracao.SIMULADO_ABERTO)));
    }

    /** A pergunta do roteiro — "como o João foi no último simulado?" — já tem resposta. */
    @Test
    void oProfessorLeODesempenhoPeloMcp() throws Exception {
        semearNoBancoVazio();
        var professor = conta("professor@escola.demo");

        comando("buscar_desempenho_aluno", """
                {"aluno": "João"}""", professor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado").value(SeedDeDemonstracao.SIMULADO_ENCERRADO))
                .andExpect(jsonPath("$.acertos").value(4))
                .andExpect(jsonPath("$.total_questoes").value(5));
        // As questões que o roteiro manda pôr no simulado ao vivo.
        comando("buscar_questoes", """
                {"assunto": "Estequiometria"}""", professor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].alternativas.B").value("1, 2, 1 e 2."));
    }
}
