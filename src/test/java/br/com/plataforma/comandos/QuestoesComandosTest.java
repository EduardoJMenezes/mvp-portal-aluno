package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** O acervo de questões: busca, detalhe, edição e a trava da prova já aberta. */
class QuestoesComandosTest extends BaseDeComando {

    // --- buscar_questoes -----------------------------------------------------

    @Test
    void buscaFiltraPorStatusDificuldadeETexto() throws Exception {
        criarQuestao("Calcule a massa molar do CO2", "A", "FACIL", "PUBLICADO");
        criarQuestao("Qual o reagente limitante?", "B", "DIFICIL", "RASCUNHO");
        criarQuestao("Defina entalpia", "C", "MEDIA", "PUBLICADO");

        comando("buscar_questoes", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].gabarito").value("A"))
                .andExpect(jsonPath("$[0].alternativas.A").value("alternativa A"));

        comando("buscar_questoes", """
                {"status": "publicado"}""")
                .andExpect(jsonPath("$.length()").value(2));

        comando("buscar_questoes", """
                {"dificuldade": "dificil"}""")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].enunciado").value("Qual o reagente limitante?"));

        comando("buscar_questoes", """
                {"busca": "entalpia"}""")
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** O texto digitado é literal: % não vira curinga. */
    @Test
    void oPorcentoNaBuscaELiteral() throws Exception {
        criarQuestao("Rendimento de 80% na reação", "A", "MEDIA", "PUBLICADO");
        criarQuestao("Sem nada a ver", "A", "MEDIA", "PUBLICADO");

        comando("buscar_questoes", """
                {"busca": "80%"}""")
                .andExpect(jsonPath("$.length()").value(1));

        comando("buscar_questoes", """
                {"busca": "%"}""")
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void statusInvalidoDizOQueVale() throws Exception {
        comando("buscar_questoes", """
                {"status": "meio publicado"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Status 'meio publicado' inválido. Use RASCUNHO ou PUBLICADO."));
    }

    @Test
    void limiteEDeslocamentoPaginam() throws Exception {
        for (int n = 1; n <= 5; n++) {
            criarQuestao("Questão " + n, "A", "MEDIA", "PUBLICADO");
        }

        comando("buscar_questoes", """
                {"limite": 2, "deslocamento": 2}""")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].enunciado").value("Questão 3"));
    }

    // --- detalhar_questao ----------------------------------------------------

    @Test
    void detalharTrazTudoEOndeAQuestaoEstaUsada() throws Exception {
        var q = criarQuestao("Calcule a massa molar", "D", "MEDIA", "PUBLICADO");
        var prova = criarSimulado("Simulado 01", "PUBLICADO", "2020-01-01T10:00:00Z", "2020-01-01T14:00:00Z");
        porNaProva(prova, q, 1);

        comando("detalhar_questao", """
                {"questao": "%d"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questao_id").value(q))
                .andExpect(jsonPath("$.gabarito").value("D"))
                .andExpect(jsonPath("$.alternativas.E").value("alternativa E"))
                .andExpect(jsonPath("$.simulados[0].titulo").value("Simulado 01"))
                .andExpect(jsonPath("$.simulados[0].situacao").value("ENCERRADO"))
                .andExpect(jsonPath("$.resolucao").doesNotExist());
    }

    @Test
    void questaoQueNaoExisteEnsinaOndeAchar() throws Exception {
        comando("detalhar_questao", """
                {"questao": "4242"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Questão 4242 não existe no acervo. Use buscar_questoes para achar o id."));
    }

    // --- editar_questao ------------------------------------------------------

    @Test
    void editaEnunciadoGabaritoEUmaAlternativaSo() throws Exception {
        var q = criarQuestao("Enunciado velho", "A", "MEDIA", "RASCUNHO");

        comando("editar_questao", """
                {"questao": "%d", "enunciado": "Enunciado novo", "gabarito": "c",
                 "alternativas": {"C": "a resposta certa"}, "dificuldade": "dificil"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enunciado").value("Enunciado novo"))
                .andExpect(jsonPath("$.gabarito").value("C"))
                .andExpect(jsonPath("$.dificuldade").value("DIFICIL"))
                .andExpect(jsonPath("$.alternativas.C").value("a resposta certa"))
                // as outras letras ficam como estavam: a alteração é parcial
                .andExpect(jsonPath("$.alternativas.A").value("alternativa A"))
                .andExpect(jsonPath("$.alternativas.E").value("alternativa E"));

        assertThat(contar("question_options WHERE questao_id = ?", q)).isEqualTo(5);
    }

    /** Questão com erro se corrige na hora, mesmo com a prova aberta, e por qualquer canal. */
    @Test
    void oQueOAlunoJaViuMudaComProvaAbertaEDeixaRastro() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "PUBLICADO");
        var prova = criarSimulado("Simulado 02", "PUBLICADO", "2020-01-01T10:00:00Z", "2090-01-01T10:00:00Z");
        porNaProva(prova, q, 1);

        comando("editar_questao", """
                {"questao": "%d", "enunciado": "Enunciado corrigido"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enunciado").value("Enunciado corrigido"))
                .andExpect(jsonPath("$.historico[0].resumo").value("Enunciado alterado."))
                .andExpect(jsonPath("$.historico[0].quem").value("Professora Ana"))
                .andExpect(jsonPath("$.historico[0].antes").value(org.hamcrest.Matchers.containsString("Enunciado: Enunciado")));
    }

    /** Classificação, dificuldade e vídeo não mudam o que o aluno lê: não entram no histórico. */
    @Test
    void dificuldadeAindaMudaComProvaAberta() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "PUBLICADO");
        var prova = criarSimulado("Simulado 02", "PUBLICADO", "2020-01-01T10:00:00Z", "2090-01-01T10:00:00Z");
        porNaProva(prova, q, 1);

        comando("editar_questao", """
                {"questao": "%d", "dificuldade": "FACIL"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dificuldade").value("FACIL"));
    }

    @Test
    void assuntoTrocaAClassificacaoInteiraEVazioATira() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "RASCUNHO");
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Pureza e rendimento"]}""")
                .andExpect(status().isOk());
        comando("cadastrar_assunto", """
                {"nome": "Termoquímica"}""").andExpect(status().isOk());

        comando("editar_questao", """
                {"questao": "%d", "assunto": "Estequiometria", "subassunto": "Pureza"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificacao[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.classificacao[0].subassunto").value("Pureza e rendimento"));

        comando("editar_questao", """
                {"questao": "%d", "assunto": "Termoquímica"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificacao.length()").value(1))
                .andExpect(jsonPath("$.classificacao[0].assunto").value("Termoquímica"));

        comando("editar_questao", """
                {"questao": "%d", "assunto": ""}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificacao").isEmpty());

        assertThat(contar("question_subjects")).isZero();
    }

    @Test
    void subassuntoSozinhoERecusado() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "RASCUNHO");

        comando("editar_questao", """
                {"questao": "%d", "subassunto": "Pureza"}""".formatted(q))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Informe o assunto junto do sub-assunto."));
    }

    @Test
    void oVimeoIdEntraESaiDaResolucao() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "RASCUNHO");

        comando("editar_questao", """
                {"questao": "%d", "resolucao": {"vimeo_id": "987654"}}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolucao.vimeo_id").value("987654"))
                .andExpect(jsonPath("$.resolucao.titulo").value("Vídeo 987654"));

        comando("editar_questao", """
                {"questao": "%d", "resolucao": {"vimeo_id": ""}}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolucao").doesNotExist());
    }

    // --- remover_questao -----------------------------------------------------

    @Test
    void removeQuandoTodaProvaJaTerminou() throws Exception {
        var longo = "Enunciado bem longo, de propósito, para o resumo da remoção cortar nos primeiros "
                + "oitenta caracteres e a gente ver o corte acontecendo.";
        var q = criarQuestao(longo, "A", "MEDIA", "PUBLICADO");
        var prova = criarSimulado("Simulado 01", "PUBLICADO", "2020-01-01T10:00:00Z", "2020-01-01T14:00:00Z");
        porNaProva(prova, q, 1);

        comando("remover_questao", """
                {"questao": "%d"}""".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questao_id").value(q))
                .andExpect(jsonPath("$.reversivel").value(true))
                .andExpect(jsonPath("$.enunciado").value(longo.substring(0, 80)));

        assertThat(contar("questions WHERE removido_em IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void naoRemoveComProvaQueAindaNaoTerminou() throws Exception {
        var q = criarQuestao("Enunciado", "A", "MEDIA", "PUBLICADO");
        var prova = criarSimulado("Simulado 03", "PUBLICADO", "2090-01-01T10:00:00Z", "2090-01-02T10:00:00Z");
        porNaProva(prova, q, 1);

        comando("remover_questao", """
                {"questao": "%d"}""".formatted(q))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        ("A questão %d está em simulado que ainda não terminou: 'Simulado 03'. "
                                + "Tire-a da prova com editar_simulado antes de remover.").formatted(q)));

        assertThat(contar("questions WHERE removido_em IS NULL")).isEqualTo(1);
    }

    // --- a figura recortada do print ------------------------------------------

    /** PNG de 1×1 transparente: o suficiente para o sniff de tipo reconhecer. */
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    @Test
    void aFiguraEntraNaMarcaPendenteDoEnunciado() throws Exception {
        var q = criarQuestao("Dê o nome do composto:\n\n![](figura:pendente)", "A", "MEDIA", "RASCUNHO");

        comando("anexar_figura", """
                {"questao": "%d", "conteudo_base64": "%s", "nome": "print 1"}""".formatted(q, PNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questao_id").value(q))
                .andExpect(jsonPath("$.tipo").value("image/png"))
                .andExpect(jsonPath("$.imagem_pendente").value(false));

        assertThat(jdbc.queryForObject("SELECT enunciado FROM questions WHERE id = ?", String.class, q))
                .doesNotContain("figura:pendente")
                .containsPattern("figura:\\d+");
    }

    /** A rede que justifica o recorte automático é o preview antes de aprovar. */
    @Test
    void questaoJaPublicadaNaoRecebeRecorteDePrint() throws Exception {
        var q = criarQuestao("Enunciado ![](figura:pendente)", "A", "MEDIA", "PUBLICADO");

        comando("anexar_figura", """
                {"questao": "%d", "conteudo_base64": "%s", "nome": "print 1"}""".formatted(q, PNG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(("A questão %d já foi publicada: "
                        + "recorte de print só entra em questão de rascunho.").formatted(q)));

        assertThat(contar("images")).isZero();
    }

    /** Recorte que saiu errado troca o arquivo sem mexer no texto. */
    @Test
    void trocarFiguraMantemAReferenciaNoTexto() throws Exception {
        var q = criarQuestao("Enunciado ![](figura:pendente)", "A", "MEDIA", "RASCUNHO");
        var resposta = comando("anexar_figura", """
                {"questao": "%d", "conteudo_base64": "%s", "nome": "print 1"}""".formatted(q, PNG))
                .andReturn().getResponse().getContentAsString();
        int figura = com.jayway.jsonpath.JsonPath.read(resposta, "$.figura_id");
        var antes = jdbc.queryForObject("SELECT enunciado FROM questions WHERE id = ?", String.class, q);

        comando("trocar_figura", """
                {"figura": %d, "questao": "%d", "conteudo_base64": "%s"}""".formatted(figura, q, PNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figura_id").value(figura));

        assertThat(jdbc.queryForObject("SELECT enunciado FROM questions WHERE id = ?", String.class, q))
                .isEqualTo(antes);
        assertThat(contar("images")).isEqualTo(1);
    }

    /** Id trocado pelo modelo não pode apagar em silêncio a figura de outra questão. */
    @Test
    void naoTrocaAFiguraDeOutraQuestao() throws Exception {
        var minha = criarQuestao("Minha ![](figura:pendente)", "A", "MEDIA", "RASCUNHO");
        var alheia = criarQuestao("Alheia ![](figura:pendente)", "A", "MEDIA", "RASCUNHO");
        var resposta = comando("anexar_figura", """
                {"questao": "%d", "conteudo_base64": "%s", "nome": "print 1"}""".formatted(alheia, PNG))
                .andReturn().getResponse().getContentAsString();
        int figura = com.jayway.jsonpath.JsonPath.read(resposta, "$.figura_id");

        comando("trocar_figura", """
                {"figura": %d, "questao": "%d", "conteudo_base64": "%s"}"""
                .formatted(figura, minha, PNG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("A figura %d não é da questão %d.".formatted(figura, minha)));
    }

    // --- classificar várias de uma vez ---------------------------------------

    /** Quem importa não sabe o assunto; quem sabe vem depois e pede "as que estão sem assunto". */
    @Test
    void asSemAssuntoVemComATaxonomiaEOTextoEnxuto() throws Exception {
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Reagente limitante"]}""").andExpect(status().isOk());
        var classificada = criarQuestao("Qual o reagente limitante?", "B", "MEDIA", "PUBLICADO");
        comando("editar_questao", "{\"questao\": \"%d\", \"assunto\": \"Estequiometria\"}".formatted(classificada))
                .andExpect(status().isOk());
        var comFigura = criarQuestao("Observe o gráfico:\n\n![](figura:12)\n\nQual a ordem?", "A", "MEDIA", "PUBLICADO");
        var rascunho = criarQuestao("Defina entalpia", "C", "MEDIA", "RASCUNHO");

        comando("listar_questoes_sem_assunto", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.restam").value(0))
                .andExpect(jsonPath("$.questoes[0].questao_id").value(comFigura))
                .andExpect(jsonPath("$.questoes[0].enunciado").value("Observe o gráfico:\n\n[figura]\n\nQual a ordem?"))
                .andExpect(jsonPath("$.questoes[0].gabarito").value("A"))
                .andExpect(jsonPath("$.questoes[0].alternativas.A").value("alternativa A"))
                .andExpect(jsonPath("$.questoes[1].questao_id").value(rascunho))
                .andExpect(jsonPath("$.questoes[1].status").value("RASCUNHO"))
                .andExpect(jsonPath("$.assuntos[0].nome").value("Estequiometria"))
                .andExpect(jsonPath("$.assuntos[0].subassuntos[0].nome").value("Reagente limitante"));

        comando("listar_questoes_sem_assunto", "{\"limite\": 1}")
                .andExpect(jsonPath("$.questoes.length()").value(1))
                .andExpect(jsonPath("$.restam").value(1));
    }

    @Test
    void classificaVariasDeUmaVezEONomeTemDeExistirPorInteiro() throws Exception {
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Reagente limitante"]}""").andExpect(status().isOk());
        var q1 = criarQuestao("Qual o reagente limitante?", "B", "MEDIA", "PUBLICADO");
        var q2 = criarQuestao("Calcule a massa molar do CO2", "A", "MEDIA", "PUBLICADO");

        // Pedaço de nome não vale num lote: viraria classificação errada em silêncio.
        comando("classificar_questoes", """
                {"classificacoes": [{"questao": %d, "assunto": "Estequio"}]}""".formatted(q1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ainda não existem assuntos 'Estequio'")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Estequiometria")));
        assertThat(contar("question_subjects")).isZero();

        comando("classificar_questoes", """
                {"classificacoes": [
                  {"questao": %d, "assunto": "estequiometria", "subassunto": "Reagente limitante"},
                  {"questao": %d, "assunto": "Estequiometria"}]}""".formatted(q1, q2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificadas").value(2))
                .andExpect(jsonPath("$.assuntos_criados.length()").value(0))
                .andExpect(jsonPath("$.restam_sem_assunto").value(0));

        comando("detalhar_questao", "{\"questao\": \"%d\"}".formatted(q1))
                .andExpect(jsonPath("$.classificacao[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.classificacao[0].subassunto").value("Reagente limitante"));
    }

    @Test
    void oAssuntoQueFaltaSoNasceComOSimDoProfessor() throws Exception {
        comando("cadastrar_assunto", "{\"nome\": \"Atomística\"}").andExpect(status().isOk());
        var q1 = criarQuestao("Quantas ligações sigma e pi?", "E", "MEDIA", "PUBLICADO");
        var q2 = criarQuestao("Distribuição eletrônica do zinco", "A", "MEDIA", "PUBLICADO");
        comando("editar_questao", "{\"questao\": \"%d\", \"assunto\": \"Atomística\"}".formatted(q1))
                .andExpect(status().isOk());
        var lote = """
                {"classificacoes": [
                  {"questao": %d, "assunto": "Ligações Químicas", "subassunto": "Ligações sigma e pi"},
                  {"questao": %d, "assunto": "Atomística", "subassunto": "Distribuição eletrônica"}]%s}""";

        comando("classificar_questoes", lote.formatted(q1, q2, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("assuntos 'Ligações Químicas'")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("'Atomística › Distribuição eletrônica'")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("criar_novos=true")));
        assertThat(contar("subjects WHERE nome = 'Ligações Químicas'")).isZero();
        assertThat(contar("question_subjects")).isEqualTo(1);

        comando("classificar_questoes", lote.formatted(q1, q2, ", \"criar_novos\": true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificadas").value(2))
                .andExpect(jsonPath("$.assuntos_criados[0]").value("Ligações Químicas"))
                .andExpect(jsonPath("$.subassuntos_criados.length()").value(2));

        // A que já tinha assunto troca de etiqueta, sem ficar com as duas.
        assertThat(contar("question_subjects WHERE questao_id = ?", q1)).isEqualTo(1);
        comando("detalhar_questao", "{\"questao\": \"%d\"}".formatted(q1))
                .andExpect(jsonPath("$.classificacao[0].assunto").value("Ligações Químicas"));
    }

    @Test
    void aMesmaQuestaoDuasVezesNoLoteEErro() throws Exception {
        comando("cadastrar_assunto", "{\"nome\": \"Atomística\"}").andExpect(status().isOk());
        var q = criarQuestao("Distribuição eletrônica do zinco", "A", "MEDIA", "PUBLICADO");

        comando("classificar_questoes", """
                {"classificacoes": [{"questao": %d, "assunto": "Atomística"}, {"questao": %d, "assunto": "Atomística"}]}"""
                .formatted(q, q))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("mais de uma vez")));
        assertThat(contar("question_subjects")).isZero();
    }
}
