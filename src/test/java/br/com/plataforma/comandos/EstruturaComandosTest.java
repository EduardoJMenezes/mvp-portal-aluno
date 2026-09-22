package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** O resto da estrutura do curso: sub-módulo, edição, movimentação, remoção e as listagens. */
class EstruturaComandosTest extends BaseDeComando {

    /** Monta "K01" com 'Aulas' e 'Questões da apostila' e devolve o id do módulo. */
    private int montarK01() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        return idDo("modules", "K01");
    }

    // --- criar_submodulo -----------------------------------------------------

    @Test
    void acrescentaSubmoduloAUmModuloQueJaExiste() throws Exception {
        montarK01();

        comando("criar_submodulo", """
                {"turma": "Extensivo 2027", "modulo": "K01", "nome": "Revisão"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submodulo").value("Revisão"))
                .andExpect(jsonPath("$.modulo").value("K01"))
                .andExpect(jsonPath("$.turma").value("Extensivo 2027"));

        assertThat(contar("submodules")).isEqualTo(3);
    }

    // --- resolução por nome --------------------------------------------------

    @Test
    void moduloQueNaoExisteListaOsQueExistem() throws Exception {
        montarK01();

        comando("criar_submodulo", """
                {"turma": "Extensivo 2027", "modulo": "K99", "nome": "Revisão"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value("Módulo 'K99' não existe em 'Extensivo 2027'. Há: 'K01'."));
    }

    @Test
    void referenciaAmbiguaPedeQueEscolham() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01 - Cadeias"}""").andExpect(status().isOk());
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01 - Isomeria"}""").andExpect(status().isOk());

        comando("criar_submodulo", """
                {"turma": "Extensivo 2027", "modulo": "K01", "nome": "Revisão"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "'K01' casa com mais de um módulo em 'Extensivo 2027': "
                                + "'K01 - Cadeias', 'K01 - Isomeria'. Diga qual deles."));
    }

    // --- editar_modulo -------------------------------------------------------

    @Test
    void renomeiaEReordenaOModulo() throws Exception {
        montarK01();

        comando("editar_modulo", """
                {"turma": "Extensivo 2027", "modulo": "K01", "novo_nome": "K01 - Orgânica", "nova_ordem": 7}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modulo").value("K01 - Orgânica"))
                .andExpect(jsonPath("$.ordem").value(7));

        assertThat(jdbc.queryForObject("SELECT alterado_por_id FROM modules", Integer.class)).isEqualTo(ADMIN);
    }

    @Test
    void editarSemDizerOQueMudarERecusado() throws Exception {
        montarK01();

        comando("editar_modulo", """
                {"turma": "Extensivo 2027", "modulo": "K01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Diga o que mudar: nome, ordem, ou os dois."));
    }

    // --- editar_item ---------------------------------------------------------

    @Test
    void renomeiaOItemSemMexerNaPosicao() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Q04", 1, "PUBLICADO");

        comando("editar_item", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas", "item": "Q04",
                 "novo_nome": "Q04 - Cadeias carbônicas"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("Q04 - Cadeias carbônicas"))
                .andExpect(jsonPath("$.ordem").value(1))
                .andExpect(jsonPath("$.submodulo").value("Aulas"));
    }

    @Test
    void moveOItemParaOutroSubmodulo() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Q04", 1, "RASCUNHO");

        comando("editar_item", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas", "item": "Q04",
                 "mover_para_submodulo": "Questões da apostila"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submodulo").value("Questões da apostila"));

        assertThat(contar("items WHERE submodulo_id = ?", idDo("submodules", "Questões da apostila")))
                .isEqualTo(1);
    }

    @Test
    void moverParaOndeOVideoJaEstaERecusado() throws Exception {
        montarK01();
        var video = criarVideo("v1", "Aula 1");
        criarItem(idDo("submodules", "Aulas"), video, "Q04", 1, "RASCUNHO");
        criarItem(idDo("submodules", "Questões da apostila"), video, "Q04 (repetida)", 1, "RASCUNHO");

        comando("editar_item", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas", "item": "Q04",
                 "mover_para_submodulo": "Questões da apostila"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("'Questões da apostila' já tem este vídeo, como 'Q04 (repetida)'."));
    }

    // --- remover_do_curso ----------------------------------------------------

    @Test
    void removeOItemEDizSeSumiuDaTela() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Q04", 1, "PUBLICADO");

        comando("remover_do_curso", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas", "item": "Q04"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("Q04"))
                .andExpect(jsonPath("$.submodulo").value("Aulas"))
                .andExpect(jsonPath("$.sumiu_da_tela_do_aluno").value(true))
                .andExpect(jsonPath("$.reversivel").value(true));

        // Nada é apagado: a linha continua lá, só marcada.
        assertThat(contar("items")).isEqualTo(1);
        assertThat(contar("items WHERE removido_em IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void removeOModuloEContaOsPublicadosQueSomem() throws Exception {
        montarK01();
        var aulas = idDo("submodules", "Aulas");
        var questoes = idDo("submodules", "Questões da apostila");
        criarItem(aulas, criarVideo("v1", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        criarItem(questoes, criarVideo("v2", "Q04"), "Q04", 1, "PUBLICADO");
        criarItem(questoes, criarVideo("v3", "Q05"), "Q05", 2, "RASCUNHO");

        comando("remover_do_curso", """
                {"turma": "Extensivo 2027", "modulo": "K01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modulo").value("K01"))
                .andExpect(jsonPath("$.turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$.itens_publicados_que_somem_da_tela").value(2));

        // Remover o módulo não cascateia: sub-módulos e itens ficam intactos.
        assertThat(contar("submodules WHERE removido_em IS NULL")).isEqualTo(2);
        assertThat(contar("items WHERE removido_em IS NULL")).isEqualTo(3);
    }

    @Test
    void removeOSubmoduloEContaSoOsDele() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        criarItem(idDo("submodules", "Questões da apostila"), criarVideo("v2", "Q04"), "Q04", 1, "PUBLICADO");

        comando("remover_do_curso", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submodulo").value("Aulas"))
                .andExpect(jsonPath("$.modulo").value("K01"))
                .andExpect(jsonPath("$.itens_publicados_que_somem_da_tela").value(1));
    }

    /** Sem esta trava, "remova a Q04" sem o sub-módulo removeria o módulo inteiro. */
    @Test
    void removerItemSemDizerOSubmoduloERecusado() throws Exception {
        montarK01();

        comando("remover_do_curso", """
                {"turma": "Extensivo 2027", "modulo": "K01", "item": "Q04"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Para remover um item, diga também o sub-módulo dele."));
    }

    // --- listagens -----------------------------------------------------------

    @Test
    void listarModulosDevolveAArvoreInteira() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Aula 1", 1, "PUBLICADO");

        comando("listar_modulos", """
                {"turma": "Extensivo 2027"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("K01"))
                .andExpect(jsonPath("$[0].turma").value("Extensivo 2027"))
                .andExpect(jsonPath("$[0].submodulos[0].nome").value("Aulas"))
                .andExpect(jsonPath("$[0].submodulos[0].tipo").value("VIDEO"))
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].nome").value("Aula 1"))
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].status").value("PUBLICADO"))
                .andExpect(jsonPath("$[0].submodulos[0].itens[0].video_id").isNumber())
                .andExpect(jsonPath("$[0].submodulos[1].itens").isEmpty());
    }

    @Test
    void listarModulosSemTurmaPegaTodasAsTurmas() throws Exception {
        montarK01();
        comando("criar_modulo", """
                {"turma": "Intensivo 2027", "nome": "I01"}""").andExpect(status().isOk());

        comando("listar_modulos", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listarTurmasContaAlunosModulosEItens() throws Exception {
        montarK01();
        criarItem(idDo("submodules", "Aulas"), criarVideo("v1", "Aula 1"), "Aula 1", 1, "PUBLICADO");
        criarItem(idDo("submodules", "Aulas"), criarVideo("v2", "Aula 2"), "Aula 2", 2, "RASCUNHO");
        jdbc.update("INSERT INTO enrollments (usuario_id, turma_id) VALUES (2, 10)");

        comando("listar_turmas", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Extensivo 2027"))
                .andExpect(jsonPath("$[0].ano").value(2027))
                .andExpect(jsonPath("$[0].alunos").value(1))
                .andExpect(jsonPath("$[0].modulos").value(1))
                .andExpect(jsonPath("$[0].itens_publicados").value(1))
                .andExpect(jsonPath("$[0].itens_em_rascunho").value(1))
                .andExpect(jsonPath("$[1].nome").value("Intensivo 2027"))
                .andExpect(jsonPath("$[1].modulos").value(0));
    }
}
