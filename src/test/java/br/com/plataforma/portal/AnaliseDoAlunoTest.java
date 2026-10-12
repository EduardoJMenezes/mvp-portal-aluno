package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A análise do aluno: a plataforma monta o resumo e guarda o texto; quem escreve é o Claude do
 * professor, pelo MCP. Aqui se trava quem entra na fila, o que vai no resumo, quem lê o quê e o
 * que o professor pode fazer com uma análise que não ficou boa.
 */
class AnaliseDoAlunoTest extends BaseDoPortal {

    private int apostila;
    private int mol;
    private int estequiometria;
    private int q1;
    private int q2;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        estequiometria = jdbc.queryForObject("INSERT INTO subjects (nome) VALUES ('Estequiometria') RETURNING id", Integer.class);
        mol = jdbc.queryForObject("INSERT INTO subtopics (assunto_id, nome) VALUES (?, 'Mol') RETURNING id", Integer.class, estequiometria);
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"K01\"}").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);

        // Cinco questões na aula: erra as duas primeiras, acerta as outras. O gabarito é sempre A.
        q1 = questao("Quantos mols há em 18 g de água?\n\n![](figura:7)");
        q2 = questao("Q2");
        jdbc.update("UPDATE question_options SET comentario = 'Você confundiu massa molar com massa atômica. Falta revisar: massa molar.' WHERE questao_id = ? AND letra = 'B'", q1);
        jdbc.update("UPDATE question_options SET comentario = 'Você usou a massa molar da água corretamente.' WHERE questao_id = ? AND letra = 'A'", q1);
        responderNaAula(naAula(q1), "B", ALUNO);
        responderNaAula(naAula(q2), "C", ALUNO);
        for (var n = 3; n <= 5; n++) {
            responderNaAula(naAula(questao("Q" + n)), "A", ALUNO);
        }
    }

    private int questao(String enunciado) {
        var id = criarQuestao(enunciado, "A", "MEDIA", "PUBLICADO");
        jdbc.update("INSERT INTO question_subjects (questao_id, assunto_id, subassunto_id) VALUES (?, ?, ?)", id, estequiometria, mol);
        return id;
    }

    private int naAula(int questao) throws Exception {
        var corpo = post("/api/admin/submodulos/" + apostila + "/questao", "{\"questao_id\": %d}".formatted(questao), ADMIN)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<Integer>read(corpo, "$.id");
    }

    private void responderNaAula(int item, String letra, int aluno) throws Exception {
        post("/api/aluno/itens/" + item + "/responder", "{\"alternativa\": \"%s\"}".formatted(letra), aluno)
                .andExpect(status().isOk());
    }

    private static String analise(int aluno, String ponto) {
        return "{\"aluno_id\": %d, \"ponto_principal\": \"%s\", \"padroes\": \"Padrão.\", \"melhorou\": \"\", \"proximos_passos\": \"Passo.\"}"
                .formatted(aluno, ponto);
    }

    private void gravar(String ponto) throws Exception {
        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, ponto) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gravadas").value(1));
    }

    // --- a fila e o resumo ---------------------------------------------------------

    @Test
    void aFilaTrazOResumoComOComentarioDaAlternativaQueOAlunoMarcou() throws Exception {
        comando("listar_analises_pendentes", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.na_fila").value(1))
                .andExpect(jsonPath("$.restam").value(0))
                .andExpect(jsonPath("$.alunos[0].aluno_id").value(ALUNO))
                .andExpect(jsonPath("$.alunos[0].aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.alunos[0].motivo").value("primeira análise"))
                .andExpect(jsonPath("$.alunos[0].respostas").value(5))
                .andExpect(jsonPath("$.alunos[0].acertos").value(3))
                .andExpect(jsonPath("$.alunos[0].erros").value(2))
                .andExpect(jsonPath("$.alunos[0].analise_anterior").doesNotExist())
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].nome").value("Estequiometria"))
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].subassuntos[0].nome").value("Mol"))
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].subassuntos[0].respostas").value(5))
                // O erro mais recente primeiro; cada um com o que o professor escreveu para aquela alternativa.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[*].questao_id").value(contains(q2, q1)))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].marcada").value("B"))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].gabarito").value("A"))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].comentario_da_marcada").value(containsString("massa molar com massa atômica")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].comentario_da_correta").value(containsString("corretamente")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].subassunto").value("Mol"))
                // A marca da figura não vai: é texto para caber num resumo.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].enunciado").value("Quantos mols há em 18 g de água?"))
                // Sem comentário, quem escreve só sabe que ele errou.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].comentario_da_marcada").doesNotExist())
                // O que há no curso dele sobre o ponto fraco, pelo nome que ele vê.
                .andExpect(jsonPath("$.alunos[0].no_curso[0].topico").value("Mol"))
                .andExpect(jsonPath("$.alunos[0].no_curso[0].rever").value(hasSize(2)));
    }

    @Test
    void quemTemPoucasRespostasFicaForaDaFila() throws Exception {
        var maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        matricular(maria, 10);
        responderNaAula(jdbc.queryForObject("SELECT id FROM items WHERE questao_id = ?", Integer.class, q1), "A", maria);

        comando("listar_analises_pendentes", "{}")
                .andExpect(jsonPath("$.na_fila").value(1))
                .andExpect(jsonPath("$.alunos[0].aluno_id").value(ALUNO));
        // Pedida pelo nome, ela vem mesmo fora da fila: é o professor querendo a análise de alguém.
        comando("listar_analises_pendentes", "{\"aluno\": \"Maria\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunos[0].aluno_id").value(maria))
                .andExpect(jsonPath("$.alunos[0].motivo").value("pedida pelo professor"));
    }

    @Test
    void simuladoAbertoNaoEntraNoResumoMasOEncerradoEntra() throws Exception {
        var aberto = criarSimulado("Simulado aberto", "PUBLICADO", "2026-01-01T10:00:00Z", "2099-01-01T10:00:00Z");
        porNaProva(aberto, q1, 1);
        responder(aberto, ALUNO, q1, false);
        comando("listar_analises_pendentes", "{}").andExpect(jsonPath("$.alunos[0].respostas").value(5));

        var fechado = criarSimulado("Simulado 1", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-02T10:00:00Z");
        porNaProva(fechado, q1, 1);
        porNaProva(fechado, q2, 2);
        // Marcou a primeira errada e deixou a segunda em branco.
        responder(fechado, ALUNO, q1, false);
        comando("listar_analises_pendentes", "{}")
                .andExpect(jsonPath("$.alunos[0].respostas").value(7))
                .andExpect(jsonPath("$.alunos[0].erros").value(4))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[?(@.onde == 'Simulado 1, questão 1')].marcada").value(contains("A")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[?(@.onde == 'Simulado 1, questão 2')]").value(hasSize(1)))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[?(@.onde == 'Simulado 1, questão 2')].marcada").value(contains((Object) null)));
    }

    // --- gravar e ler --------------------------------------------------------------

    @Test
    void aAnaliseGravadaApareceParaOAlunoETiraEleDaFila() throws Exception {
        get("/api/aluno/analise", ALUNO).andExpect(status().isOk()).andExpect(jsonPath("$.analise").doesNotExist());

        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Seu ponto principal agora: **mol**.") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gravadas").value(1))
                .andExpect(jsonPath("$.alunos[0]").value("Aluno Bruno"))
                .andExpect(jsonPath("$.recusadas").isEmpty())
                .andExpect(jsonPath("$.restam_na_fila").value(0));

        get("/api/aluno/analise", ALUNO)
                .andExpect(jsonPath("$.analise.ponto_principal").value("Seu ponto principal agora: **mol**."))
                .andExpect(jsonPath("$.analise.padroes").value("Padrão."))
                // Bloco em branco não é guardado como texto vazio.
                .andExpect(jsonPath("$.analise.melhorou").doesNotExist())
                .andExpect(jsonPath("$.analise.proximos_passos").value("Passo."))
                .andExpect(jsonPath("$.analise.escrita_em").exists())
                .andExpect(jsonPath("$.tem_novidade").value(false));
        comando("listar_analises_pendentes", "{}").andExpect(jsonPath("$.na_fila").value(0)).andExpect(jsonPath("$.alunos").isEmpty());

        var linha = jdbc.queryForMap("SELECT respostas, escrita_por_id FROM student_analyses");
        assertThat(linha.get("respostas")).isEqualTo(5);
        assertThat(linha.get("escrita_por_id")).isEqualTo(ADMIN);
    }

    @Test
    void respostasNovasDevolvemOAlunoParaAFilaComOQueMudou() throws Exception {
        gravar("Primeira.");
        // Quatro respostas novas ainda não bastam.
        for (var n = 6; n <= 9; n++) {
            responderNaAula(naAula(questao("Q" + n)), n == 6 ? "D" : "A", ALUNO);
        }
        comando("listar_analises_pendentes", "{}").andExpect(jsonPath("$.na_fila").value(0));
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.tem_novidade").value(false));

        var q10 = questao("Q10");
        responderNaAula(naAula(q10), "A", ALUNO);
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.tem_novidade").value(true));
        comando("listar_analises_pendentes", "{}")
                .andExpect(jsonPath("$.na_fila").value(1))
                .andExpect(jsonPath("$.alunos[0].motivo").value("5 respostas novas"))
                .andExpect(jsonPath("$.alunos[0].novas_respostas").value(5))
                // A análise anterior vai junto: é ela que permite dizer o que melhorou.
                .andExpect(jsonPath("$.alunos[0].analise_anterior.ponto_principal").value("Primeira."))
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].novas_respostas").value(5))
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].novos_acertos").value(4))
                .andExpect(jsonPath("$.alunos[0].por_assunto[0].subassuntos[0].novos_acertos").value(4))
                // Só o erro de depois da análise vem marcado como novo.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[?(@.novo == true)]").value(hasSize(1)))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[?(@.novo == false)]").value(hasSize(2)));
    }

    @Test
    void simuladoQueFechaReescreveMesmoComPoucasQuestoes() throws Exception {
        gravar("Primeira.");
        // A análise é de antes de o simulado fechar.
        jdbc.update("UPDATE student_analyses SET escrita_em = '2026-01-01T12:00:00Z'::timestamptz");
        var fechado = criarSimulado("Simulado 1", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-02T10:00:00Z");
        porNaProva(fechado, q1, 1);
        responder(fechado, ALUNO, q1, true);

        comando("listar_analises_pendentes", "{}")
                .andExpect(jsonPath("$.na_fila").value(1))
                .andExpect(jsonPath("$.alunos[0].motivo").value("fechou o simulado 'Simulado 1'"));
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.tem_novidade").value(true));
    }

    @Test
    void aRecusadaVoltaComOMotivoEAsOutrasEntram() throws Exception {
        var maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        var corpo = "{\"analises\": [%s, %s, %s, %s, %s, %s]}".formatted(
                analise(ALUNO, "Vale."),
                analise(ALUNO, "Repetida."),
                analise(999, "Ninguém."),
                analise(ADMIN, "Professora não é aluna."),
                analise(maria, "Sem resposta nenhuma."),
                "{\"aluno_id\": %d, \"ponto_principal\": \"  \"}".formatted(ALUNO));
        comando("gravar_analises", corpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gravadas").value(1))
                .andExpect(jsonPath("$.recusadas.length()").value(5))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("duas vezes")))
                .andExpect(jsonPath("$.recusadas[1].motivo").value(containsString("não existe aluno com id 999")))
                .andExpect(jsonPath("$.recusadas[2].motivo").value(containsString("não existe aluno")))
                .andExpect(jsonPath("$.recusadas[3].motivo").value(containsString("não tem resposta que conte")))
                .andExpect(jsonPath("$.recusadas[4].motivo").value(containsString("duas vezes")));
        assertThat(contar("student_analyses")).isEqualTo(1);

        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "x".repeat(701)) + "]}")
                .andExpect(jsonPath("$.gravadas").value(0))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("o limite é 700")));
        comando("gravar_analises", "{\"analises\": [{\"aluno_id\": %d}]}".formatted(ALUNO))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("ponto_principal é obrigatório")));
        comando("gravar_analises", "{\"analises\": []}").andExpect(status().isBadRequest());
    }

    // --- o professor acompanha -----------------------------------------------------

    @Test
    void oProfessorOcultaEDevolveAAnalise() throws Exception {
        get("/api/admin/alunos/" + ALUNO + "/analise", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analise").doesNotExist())
                .andExpect(jsonPath("$.na_fila").value(true))
                .andExpect(jsonPath("$.motivo_da_fila").value("primeira análise"));
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"oculta\": true}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ainda não tem análise")));

        gravar("Primeira.");
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"oculta\": true}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oculta").value(true))
                // O professor continua lendo o que ocultou.
                .andExpect(jsonPath("$.analise.ponto_principal").value("Primeira."))
                .andExpect(jsonPath("$.escrita_por").value("Professora Ana"));
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.analise").doesNotExist());
        // Ocultar não pede outra: sem novidade, o aluno não volta para a fila.
        comando("listar_analises_pendentes", "{}").andExpect(jsonPath("$.na_fila").value(0));

        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"oculta\": false}", ADMIN)
                .andExpect(jsonPath("$.oculta").value(false));
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.analise.ponto_principal").value("Primeira."));
    }

    @Test
    void pedirOutraOcultaAAtualEPoeOAlunoNoComecoDaFilaComAOrientacao() throws Exception {
        gravar("Primeira.");
        post("/api/admin/alunos/" + ALUNO + "/analise/refazer", "{\"orientacao\": \"Não fale da questão 2, ela estava errada.\"}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oculta").value(true))
                .andExpect(jsonPath("$.refazer_pedido_em").exists())
                .andExpect(jsonPath("$.na_fila").value(true))
                .andExpect(jsonPath("$.motivo_da_fila").value("o professor pediu outra"));
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.analise").doesNotExist());

        comando("listar_analises_pendentes", "{}")
                .andExpect(jsonPath("$.alunos[0].motivo").value("o professor pediu outra"))
                .andExpect(jsonPath("$.alunos[0].orientacao_do_professor").value("Não fale da questão 2, ela estava errada."))
                .andExpect(jsonPath("$.alunos[0].analise_anterior.ponto_principal").value("Primeira."));

        // A nova nasce visível e atende o pedido.
        gravar("Segunda.");
        get("/api/aluno/analise", ALUNO).andExpect(jsonPath("$.analise.ponto_principal").value("Segunda."));
        get("/api/admin/alunos/" + ALUNO + "/analise", ADMIN)
                .andExpect(jsonPath("$.oculta").value(false))
                .andExpect(jsonPath("$.refazer_pedido_em").doesNotExist())
                .andExpect(jsonPath("$.na_fila").value(false));
        // A anterior fica de histórico.
        assertThat(contar("student_analyses")).isEqualTo(2);
    }

    // --- quem pode -----------------------------------------------------------------

    @Test
    void cadaUmNaSuaVisao() throws Exception {
        gravar("Do Bruno.");
        var maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        matricular(maria, 10);

        // O aluno só lê a própria, e não há rota do portal que escreva análise.
        get("/api/aluno/analise", maria).andExpect(jsonPath("$.analise").doesNotExist());
        get("/api/admin/alunos/" + ALUNO + "/analise", ALUNO).andExpect(status().isForbidden());
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"oculta\": true}", ALUNO).andExpect(status().isForbidden());
        post("/api/admin/alunos/" + ALUNO + "/analise/refazer", "{}", ALUNO).andExpect(status().isForbidden());
        get("/api/aluno/analise", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("visão do aluno")));
        // Pelos comandos, só operador: o aluno não lista nem grava.
        comando("listar_analises_pendentes", "{}", ALUNO).andExpect(status().isForbidden());
        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Do aluno.") + "]}", ALUNO).andExpect(status().isForbidden());
        assertThat(contar("student_analyses")).isEqualTo(1);
    }
}
