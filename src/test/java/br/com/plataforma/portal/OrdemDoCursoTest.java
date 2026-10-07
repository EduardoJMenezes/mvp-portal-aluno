package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Arrastar na tela de montar o curso: a fila inteira numa chamada só, e o nome do sub-módulo. */
class OrdemDoCursoTest extends BaseDoPortal {

    private int k01;
    private int k02;
    private int aulas;
    private int apostila;
    private int a1;
    private int a2;
    private int a3;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K02\"}").andExpect(status().isOk());
        k01 = idDo("modules", "K01");
        k02 = idDo("modules", "K02");
        aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas' AND modulo_id = ?", Integer.class, k01);
        apostila = jdbc.queryForObject(
                "SELECT id FROM submodules WHERE nome = 'Questões da apostila' AND modulo_id = ?", Integer.class, k01);
        a1 = criarItem(aulas, criarVideo("111111", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        a2 = criarItem(aulas, criarVideo("222222", "Aula 2"), "Aula 2", 2, "PUBLICADO");
        a3 = criarItem(aulas, criarVideo("333333", "Aula 3"), "Aula 3", 3, "PUBLICADO");
    }

    private List<String> nomes(String sql, Object... args) {
        return jdbc.queryForList(sql, String.class, args);
    }

    private List<String> linhasDeAulas() {
        return nomes("SELECT nome FROM items WHERE submodulo_id = ? ORDER BY ordem", aulas);
    }

    // --- as linhas ---------------------------------------------------------------

    @Test
    void aFilaDasLinhasMudaDeUmaVezEChegaAoAluno() throws Exception {
        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d, %d]}".formatted(a3, a1, a2), ADMIN)
                .andExpect(status().isOk());

        assertThat(linhasDeAulas()).containsExactly("Aula 3", "Aula 1", "Aula 2");
        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].nome").value("Aula 3"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[2].nome").value("Aula 2"));
    }

    @Test
    void linhaQueAListaNaoCitaFicaOndeEstava() throws Exception {
        // A tela olhava por uma turma que não vê a Aula 2: só a 1 e a 3 trocam entre si.
        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d]}".formatted(a3, a1), ADMIN)
                .andExpect(status().isOk());

        assertThat(linhasDeAulas()).containsExactly("Aula 3", "Aula 2", "Aula 1");
    }

    @Test
    void aOrdemSaiSemBuracoMesmoQuandoEstavaRepetida() throws Exception {
        jdbc.update("UPDATE items SET ordem = 7 WHERE submodulo_id = ?", aulas);

        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d, %d]}".formatted(a2, a3, a1), ADMIN)
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT ordem FROM items WHERE submodulo_id = ? ORDER BY ordem", Integer.class, aulas))
                .containsExactly(1, 2, 3);
        assertThat(linhasDeAulas()).containsExactly("Aula 2", "Aula 3", "Aula 1");
    }

    @Test
    void linhaDeOutroSubmoduloERecusadaENadaMuda() throws Exception {
        var deFora = criarItem(apostila, criarVideo("444444", "Q01"), "Q01", 1, "PUBLICADO");

        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d]}".formatted(a2, deFora), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("não está em 'Aulas'")));

        assertThat(linhasDeAulas()).containsExactly("Aula 1", "Aula 2", "Aula 3");
    }

    @Test
    void idRepetidoERecusado() throws Exception {
        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d]}".formatted(a1, a1), ADMIN)
                .andExpect(status().isBadRequest());
    }

    // --- os sub-módulos e os módulos ---------------------------------------------

    @Test
    void osSubmodulosTrocamDeLugar() throws Exception {
        put("/api/admin/modulos/" + k01 + "/submodulos/ordem", "{\"ids\": [%d, %d]}".formatted(apostila, aulas), ADMIN)
                .andExpect(status().isOk());

        assertThat(nomes("SELECT nome FROM submodules WHERE modulo_id = ? ORDER BY ordem", k01))
                .containsExactly("Questões da apostila", "Aulas");
    }

    @Test
    void osModulosTrocamDeLugarNaBiblioteca() throws Exception {
        put("/api/admin/biblioteca/ordem", "{\"ids\": [%d, %d]}".formatted(k02, k01), ADMIN)
                .andExpect(status().isOk());

        get("/api/admin/biblioteca/arvore", ADMIN)
                .andExpect(jsonPath("$[0].nome").value("K02"))
                .andExpect(jsonPath("$[1].nome").value("K01"));
    }

    // --- o nome do sub-módulo ----------------------------------------------------

    @Test
    void oSubmoduloMudaDeNome() throws Exception {
        patch("/api/admin/submodulos/" + aulas, "{\"nome\": \"  Videoaulas \"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Videoaulas"));
    }

    @Test
    void oNomeNaoPodeRepetirOutroDoMesmoModulo() throws Exception {
        patch("/api/admin/submodulos/" + aulas, "{\"nome\": \"questões da apostila\"}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'K01' já tem um sub-módulo chamado 'Questões da apostila'."));

        // O próprio nome, com outra caixa, é dele: pode.
        patch("/api/admin/submodulos/" + aulas, "{\"nome\": \"AULAS\"}", ADMIN).andExpect(status().isOk());
    }

    // --- quem pode ---------------------------------------------------------------

    @Test
    void alunoNaoMexeNaOrdemNemNoNome() throws Exception {
        put("/api/admin/submodulos/" + aulas + "/itens/ordem", "{\"ids\": [%d, %d, %d]}".formatted(a3, a2, a1), ALUNO)
                .andExpect(status().isForbidden());
        put("/api/admin/biblioteca/ordem", "{\"ids\": [%d, %d]}".formatted(k02, k01), ALUNO)
                .andExpect(status().isForbidden());
        patch("/api/admin/submodulos/" + aulas, "{\"nome\": \"Outro\"}", ALUNO).andExpect(status().isForbidden());

        assertThat(linhasDeAulas()).containsExactly("Aula 1", "Aula 2", "Aula 3");
    }
}
