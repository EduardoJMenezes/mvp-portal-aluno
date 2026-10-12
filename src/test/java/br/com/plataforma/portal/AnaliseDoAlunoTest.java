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
 * A análise escrita e a liberação do resultado. O simulado fecha na hora marcada, mas o aluno só vê
 * o resultado (nota, gabarito, gráficos e a análise dele) quando o professor libera, depois de o
 * Claude dele escrever as análises. A análise geral, de tudo o que o aluno respondeu, é só do
 * professor.
 */
class AnaliseDoAlunoTest extends BaseDoPortal {

    private int maria;
    private int simulado;
    private int q1;
    private int q3;

    @BeforeEach
    void cenario() {
        comSenhas();
        maria = criarUsuario("Maria", "maria@teste.invalid", SENHA, "ALUNO", false);
        matricular(maria, 10);
        var estequiometria = jdbc.queryForObject("INSERT INTO subjects (nome) VALUES ('Estequiometria') RETURNING id", Integer.class);
        var mol = jdbc.queryForObject("INSERT INTO subtopics (assunto_id, nome) VALUES (?, 'Mol') RETURNING id", Integer.class, estequiometria);
        var atomistica = jdbc.queryForObject("INSERT INTO subjects (nome) VALUES ('Atomística') RETURNING id", Integer.class);

        // Quatro questões, todas com gabarito A: duas de Mol, duas de Atomística.
        q1 = questao("Quantos mols há em 18 g de água?\n\n![](figura:7)", estequiometria, mol);
        var q2 = questao("Q2", estequiometria, mol);
        q3 = questao("Q3", atomistica, null);
        var q4 = questao("Q4", atomistica, null);
        jdbc.update("UPDATE question_options SET comentario = 'Você confundiu massa molar com massa atômica. Falta revisar: massa molar.' WHERE questao_id = ? AND letra = 'B'", q1);
        jdbc.update("UPDATE question_options SET comentario = 'Você usou a massa molar da água corretamente.' WHERE questao_id = ? AND letra = 'A'", q1);

        // A prova já fechou, mas o resultado espera o professor, como todo simulado de verdade.
        simulado = criarSimulado("Simulado 02", "PUBLICADO", "2026-01-01T10:00:00Z", "2026-01-02T10:00:00Z");
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", simulado);
        segurarResultado(simulado);
        var questoes = new int[] {q1, q2, q3, q4};
        for (var i = 0; i < questoes.length; i++) {
            porNaProva(simulado, questoes[i], i + 1);
        }
        // Bruno erra a 1 e deixa a 4 em branco; Maria erra só a 3.
        prova(simulado, ALUNO, questoes, "B", "A", "A", null);
        prova(simulado, maria, questoes, "A", "A", "C", "A");
    }

    private int questao(String enunciado, int assunto, Integer subassunto) {
        var id = criarQuestao(enunciado, "A", "MEDIA", "PUBLICADO");
        jdbc.update("INSERT INTO question_subjects (questao_id, assunto_id, subassunto_id) VALUES (?, ?, ?)", id, assunto, subassunto);
        return id;
    }

    /** Uma prova entregue: a letra de cada questão, ou nulo para a que ficou em branco. */
    private void prova(int simulado, int aluno, int[] questoes, String... letras) {
        var tentativa = jdbc.queryForObject(
                "INSERT INTO exam_attempts (simulado_id, aluno_id, finalizado_em) VALUES (?, ?, now()) RETURNING id",
                Integer.class, simulado, aluno);
        for (var i = 0; i < questoes.length; i++) {
            if (letras[i] != null) {
                jdbc.update("INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta) VALUES (?, ?, ?, ?)",
                        tentativa, questoes[i], letras[i], "A".equals(letras[i]));
            }
        }
    }

    private static String analise(int aluno, String ponto) {
        return "{\"aluno_id\": %d, \"ponto_principal\": \"%s\", \"padroes\": \"Padrão.\", \"melhorou\": \"\", \"proximos_passos\": \"Passo.\"}"
                .formatted(aluno, ponto);
    }

    private void gravarDoSimulado(int aluno, String ponto) throws Exception {
        comando("gravar_analises", "{\"simulado\": \"Simulado 02\", \"analises\": [" + analise(aluno, ponto) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gravadas").value(1));
    }

    private void liberar() throws Exception {
        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado 02\"}").andExpect(status().isOk());
    }

    // --- o resultado espera o professor --------------------------------------------

    @Test
    void oSimuladoFechaMasOResultadoSoSaiQuandoOProfessorLibera() throws Exception {
        // Fechado e não liberado: nada do resultado sai, por nenhuma porta.
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ainda não foi liberado pelo professor")));
        get("/api/aluno/simulados", ALUNO)
                .andExpect(jsonPath("$[0].situacao").value("ENCERRADO"))
                .andExpect(jsonPath("$[0].resultado_disponivel").value(false));
        get("/api/aluno/simulados/" + simulado, ALUNO)
                .andExpect(jsonPath("$.resultado_disponivel").value(false))
                .andExpect(jsonPath("$.aguardando_resultado").value(true));
        get("/api/aluno/desempenho", ALUNO).andExpect(jsonPath("$.simulados").isEmpty());
        get("/api/aluno/desempenho/assuntos", ALUNO).andExpect(jsonPath("$.respostas").value(0));
        // O professor vê a prova entregue a qualquer momento.
        get("/api/admin/alunos/" + ALUNO + "/assuntos", ADMIN).andExpect(jsonPath("$.respostas").value(4));

        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado 02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado").value("Simulado 02"))
                .andExpect(jsonPath("$.liberado_agora").value(true))
                .andExpect(jsonPath("$.liberado_em").exists())
                .andExpect(jsonPath("$.participantes").value(2))
                .andExpect(jsonPath("$.com_analise").value(0))
                .andExpect(jsonPath("$.sem_analise").value(2));
        assertThat(jdbc.queryForObject("SELECT resultado_liberado_por_id FROM exams WHERE id = ?", Integer.class, simulado)).isEqualTo(ADMIN);

        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acertos").value(2))
                // Sem análise escrita, o aluno vê a leitura crua: a nota e o gráfico por assunto.
                .andExpect(jsonPath("$.analise_escrita").doesNotExist())
                .andExpect(jsonPath("$.por_assunto.length()").value(2));
        get("/api/aluno/simulados", ALUNO).andExpect(jsonPath("$[0].resultado_disponivel").value(true));
        get("/api/aluno/desempenho", ALUNO).andExpect(jsonPath("$.simulados[0].titulo").value("Simulado 02"));
        get("/api/aluno/desempenho/assuntos", ALUNO).andExpect(jsonPath("$.respostas").value(4));

        // Liberar de novo não muda nada, e diz que já estava.
        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado 02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liberado_agora").value(false));
    }

    @Test
    void oGraficoPorAssuntoPoeOAlunoAoLadoDaMediaDeQuemFez() throws Exception {
        liberar();
        // Mol: Bruno 1 de 2; a turma acertou 3 das 4 respostas. Atomística: Bruno 1 de 2 (a em branco é erro);
        // a turma, 2 de 4. Empate em 50%: vem em ordem alfabética.
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(jsonPath("$.por_assunto[*].assunto").value(contains("Atomística", "Estequiometria")))
                .andExpect(jsonPath("$.por_assunto[0].questoes").value(2))
                .andExpect(jsonPath("$.por_assunto[0].acertos").value(1))
                .andExpect(jsonPath("$.por_assunto[0].percentual").value(50.0))
                .andExpect(jsonPath("$.por_assunto[0].media_da_turma").value(50.0))
                .andExpect(jsonPath("$.por_assunto[1].media_da_turma").value(75.0));
    }

    @Test
    void oProfessorTambemLiberaPeloPortal() throws Exception {
        post("/api/admin/simulados/" + simulado + "/resultado/liberar", "{}", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liberado_agora").value(true));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(status().isOk());
        // O professor vê no simulado que o resultado saiu.
        comando("detalhar_simulado", "{\"simulado\": \"Simulado 02\"}").andExpect(jsonPath("$.resultado_liberado_em").exists());
    }

    @Test
    void simuladoQueNaoFechouNaoTemAnaliseNemResultadoParaLiberar() throws Exception {
        var aberto = criarSimulado("Simulado aberto", "PUBLICADO", "2026-01-01T10:00:00Z", "2099-01-01T10:00:00Z");
        segurarResultado(aberto);
        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado aberto\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ainda não fechou")));
        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado aberto\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("depois do fechamento")));
        comando("gravar_analises", "{\"simulado\": \"Simulado aberto\", \"analises\": [" + analise(ALUNO, "Cedo.") + "]}")
                .andExpect(status().isBadRequest());
        assertThat(contar("student_analyses")).isZero();
    }

    // --- as análises do simulado ---------------------------------------------------

    @Test
    void aFilaDoSimuladoTrazSoAProvaComOComentarioDaAlternativaMarcada() throws Exception {
        // Outra prova dele, com resultado já liberado, e uma terceira ainda presa.
        var anterior = criarSimulado("Simulado 01", "PUBLICADO", "2025-12-01T10:00:00Z", "2025-12-02T10:00:00Z");
        porNaProva(anterior, q3, 1);
        prova(anterior, ALUNO, new int[] {q3}, "A");
        var presa = criarSimulado("Simulado preso", "PUBLICADO", "2025-12-10T10:00:00Z", "2025-12-11T10:00:00Z");
        segurarResultado(presa);
        porNaProva(presa, q3, 1);
        prova(presa, ALUNO, new int[] {q3}, "B");

        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado 02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorte").value("SIMULADO"))
                .andExpect(jsonPath("$.simulado").value("Simulado 02"))
                .andExpect(jsonPath("$.resultado_liberado").value(false))
                .andExpect(jsonPath("$.participantes").value(2))
                .andExpect(jsonPath("$.questoes").value(4))
                // Só a primeira questão tem comentário do professor.
                .andExpect(jsonPath("$.questoes_sem_comentario").value(3))
                .andExpect(jsonPath("$.na_fila").value(2))
                .andExpect(jsonPath("$.restam").value(0))
                .andExpect(jsonPath("$.alunos[*].aluno").value(contains("Aluno Bruno", "Maria")))
                // Só as quatro respostas desta prova: as outras duas provas não entram na conta.
                .andExpect(jsonPath("$.alunos[0].respostas").value(4))
                .andExpect(jsonPath("$.alunos[0].acertos").value(2))
                .andExpect(jsonPath("$.alunos[0].erros").value(2))
                .andExpect(jsonPath("$.alunos[0].por_assunto[*].nome").value(org.hamcrest.Matchers.containsInAnyOrder("Estequiometria", "Atomística")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[*].onde").value(contains("Simulado 02, questão 1", "Simulado 02, questão 4")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].marcada").value("B"))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].gabarito").value("A"))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].comentario_da_marcada").value(containsString("massa molar com massa atômica")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].comentario_da_correta").value(containsString("corretamente")))
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].subassunto").value("Mol"))
                // A marca da figura não vai: é texto para caber num resumo.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[0].enunciado").value("Quantos mols há em 18 g de água?"))
                // Em branco: errou, sem alternativa e sem comentário.
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].marcada").doesNotExist())
                .andExpect(jsonPath("$.alunos[0].erros_recentes[1].comentario_da_marcada").doesNotExist())
                // A outra prova dele entra como ele a vê: a de resultado preso fica de fora do texto.
                .andExpect(jsonPath("$.alunos[0].outros_simulados.length()").value(1))
                .andExpect(jsonPath("$.alunos[0].outros_simulados[0].titulo").value("Simulado 01"))
                .andExpect(jsonPath("$.alunos[0].outros_simulados[0].acertos").value(1))
                .andExpect(jsonPath("$.alunos[0].outros_simulados[0].total").value(1))
                .andExpect(jsonPath("$.alunos[0].analise_anterior").doesNotExist());
    }

    @Test
    void aAnaliseGravadaSoChegaAoAlunoComOResultadoLiberado() throws Exception {
        comando("gravar_analises", "{\"simulado\": \"Simulado 02\", \"analises\": [" + analise(ALUNO, "Seu ponto principal: **mol**.") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorte").value("SIMULADO"))
                .andExpect(jsonPath("$.gravadas").value(1))
                .andExpect(jsonPath("$.alunos[0]").value("Aluno Bruno"))
                .andExpect(jsonPath("$.recusadas").isEmpty())
                .andExpect(jsonPath("$.restam").value(1))
                .andExpect(jsonPath("$.resultado_liberado").value(false));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(status().isBadRequest());
        // Quem já tem análise sai da fila.
        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado 02\"}")
                .andExpect(jsonPath("$.na_fila").value(1))
                .andExpect(jsonPath("$.alunos[0].aluno").value("Maria"));

        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado 02\"}")
                .andExpect(jsonPath("$.com_analise").value(1))
                .andExpect(jsonPath("$.sem_analise").value(1));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(jsonPath("$.analise_escrita.ponto_principal").value("Seu ponto principal: **mol**."))
                .andExpect(jsonPath("$.analise_escrita.padroes").value("Padrão."))
                // Bloco em branco não é guardado como texto vazio.
                .andExpect(jsonPath("$.analise_escrita.melhorou").doesNotExist())
                .andExpect(jsonPath("$.analise_escrita.proximos_passos").value("Passo."))
                .andExpect(jsonPath("$.analise_escrita.escrita_em").exists());
        // Cada um lê a sua: Maria ainda não tem.
        get("/api/aluno/simulados/" + simulado + "/resultado", maria).andExpect(jsonPath("$.analise_escrita").doesNotExist());

        // Gravada depois da liberação, chega na hora.
        gravarDoSimulado(maria, "Da Maria.");
        get("/api/aluno/simulados/" + simulado + "/resultado", maria)
                .andExpect(jsonPath("$.analise_escrita.ponto_principal").value("Da Maria."));
        var linha = jdbc.queryForMap("SELECT simulado_id, respostas, escrita_por_id FROM student_analyses WHERE aluno_id = ?", ALUNO);
        assertThat(linha.get("simulado_id")).isEqualTo(simulado);
        assertThat(linha.get("respostas")).isEqualTo(4);
        assertThat(linha.get("escrita_por_id")).isEqualTo(ADMIN);
    }

    @Test
    void refazerAAnaliseDeUmAlunoGuardaAAnteriorDeHistorico() throws Exception {
        gravarDoSimulado(ALUNO, "Primeira.");
        // Pedido pelo nome, ele vem mesmo já tendo análise, com a anterior junto.
        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado 02\", \"aluno\": \"Bruno\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunos.length()").value(1))
                .andExpect(jsonPath("$.alunos[0].analise_anterior.ponto_principal").value("Primeira."));
        gravarDoSimulado(ALUNO, "Segunda.");
        liberar();

        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(jsonPath("$.analise_escrita.ponto_principal").value("Segunda."));
        get("/api/admin/alunos/" + ALUNO + "/analise?simulado=" + simulado, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.simulado").value("Simulado 02"))
                .andExpect(jsonPath("$.analise.ponto_principal").value("Segunda."))
                .andExpect(jsonPath("$.escrita_por").value("Professora Ana"))
                .andExpect(jsonPath("$.anteriores.length()").value(1))
                .andExpect(jsonPath("$.anteriores[0].ponto_principal").value("Primeira."));
    }

    @Test
    void oProfessorOcultaEDevolveAAnaliseDoSimulado() throws Exception {
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"simulado\": \"%d\", \"oculta\": true}".formatted(simulado), ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ainda não tem análise")));
        gravarDoSimulado(ALUNO, "Primeira.");
        liberar();

        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"simulado\": \"%d\", \"oculta\": true}".formatted(simulado), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oculta").value(true))
                // O professor continua lendo o que ocultou.
                .andExpect(jsonPath("$.analise.ponto_principal").value("Primeira."));
        // O aluno fica com o resto do resultado, sem o texto.
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.analise_escrita").doesNotExist());

        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"simulado\": \"%d\", \"oculta\": false}".formatted(simulado), ADMIN)
                .andExpect(jsonPath("$.oculta").value(false));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(jsonPath("$.analise_escrita.ponto_principal").value("Primeira."));
    }

    @Test
    void aRecusadaVoltaComOMotivoEAsOutrasEntram() throws Exception {
        var jose = criarUsuario("José", "jose@teste.invalid", SENHA, "ALUNO", false);
        var corpo = "{\"simulado\": \"Simulado 02\", \"analises\": [%s, %s, %s, %s, %s, %s]}".formatted(
                analise(ALUNO, "Vale."),
                analise(ALUNO, "Repetida."),
                analise(999, "Ninguém."),
                analise(ADMIN, "Professora não é aluna."),
                analise(jose, "Não fez a prova."),
                "{\"aluno_id\": %d, \"ponto_principal\": \"  \"}".formatted(maria));
        comando("gravar_analises", corpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gravadas").value(1))
                .andExpect(jsonPath("$.recusadas.length()").value(5))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("duas vezes")))
                .andExpect(jsonPath("$.recusadas[1].motivo").value(containsString("não existe aluno com id 999")))
                .andExpect(jsonPath("$.recusadas[2].motivo").value(containsString("não existe aluno")))
                .andExpect(jsonPath("$.recusadas[3].motivo").value(containsString("José não fez 'Simulado 02'")))
                .andExpect(jsonPath("$.recusadas[4].motivo").value(containsString("ponto_principal é obrigatório")));
        assertThat(contar("student_analyses")).isEqualTo(1);

        comando("gravar_analises", "{\"simulado\": \"Simulado 02\", \"analises\": [" + analise(maria, "x".repeat(701)) + "]}")
                .andExpect(jsonPath("$.gravadas").value(0))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("o limite é 700")));
        comando("gravar_analises", "{\"analises\": []}").andExpect(status().isBadRequest());
        // A análise geral de quem nunca respondeu nada não tem do que falar.
        comando("gravar_analises", "{\"analises\": [" + analise(jose, "Vazia.") + "]}")
                .andExpect(jsonPath("$.gravadas").value(0))
                .andExpect(jsonPath("$.recusadas[0].motivo").value(containsString("ainda não respondeu nenhuma questão")));
        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado 02\", \"aluno\": \"José\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("José não fez 'Simulado 02'")));
    }

    // --- a análise geral, que é do professor ---------------------------------------

    @Test
    void aAnaliseGeralJuntaTudoEFicaSoComOProfessor() throws Exception {
        comando("listar_analises_pendentes", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Informe o simulado")));
        // O professor vê a prova entregue mesmo com o resultado preso: é a visão dele.
        comando("listar_analises_pendentes", "{\"aluno\": \"Bruno\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorte").value("GERAL"))
                .andExpect(jsonPath("$.simulado").doesNotExist())
                .andExpect(jsonPath("$.alunos[0].aluno_id").value(ALUNO))
                .andExpect(jsonPath("$.alunos[0].respostas").value(4))
                .andExpect(jsonPath("$.alunos[0].simulados[0].titulo").value("Simulado 02"))
                .andExpect(jsonPath("$.alunos[0].simulados[0].acertos").value(2));

        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Bruno confunde massa molar com massa atômica.") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorte").value("GERAL"))
                .andExpect(jsonPath("$.gravadas").value(1))
                .andExpect(jsonPath("$.restam").doesNotExist());
        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Segunda leitura.") + "]}").andExpect(status().isOk());

        get("/api/admin/alunos/" + ALUNO + "/analise", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado").doesNotExist())
                .andExpect(jsonPath("$.analise.ponto_principal").value("Segunda leitura."))
                // As anteriores ficam para o professor reconsultar.
                .andExpect(jsonPath("$.anteriores[0].ponto_principal").value("Bruno confunde massa molar com massa atômica."));
        // Na próxima vez, quem escreve recebe a anterior junto.
        comando("listar_analises_pendentes", "{\"aluno\": \"Bruno\"}")
                .andExpect(jsonPath("$.alunos[0].analise_anterior.ponto_principal").value("Segunda leitura."));

        // O aluno não tem por onde ler a geral: nem rota própria, nem no resultado da prova.
        liberar();
        get("/api/aluno/analise", ALUNO).andExpect(status().isNotFound());
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(jsonPath("$.analise_escrita").doesNotExist());
        // E não há o que ocultar nela.
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"oculta\": true}", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("já é só do professor")));
    }

    @Test
    void oMaterialDaGeralAgrupaOsErrosPorPontoESeparaOQueFicouEmBranco() throws Exception {
        // Uma segunda prova, com duas questões repetidas: o Bruno acerta a 1, que tinha errado, e deixa a 4 em branco de novo.
        var outra = criarSimulado("Simulado 03", "PUBLICADO", "2026-02-01T10:00:00Z", "2026-02-02T10:00:00Z");
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, 10)", outra);
        var q4 = jdbc.queryForObject("SELECT id FROM questions WHERE enunciado = 'Q4'", Integer.class);
        porNaProva(outra, q1, 1);
        porNaProva(outra, q4, 2);
        prova(outra, ALUNO, new int[] {q1, q4}, "A", null);

        comando("listar_analises_pendentes", "{\"aluno\": \"Bruno\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunos[0].respostas").value(6))
                .andExpect(jsonPath("$.alunos[0].acertos").value(3))
                // Errar marcando e deixar em branco são coisas diferentes: da segunda não se sabe o porquê.
                .andExpect(jsonPath("$.alunos[0].marcou_errado").value(1))
                .andExpect(jsonPath("$.alunos[0].em_branco").value(2))
                .andExpect(jsonPath("$.alunos[0].turmas").value(contains("Extensivo 2027")))
                // As provas, da mais antiga para a mais nova, com a média de quem fez e a posição dele.
                .andExpect(jsonPath("$.alunos[0].simulados[*].titulo").value(contains("Simulado 02", "Simulado 03")))
                .andExpect(jsonPath("$.alunos[0].simulados[0].em_branco").value(1))
                .andExpect(jsonPath("$.alunos[0].simulados[0].media").value(62.5))
                .andExpect(jsonPath("$.alunos[0].simulados[0].posicao").value(2))
                .andExpect(jsonPath("$.alunos[0].simulados[0].participantes").value(2))
                // Cada ponto de erro, com os exemplos comentados; o que ficou em branco só entra na conta.
                .andExpect(jsonPath("$.alunos[0].onde_erra[*].assunto").value(contains("Atomística", "Estequiometria")))
                .andExpect(jsonPath("$.alunos[0].onde_erra[0].em_branco").value(2))
                .andExpect(jsonPath("$.alunos[0].onde_erra[0].exemplos").isEmpty())
                .andExpect(jsonPath("$.alunos[0].onde_erra[1].subassunto").value("Mol"))
                .andExpect(jsonPath("$.alunos[0].onde_erra[1].marcou_errado").value(1))
                .andExpect(jsonPath("$.alunos[0].onde_erra[1].exemplos[0].onde").value("Simulado 02, questão 1"))
                .andExpect(jsonPath("$.alunos[0].onde_erra[1].exemplos[0].marcada").value("B"))
                .andExpect(jsonPath("$.alunos[0].onde_erra[1].exemplos[0].comentario_da_marcada").value(containsString("massa molar")))
                // A mesma questão duas vezes é um fato só: corrigiu, ou errou de novo.
                .andExpect(jsonPath("$.alunos[0].repetidas[*].leitura").value(contains("CORRIGIU", "ERROU_DE_NOVO")))
                .andExpect(jsonPath("$.alunos[0].repetidas[0].vezes[*].onde").value(contains("Simulado 02, questão 1", "Simulado 03, questão 1")))
                .andExpect(jsonPath("$.alunos[0].repetidas[1].vezes[0].marcada").doesNotExist())
                // Ao lado dos colegas, nas mesmas questões: a Maria acertou as duas de Mol e uma das de Atomística.
                .andExpect(jsonPath("$.alunos[0].colegas").value(1))
                .andExpect(jsonPath("$.alunos[0].por_assunto[?(@.nome == 'Estequiometria')].colegas").value(contains(100.0)))
                .andExpect(jsonPath("$.alunos[0].por_assunto[?(@.nome == 'Atomística')].colegas").value(contains(50.0)))
                .andExpect(jsonPath("$.alunos[0].por_assunto[?(@.nome == 'Atomística')].em_branco").value(contains(2)));
    }

    @Test
    void oPanoramaDaTelaTemOsMesmosNumerosEAvisaQuandoAAnaliseFicouVelha() throws Exception {
        get("/api/admin/alunos/" + ALUNO + "/panorama", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.respostas").value(4))
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.erros").value(1))
                .andExpect(jsonPath("$.em_branco").value(1))
                .andExpect(jsonPath("$.percentual").value(50.0))
                .andExpect(jsonPath("$.de_simulado").value(4))
                .andExpect(jsonPath("$.simulados[0].percentual").value(50.0))
                .andExpect(jsonPath("$.simulados[0].encerrado").value(true))
                .andExpect(jsonPath("$.curso.total").exists())
                .andExpect(jsonPath("$.analise_geral_em").doesNotExist())
                .andExpect(jsonPath("$.respostas_novas").doesNotExist());

        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Geral do Bruno.") + "]}").andExpect(status().isOk());
        get("/api/admin/alunos/" + ALUNO + "/panorama", ADMIN)
                .andExpect(jsonPath("$.analise_geral_em").exists())
                .andExpect(jsonPath("$.respostas_novas").value(0));

        // Ele faz outra prova: a análise que está na tela já não leu tudo.
        var outra = criarSimulado("Simulado 03", "PUBLICADO", "2026-02-01T10:00:00Z", "2026-02-02T10:00:00Z");
        porNaProva(outra, q1, 1);
        porNaProva(outra, q3, 2);
        prova(outra, ALUNO, new int[] {q1, q3}, "A", "A");
        get("/api/admin/alunos/" + ALUNO + "/panorama", ADMIN).andExpect(jsonPath("$.respostas_novas").value(2));

        // Na tela, cada ponto de erro vem com o que há no curso do aluno para indicar.
        get("/api/admin/alunos/" + ALUNO + "/assuntos", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onde_revisar[*].topico").value(org.hamcrest.Matchers.containsInAnyOrder("Mol", "Atomística")));
        get("/api/admin/alunos/" + ALUNO + "/panorama", ALUNO).andExpect(status().isForbidden());
    }

    @Test
    void oProfessorAchaOAlunoPeloNomeSemSePreocuparComAcento() throws Exception {
        var jose = criarUsuario("José Antônio", "jose@teste.invalid", SENHA, "ALUNO", false);
        matricular(jose, 11);
        comando("gravar_analises", "{\"analises\": [" + analise(ALUNO, "Geral do Bruno.") + "]}").andExpect(status().isOk());

        get("/api/admin/alunos", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nome").value(contains("Aluno Bruno", "José Antônio", "Maria")))
                .andExpect(jsonPath("$[0].turmas").value(contains("Extensivo 2027")))
                // Quem já tem análise geral aparece marcado, com a data.
                .andExpect(jsonPath("$[0].analise_geral_em").exists())
                .andExpect(jsonPath("$[1].analise_geral_em").doesNotExist());
        get("/api/admin/alunos?busca=jose anto", ADMIN)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(jose))
                .andExpect(jsonPath("$[0].turmas").value(contains("Intensivo 2027")));
        get("/api/admin/alunos?busca=maria@teste", ADMIN).andExpect(jsonPath("$[0].nome").value("Maria"));
        get("/api/admin/alunos?busca=ninguem", ADMIN).andExpect(jsonPath("$").isEmpty());
    }

    // --- o simulado depois da prova, para o professor ------------------------------

    @Test
    void oProfessorVeComoATurmaFoiEOndeMaisErrou() throws Exception {
        get("/api/admin/simulados/" + simulado + "/analise", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Simulado 02"))
                .andExpect(jsonPath("$.situacao").value("ENCERRADO"))
                .andExpect(jsonPath("$.resultado_liberado_em").doesNotExist())
                .andExpect(jsonPath("$.matriculados").value(2))
                .andExpect(jsonPath("$.participantes").value(2))
                // Maria fez 3 de 4 e Bruno 2 de 4.
                .andExpect(jsonPath("$.media").value(62.5))
                .andExpect(jsonPath("$.com_analise").value(0))
                .andExpect(jsonPath("$.questoes").value(4))
                .andExpect(jsonPath("$.questoes_sem_comentario").value(3))
                // Do assunto em que a turma foi pior para o melhor.
                .andExpect(jsonPath("$.por_assunto[*].assunto").value(contains("Atomística", "Estequiometria")))
                .andExpect(jsonPath("$.por_assunto[0].percentual").value(50.0))
                .andExpect(jsonPath("$.por_assunto[1].percentual").value(75.0))
                // As questões em que alguém errou, com a alternativa errada mais marcada e o que o professor disse dela.
                .andExpect(jsonPath("$.mais_erradas[*].ordem").value(contains(1, 3, 4)))
                .andExpect(jsonPath("$.mais_erradas[0].percentual_acerto").value(50.0))
                .andExpect(jsonPath("$.mais_erradas[0].gabarito").value("A"))
                .andExpect(jsonPath("$.mais_erradas[0].erro_mais_comum").value("B"))
                .andExpect(jsonPath("$.mais_erradas[0].quantos_marcaram").value(1))
                .andExpect(jsonPath("$.mais_erradas[0].comentario_do_erro").value(containsString("massa molar com massa atômica")))
                .andExpect(jsonPath("$.mais_erradas[0].subassunto").value("Mol"))
                .andExpect(jsonPath("$.mais_erradas[1].erro_mais_comum").value("C"))
                .andExpect(jsonPath("$.mais_erradas[1].comentario_do_erro").doesNotExist())
                // Na 4 ninguém marcou errado: quem não acertou deixou em branco.
                .andExpect(jsonPath("$.mais_erradas[2].em_branco").value(1))
                .andExpect(jsonPath("$.mais_erradas[2].erro_mais_comum").doesNotExist())
                // O ranking, de onde o professor abre o resultado de cada um.
                .andExpect(jsonPath("$.alunos[*].aluno").value(contains("Maria", "Aluno Bruno")))
                .andExpect(jsonPath("$.alunos[0].posicao").value(1))
                .andExpect(jsonPath("$.alunos[0].acertos").value(3))
                .andExpect(jsonPath("$.alunos[1].aluno_id").value(ALUNO))
                .andExpect(jsonPath("$.alunos[1].posicao").value(2))
                .andExpect(jsonPath("$.alunos[1].em_branco").value(1))
                .andExpect(jsonPath("$.alunos[1].percentual").value(50.0))
                .andExpect(jsonPath("$.alunos[1].tem_analise").value(false));

        gravarDoSimulado(ALUNO, "Do Bruno.");
        liberar();
        get("/api/admin/simulados/" + simulado + "/analise", ADMIN)
                .andExpect(jsonPath("$.resultado_liberado_em").exists())
                .andExpect(jsonPath("$.com_analise").value(1))
                .andExpect(jsonPath("$.alunos[1].tem_analise").value(true))
                .andExpect(jsonPath("$.alunos[1].analise_oculta").value(false))
                .andExpect(jsonPath("$.alunos[0].tem_analise").value(false));
    }

    @Test
    void oProfessorAbreOResultadoDeUmAlunoComoEleOVe() throws Exception {
        // Antes de liberar, o aluno não vê; o professor vê a prévia do que vai sair.
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO).andExpect(status().isBadRequest());
        get("/api/admin/simulados/" + simulado + "/alunos/" + ALUNO + "/resultado", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aluno_id").value(ALUNO))
                .andExpect(jsonPath("$.aluno").value("Aluno Bruno"))
                .andExpect(jsonPath("$.titulo").value("Simulado 02"))
                .andExpect(jsonPath("$.acertos").value(2))
                .andExpect(jsonPath("$.em_branco").value(1))
                .andExpect(jsonPath("$.posicao").value(2))
                .andExpect(jsonPath("$.questoes.length()").value(4))
                .andExpect(jsonPath("$.questoes[0].marcada").value("B"))
                .andExpect(jsonPath("$.questoes[0].comentarios.B").value(containsString("massa molar")))
                .andExpect(jsonPath("$.por_assunto.length()").value(2))
                .andExpect(jsonPath("$.analise_escrita").doesNotExist())
                .andExpect(jsonPath("$.analise_oculta").value(false));

        // A análise aparece para o professor mesmo oculta, com a marca de que o aluno não a vê.
        gravarDoSimulado(ALUNO, "Do Bruno.");
        liberar();
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"simulado\": \"%d\", \"oculta\": true}".formatted(simulado), ADMIN)
                .andExpect(status().isOk());
        get("/api/admin/simulados/" + simulado + "/alunos/" + ALUNO + "/resultado", ADMIN)
                .andExpect(jsonPath("$.analise_escrita.ponto_principal").value("Do Bruno."))
                .andExpect(jsonPath("$.analise_oculta").value(true));
        get("/api/aluno/simulados/" + simulado + "/resultado", ALUNO)
                .andExpect(jsonPath("$.analise_escrita").doesNotExist())
                // Para o aluno, esses campos nem existem.
                .andExpect(jsonPath("$.analise_oculta").doesNotExist())
                .andExpect(jsonPath("$.aluno").doesNotExist());

        var jose = criarUsuario("José", "jose@teste.invalid", SENHA, "ALUNO", false);
        get("/api/admin/simulados/" + simulado + "/alunos/" + jose + "/resultado", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("José não fez 'Simulado 02'")));
        // Aluno não abre o resultado de ninguém por esta porta, nem o próprio.
        get("/api/admin/simulados/" + simulado + "/alunos/" + ALUNO + "/resultado", ALUNO).andExpect(status().isForbidden());
        get("/api/admin/simulados/" + simulado + "/analise", ALUNO).andExpect(status().isForbidden());
    }

    // --- quem pode -----------------------------------------------------------------

    @Test
    void soOProfessorEscreveLiberaELe() throws Exception {
        gravarDoSimulado(ALUNO, "Do Bruno.");

        get("/api/admin/alunos", ALUNO).andExpect(status().isForbidden());
        get("/api/admin/alunos/" + ALUNO + "/analise", ALUNO).andExpect(status().isForbidden());
        post("/api/admin/alunos/" + ALUNO + "/analise/visibilidade", "{\"simulado\": \"%d\", \"oculta\": true}".formatted(simulado), ALUNO)
                .andExpect(status().isForbidden());
        post("/api/admin/simulados/" + simulado + "/resultado/liberar", "{}", ALUNO).andExpect(status().isForbidden());
        // Pelos comandos, só operador: o aluno não lista, não grava e não libera.
        comando("listar_analises_pendentes", "{\"simulado\": \"Simulado 02\"}", ALUNO).andExpect(status().isForbidden());
        comando("gravar_analises", "{\"simulado\": \"Simulado 02\", \"analises\": [" + analise(ALUNO, "Do aluno.") + "]}", ALUNO)
                .andExpect(status().isForbidden());
        comando("liberar_resultado_simulado", "{\"simulado\": \"Simulado 02\"}", ALUNO).andExpect(status().isForbidden());

        assertThat(contar("student_analyses")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resultado_liberado_em FROM exams WHERE id = ?", java.sql.Timestamp.class, simulado)).isNull();
    }
}
