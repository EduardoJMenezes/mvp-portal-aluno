package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/**
 * A regra que sustenta a POC: a IA propõe, o humano aprova, o backend publica.
 *
 * <p>Se um teste daqui começar a falhar, o problema é a mudança, não o teste.
 */
class RascunhosComandosTest extends BaseDeComando {

    private static final String CINCO_ALTERNATIVAS =
            """
            {"A": "1 mol", "B": "2 mol", "C": "3 mol", "D": "4 mol", "E": "5 mol"}""";

    // --- criar_questao_rascunho ----------------------------------------------

    @Test
    void propoeUmaQuestaoEOResumoDizOQueEla() throws Exception {
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria"}""").andExpect(status().isOk());

        comando("criar_questao_rascunho", """
                {"enunciado": "Quantos mols de CO2?", "alternativas": %s, "gabarito": "b",
                 "assunto": "Estequiometria", "dificuldade": "facil"}""".formatted(CINCO_ALTERNATIVAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("QUESTOES"))
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.origem").value("MCP"))
                .andExpect(jsonPath("$.resumo")
                        .value("1 questão de simulado — Estequiometria: Quantos mols de CO2?"))
                .andExpect(jsonPath("$.questoes[0].gabarito").value("B"))
                .andExpect(jsonPath("$.questoes[0].completa").value(true))
                .andExpect(jsonPath("$.questoes[0].dificuldade").value("FACIL"))
                .andExpect(jsonPath("$.questoes[0].classificacao[0].assunto").value("Estequiometria"))
                .andExpect(jsonPath("$.aprovado_por").doesNotExist());

        // Nasce em rascunho: não aparece para aluno nenhum.
        assertThat(contar("questions WHERE status = 'RASCUNHO'")).isEqualTo(1);
    }

    /** A marca no texto já basta: ninguém precisa lembrar de avisar que falta figura. */
    @Test
    void aMarcaDeFiguraPendenteNoTextoMarcaAQuestao() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Veja o gráfico ![](figura:pendente) e responda",
                 "alternativas": %s, "gabarito": "A"}""".formatted(CINCO_ALTERNATIVAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questoes[0].imagem_pendente").value(true));
    }

    @Test
    void faltandoUmaLetraERecusado() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Sem a letra E", "alternativas": {"A": "1", "B": "2", "C": "3", "D": "4"},
                 "gabarito": "A"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Faltam as alternativas E. A questão precisa de A a E."));
    }

    // --- criar_simulado_rascunho ---------------------------------------------

    /** Um simulado de 15 questões é um preview e um ok, não dezesseis rascunhos. */
    @Test
    void montaProvaMisturandoQuestaoDoAcervoEQuestaoNova() throws Exception {
        var doAcervo = criarQuestao("Questão já publicada", "C", "MEDIA", "PUBLICADO");

        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 01",
                 "questoes": [%d, {"enunciado": "Questão nova", "alternativas": %s, "gabarito": "D"}],
                 "abre_em": "2090-01-10T14:00", "fecha_em": "2090-01-10T18:00",
                 "duracao_minutos": 120}""".formatted(doAcervo, CINCO_ALTERNATIVAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("SIMULADO"))
                .andExpect(jsonPath("$.resumo")
                        .value("Simulado 'Simulado 01' com 2 questões (1 novas) — Extensivo 2027"))
                .andExpect(jsonPath("$.simulado.titulo").value("Simulado 01"))
                .andExpect(jsonPath("$.simulado.turmas[0]").value("Extensivo 2027"))
                .andExpect(jsonPath("$.simulado.questoes[0].nova").value(false))
                .andExpect(jsonPath("$.simulado.questoes[1].nova").value(true))
                .andExpect(jsonPath("$.simulado.pendencias_para_publicar").isEmpty());

        assertThat(contar("exams WHERE status = 'RASCUNHO'")).isEqualTo(1);
    }

    /** Numa prova de 15, o erro sem a posição não diz qual corrigir. */
    @Test
    void oErroDizQualQuestaoDaProvaEstaErrada() throws Exception {
        var q = criarQuestao("Questão publicada", "A", "MEDIA", "PUBLICADO");

        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 02", "questoes": [%d, %d]}"""
                .formatted(q, q))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Questão 2 da prova: a questão %d já entrou antes nesta prova.".formatted(q)));
    }

    @Test
    void questaoEmRascunhoNaoEntraNaProva() throws Exception {
        var rascunhada = criarQuestao("Ainda não revisada", "A", "MEDIA", "RASCUNHO");

        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 03", "questoes": [%d]}"""
                .formatted(rascunhada))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Questão 1 da prova: Questão '%d' não está no acervo publicado.".formatted(rascunhada))));
    }

    // --- publicar_rascunho ---------------------------------------------------

    /** O coração da regra: sem aprovação humana gravada, não publica. */
    @Test
    void semAprovacaoHumanaNaoPublicaEOErroPedeConfirmacao() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Proposta sem revisão", "alternativas": %s, "gabarito": "A"}"""
                .formatted(CINCO_ALTERNATIVAS)).andExpect(status().isOk());

        comando("publicar_rascunho", """
                {"rascunho": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:plataforma:aprovacao-necessaria"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "O rascunho 1 não tem aprovação humana registrada")));

        // E, o que mais importa: nada chegou ao aluno.
        assertThat(contar("questions WHERE status = 'PUBLICADO'")).isZero();
        assertThat(contar("drafts WHERE status = 'PUBLICADO'")).isZero();
    }

    @Test
    void comAConfirmacaoDoProfessorPublica() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Proposta revisada", "alternativas": %s, "gabarito": "A"}"""
                .formatted(CINCO_ALTERNATIVAS)).andExpect(status().isOk());

        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicado").value(true))
                .andExpect(jsonPath("$.questoes_publicadas").value(1))
                .andExpect(jsonPath("$.aprovado_por").value("Professora Ana"))
                .andExpect(jsonPath("$.aprovado_via").value("ELICITATION_MCP"));

        assertThat(contar("questions WHERE status = 'PUBLICADO'")).isEqualTo(1);
        // A aprovação fica gravada, com nome e hora — é estado, não checagem no cliente.
        assertThat(jdbc.queryForObject(
                "SELECT aprovado_por_id FROM drafts WHERE id = 1", Integer.class)).isEqualTo(ADMIN);
    }

    @Test
    void publicarDeNovoERecusado() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Proposta", "alternativas": %s, "gabarito": "A"}"""
                .formatted(CINCO_ALTERNATIVAS)).andExpect(status().isOk());
        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""").andExpect(status().isOk());

        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Rascunho 1 já foi publicado por inteiro."));
    }

    /** Prova sem agenda não vai ao ar — e a recusa acontece antes de mudar qualquer status. */
    @Test
    void simuladoComPendenciaNaoPublica() throws Exception {
        var q = criarQuestao("Questão publicada", "A", "MEDIA", "PUBLICADO");
        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Sem agenda", "questoes": [%d]}""".formatted(q))
                .andExpect(status().isOk());

        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "'Sem agenda' ainda não pode ser publicado: abertura e fechamento não definidos; "
                                + "tempo de prova não definido")));

        assertThat(contar("exams WHERE status = 'PUBLICADO'")).isZero();
    }

    @Test
    void publicaOSimuladoInteiroQuandoEstaPronto() throws Exception {
        var q = criarQuestao("Questão publicada", "A", "MEDIA", "PUBLICADO");
        comando("criar_simulado_rascunho", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 04",
                 "questoes": [%d, {"enunciado": "Nova", "alternativas": %s, "gabarito": "E"}],
                 "abre_em": "2090-02-01T14:00", "fecha_em": "2090-02-01T18:00",
                 "duracao_minutos": 90}""".formatted(q, CINCO_ALTERNATIVAS))
                .andExpect(status().isOk());

        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado_publicado").value("Simulado 04"))
                .andExpect(jsonPath("$.questoes_publicadas").value(1))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.startsWith(
                        "Publicado. 'Simulado 04' abre em 01/02/2090 às 14:00")));

        assertThat(contar("exams WHERE status = 'PUBLICADO'")).isEqualTo(1);
    }

    @Test
    void rascunhoQueNaoExisteDizIsso() throws Exception {
        comando("detalhar_rascunho", """
                {"rascunho": 77}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Rascunho 77 não existe."));
    }

    @Test
    void listarFiltraPorStatus() throws Exception {
        comando("criar_questao_rascunho", """
                {"enunciado": "Uma", "alternativas": %s, "gabarito": "A"}"""
                .formatted(CINCO_ALTERNATIVAS)).andExpect(status().isOk());
        comando("criar_questao_rascunho", """
                {"enunciado": "Outra", "alternativas": %s, "gabarito": "A"}"""
                .formatted(CINCO_ALTERNATIVAS)).andExpect(status().isOk());
        comando("publicar_rascunho", """
                {"rascunho": 1, "confirmado_pelo_professor": true}""").andExpect(status().isOk());

        comando("listar_rascunhos", "{}").andExpect(jsonPath("$.length()").value(2));
        comando("listar_rascunhos", """
                {"status": "publicado"}""")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rascunho_id").value(1));
    }

    /**
     * O rascunho sobrevive ao sub-módulo que ele aponta.
     *
     * <p>Com remoção lógica, {@code @SQLRestriction} esconde a linha e o proxy do Hibernate
     * estoura ao ser tocado — e aí a lista inteira cai por causa de um rascunho só. Achado numa
     * varredura das rotas do portal, não aqui: por isso o teste existe.
     */
    @Test
    void rascunhoDeSubModuloRemovidoAindaAparecaNaLista() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "999", "titulo": "Aula solta"}]}""")
                .andExpect(status().isOk());

        comando("remover_do_curso", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas"}""")
                .andExpect(status().isOk());

        comando("listar_rascunhos", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // o endereço some junto com o sub-módulo; o rascunho, não
                .andExpect(jsonPath("$[0].submodulo").doesNotExist());
    }

    /** E o mesmo vale para a turma: removê-la não pode derrubar a lista. */
    @Test
    void rascunhoDeTurmaRemovidaAindaAparecaNaLista() throws Exception {
        comando("criar_modulo", """
                {"turma": "Intensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        comando("importar_videos_como_itens", """
                {"turma": "Intensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "998", "titulo": "Aula solta"}]}""")
                .andExpect(status().isOk());
        jdbc.update("UPDATE classes SET removido_em = now() WHERE nome = 'Intensivo 2027'");

        comando("listar_rascunhos", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].turma").doesNotExist());
    }
}
