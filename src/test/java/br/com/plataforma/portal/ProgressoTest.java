package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aula assistida: o vídeo se conclui perto do fim, o PDF quando o aluno marca, a questão quando ele
 * responde — e o professor vê quanto cada aluno já fez do que a turma dele enxerga.
 */
class ProgressoTest extends BaseDoPortal {

    private static final String QUESTAO = """
            {"nome": "Q01", "nova": {"enunciado": "Qual a massa de 2 mol de água?",
              "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"}, "gabarito": "A"}}""";

    private int aula1;
    private int aula2;
    private int pdf;
    private int questao;
    private int rascunho;
    private int carla;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        carla = criarUsuario("Carla", "carla@teste.invalid", SENHA, "ALUNO", false);
        matricular(carla, 11);
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        var aulas = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Aulas'", Integer.class);
        var apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
        aula1 = criarItem(aulas, criarVideo("111111", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        aula2 = criarItem(aulas, criarVideo("222222", "Aula 2"), "Aula 2", 2, "PUBLICADO");
        rascunho = criarItem(aulas, criarVideo("333333", "Aula 3"), "Aula 3", 3, "RASCUNHO");
        var material = jdbc.queryForObject("""
                INSERT INTO materials (titulo, tipo, tamanho, status, criado_por_id, conteudo)
                VALUES ('Lista 1', 'application/pdf', 4, 'PUBLICADO', ?, decode('25504446', 'hex')) RETURNING id""",
                Integer.class, ADMIN);
        pdf = jdbc.queryForObject("""
                INSERT INTO items (submodulo_id, material_id, nome, ordem, status)
                VALUES (?, ?, 'Lista 1', 4, 'PUBLICADO') RETURNING id""", Integer.class, aulas, material);
        var corpo = post("/api/admin/submodulos/" + apostila + "/questao", QUESTAO, ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        questao = JsonPath.<Integer>read(corpo, "$.id");
    }

    private void assistir(int item, int posicao, int duracao, int aluno) throws Exception {
        post("/api/aluno/itens/" + item + "/progresso",
                "{\"posicao_segundos\": %d, \"duracao_segundos\": %d}".formatted(posicao, duracao), aluno)
                .andExpect(status().isOk());
    }

    // --- o vídeo -----------------------------------------------------------------

    @Test
    void oVideoGuardaAPosicaoESoConcluiPertoDoFim() throws Exception {
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 120, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concluido").value(false))
                .andExpect(jsonPath("$.posicao_segundos").value(120));
        get("/api/aluno/itens/" + aula1 + "/progresso", ALUNO)
                .andExpect(jsonPath("$.posicao_segundos").value(120))
                .andExpect(jsonPath("$.concluido").value(false));

        // 90% de 600 s: aos 539 ainda não, aos 540 sim.
        assistir(aula1, 539, 600, ALUNO);
        get("/api/aluno/itens/" + aula1 + "/progresso", ALUNO).andExpect(jsonPath("$.concluido").value(false));
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 540, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(true));

        assertThat(contar("item_progress WHERE item_id = ? AND aluno_id = ?", aula1, ALUNO)).isEqualTo(1);
    }

    @Test
    void voltarOVideoNaoDesfazAConclusao() throws Exception {
        assistir(aula1, 600, 600, ALUNO);
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 30, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(true))
                .andExpect(jsonPath("$.posicao_segundos").value(30));
    }

    @Test
    void quemDesmarcouPertoDoFimNaoVeAAulaMarcadaDeNovoNoAvisoSeguinte() throws Exception {
        assistir(aula1, 560, 600, ALUNO);
        put("/api/aluno/itens/" + aula1 + "/concluido", "{\"concluido\": false}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(false));

        // O player continua avisando: a posição já tinha passado do limiar, então não conclui de novo.
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 580, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(false));

        // Assistindo de novo desde o começo, conclui.
        assistir(aula1, 10, 600, ALUNO);
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 590, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(true));
    }

    @Test
    void posicaoAlemDoFimFicaNoFimEPedidoSemDuracaoERecusado() throws Exception {
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 9999, \"duracao_segundos\": 600}", ALUNO)
                .andExpect(jsonPath("$.posicao_segundos").value(600));
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 10, \"duracao_segundos\": 0}", ALUNO)
                .andExpect(status().isBadRequest());
        // Campo faltando é barrado na validação do pedido, antes da regra.
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 10}", ALUNO)
                .andExpect(status().isUnprocessableEntity());
    }

    // --- marcar à mão ------------------------------------------------------------

    @Test
    void oAlunoMarcaEDesmarcaEADataDaPrimeiraConclusaoFica() throws Exception {
        put("/api/aluno/itens/" + pdf + "/concluido", "{\"concluido\": true}", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concluido").value(true));
        var primeira = jdbc.queryForObject("SELECT concluido_em FROM item_progress WHERE item_id = ?", java.sql.Timestamp.class, pdf);

        // Marcar de novo não mexe na data.
        put("/api/aluno/itens/" + pdf + "/concluido", "{\"concluido\": true}", ALUNO).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT concluido_em FROM item_progress WHERE item_id = ?", java.sql.Timestamp.class, pdf))
                .isEqualTo(primeira);

        put("/api/aluno/itens/" + pdf + "/concluido", "{\"concluido\": false}", ALUNO)
                .andExpect(jsonPath("$.concluido").value(false));
        // Desmarcar o que nunca foi marcado não cria nada nem dá erro.
        put("/api/aluno/itens/" + aula2 + "/concluido", "{\"concluido\": false}", ALUNO).andExpect(status().isOk());
        assertThat(contar("item_progress WHERE item_id = ?", aula2)).isZero();
    }

    @Test
    void questaoNaoSeMarcaAMaoEPdfNaoTemPosicao() throws Exception {
        put("/api/aluno/itens/" + questao + "/concluido", "{\"concluido\": true}", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ela fica concluída quando você responde")));
        post("/api/aluno/itens/" + pdf + "/progresso", "{\"posicao_segundos\": 10, \"duracao_segundos\": 60}", ALUNO)
                .andExpect(status().isBadRequest());
    }

    // --- quem pode ---------------------------------------------------------------

    @Test
    void soQuemVeOItemRegistra() throws Exception {
        // Carla é do Intensivo, que não recebe o K01.
        post("/api/aluno/itens/" + aula1 + "/progresso", "{\"posicao_segundos\": 10, \"duracao_segundos\": 600}", carla)
                .andExpect(status().isNotFound());
        // Item em rascunho não existe para o aluno.
        put("/api/aluno/itens/" + rascunho + "/concluido", "{\"concluido\": true}", ALUNO).andExpect(status().isNotFound());
        // O professor não assiste: não tem progresso para gravar.
        put("/api/aluno/itens/" + aula1 + "/concluido", "{\"concluido\": true}", ADMIN).andExpect(status().isForbidden());

        assertThat(contar("item_progress")).isZero();
    }

    // --- a árvore do aluno -------------------------------------------------------

    @Test
    void aArvoreDoCursoDizOQueOAlunoJaFez() throws Exception {
        assistir(aula1, 600, 600, ALUNO);
        assistir(aula2, 100, 600, ALUNO);
        post("/api/aluno/itens/" + questao + "/responder", "{\"alternativa\": \"B\"}", ALUNO).andExpect(status().isOk());

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[0].concluido").value(true))
                // Começou e não terminou: não conta.
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[1].concluido").value(false))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[2].nome").value("Lista 1"))
                .andExpect(jsonPath("$[0].modulos[0].submodulos[0].itens[2].concluido").value(false))
                // Questão respondida está feita, mesmo errada.
                .andExpect(jsonPath("$[0].modulos[0].submodulos[1].itens[0].concluido").value(true));
    }

    // --- o professor -------------------------------------------------------------

    @Test
    void oProfessorVeQuantoCadaAlunoDaTurmaJaFez() throws Exception {
        var maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        matricular(maria, 10);
        assistir(aula1, 600, 600, ALUNO);
        put("/api/aluno/itens/" + pdf + "/concluido", "{\"concluido\": true}", ALUNO).andExpect(status().isOk());
        post("/api/aluno/itens/" + questao + "/responder", "{\"alternativa\": \"A\"}", ALUNO).andExpect(status().isOk());
        assistir(aula2, 50, 600, maria);

        var corpo = get("/api/admin/turmas/Extensivo 2027/progresso", ADMIN)
                .andExpect(status().isOk())
                // O rascunho não entra na conta: o aluno não o vê.
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.alunos.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        var nomeDoAluno = jdbc.queryForObject("SELECT nome FROM users WHERE id = ?", String.class, ALUNO);
        assertThat(JsonPath.<java.util.List<Integer>>read(corpo, "$.alunos[?(@.nome == '%s')].concluidos".formatted(nomeDoAluno)))
                .containsExactly(3);
        // Maria começou uma aula: zero concluídas, mas com atividade.
        assertThat(JsonPath.<java.util.List<Integer>>read(corpo, "$.alunos[?(@.nome == 'Maria')].concluidos")).containsExactly(0);
        assertThat(JsonPath.<java.util.List<String>>read(corpo, "$.alunos[?(@.nome == 'Maria')].ultima_atividade").getFirst())
                .isNotNull();
    }

    @Test
    void oProfessorAbreOCursoDeUmAlunoItemPorItem() throws Exception {
        assistir(aula1, 600, 600, ALUNO);
        assistir(aula2, 150, 600, ALUNO);
        post("/api/aluno/itens/" + questao + "/responder", "{\"alternativa\": \"C\"}", ALUNO).andExpect(status().isOk());

        get("/api/admin/alunos/" + ALUNO + "/progresso", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turmas[0].turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$.turmas[0].concluidos").value(2))
                .andExpect(jsonPath("$.turmas[0].total").value(4))
                .andExpect(jsonPath("$.turmas[0].modulos[0].nome").value("K01"))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[0].tipo").value("VIDEO"))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[0].concluido").value(true))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[1].concluido").value(false))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[1].posicao_segundos").value(150))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[1].duracao_segundos").value(600))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[2].tipo").value("PDF"))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[0].itens[2].concluido_em").value(nullValue()))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[1].itens[0].tipo").value("QUESTAO"))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[1].itens[0].concluido").value(true))
                .andExpect(jsonPath("$.turmas[0].modulos[0].submodulos[1].itens[0].correta").value(false))
                .andExpect(jsonPath("$.ultima_atividade").isNotEmpty());
    }

    @Test
    void alunoNaoVeOProgressoDosOutros() throws Exception {
        get("/api/admin/turmas/Extensivo 2027/progresso", ALUNO).andExpect(status().isForbidden());
        get("/api/admin/alunos/" + ALUNO + "/progresso", ALUNO).andExpect(status().isForbidden());
    }
}
