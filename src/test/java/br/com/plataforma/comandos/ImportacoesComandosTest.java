package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/** O .docx que chega pelo link: o link, o que o parser entrega, a revisão e o completar. */
class ImportacoesComandosTest extends BaseDeComando {

    private static final String CINCO =
            """
            {"A": "1 mol", "B": "2 mol", "C": "3 mol", "D": "4 mol", "E": "5 mol"}""";

    private String token() throws Exception {
        var resposta = comando("importar_simulado_docx", """
                {"turmas": ["Extensivo 2027"], "titulo": "Simulado 01",
                 "abre_em": "2090-05-01T14:00", "fecha_em": "2090-05-01T18:00",
                 "duracao_minutos": 120}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importacao_id").isNumber())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.token");
    }

    /** Uma leitura com duas questões fechadas e uma que o parser não fechou. */
    private void entregarDocx(String token) throws Exception {
        interno("registrar_docx", """
                {"token": "%s",
                 "lido": {
                   "arquivo_nome": "simulado.docx",
                   "titulo": "Lido do documento",
                   "avisos": ["1 figura em formato antigo não convertida"],
                   "questoes": [
                     {"numero": 1, "avisos": [], "blocos": [1, 2],
                      "dados": {"enunciado": "Questão um", "alternativas": %s, "gabarito": "A"}},
                     {"numero": 3, "avisos": [], "blocos": [8, 9],
                      "dados": {"enunciado": "Questão três", "alternativas": %s, "gabarito": "B"}}],
                   "incompletas": [
                     {"numero": 2, "avisos": ["Não fechou: faltou o gabarito"], "blocos": [4, 5, 6]}],
                   "blocos": [
                     {"indice": 4, "texto": "2. Qual o reagente limitante?"},
                     {"indice": 5, "texto": "a) o primeiro"},
                     {"indice": 6, "texto": "b) o segundo"},
                     {"indice": 7, "texto": "c) o terceiro"},
                     {"indice": 8, "texto": "d) o quarto"},
                     {"indice": 9, "texto": "e) o quinto"}]}}""".formatted(token, CINCO, CINCO))
                .andExpect(status().isOk());
    }

    // --- o link --------------------------------------------------------------

    @Test
    void oLinkVemComPrazoEOTokenSoApareceUmaVez() throws Exception {
        var token = token();

        // O banco guarda só o hash: quem lê a tabela não usa o link.
        assertThat(jdbc.queryForObject("SELECT token_hash FROM imports", String.class))
                .hasSize(64)
                .isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT status FROM imports", String.class))
                .isEqualTo("AGUARDANDO");
    }

    @Test
    void turmaErradaAparecAntesDeOProfessorEnviar() throws Exception {
        comando("importar_simulado_docx", """
                {"turmas": ["Semi 2030"]}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Turma 'Semi 2030' não existe.")));

        assertThat(contar("imports")).isZero();
    }

    @Test
    void aPaginaDeEnvioPerguntaSeOLinkVale() throws Exception {
        var token = token();

        interno("situacao_do_link", """
                {"token": "%s"}""".formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(true))
                .andExpect(jsonPath("$.situacao").value("AGUARDANDO"))
                // minúsculo: é o que a página de envio compara
                .andExpect(jsonPath("$.formato").value("docx"))
                // e os campos que ela mostra ao professor antes de aceitar o arquivo
                .andExpect(jsonPath("$.titulo").value("Simulado 01"))
                .andExpect(jsonPath("$.turmas[0]").value("Extensivo 2027"))
                .andExpect(jsonPath("$.pedido_por").value("Professora Ana"))
                .andExpect(jsonPath("$.expira_em").isNotEmpty());

        interno("situacao_do_link", """
                {"token": "inventado"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Este link de envio não existe."));
    }

    // --- o que o parser entrega ----------------------------------------------

    @Test
    void oDocxLidoViraRascunhoDeSimulado() throws Exception {
        var token = token();
        entregarDocx(token);

        // O título dos parâmetros ganha do que veio no documento.
        assertThat(jdbc.queryForObject("SELECT titulo FROM exams", String.class))
                .isEqualTo("Simulado 01");
        assertThat(contar("exams WHERE status = 'RASCUNHO'")).isEqualTo(1);
        assertThat(contar("questions WHERE status = 'RASCUNHO'")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM imports", String.class))
                .isEqualTo("PROCESSADA");
    }

    @Test
    void oMesmoLinkNaoServeDuasVezes() throws Exception {
        var token = token();
        entregarDocx(token);

        interno("registrar_docx", """
                {"token": "%s", "lido": {"arquivo_nome": "de novo.docx", "questoes": [
                   {"numero": 1, "dados": {"enunciado": "Outra", "alternativas": %s, "gabarito": "A"}}]}}"""
                .formatted(token, CINCO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Este link já foi usado."));
    }

    @Test
    void semQuestaoFechadaOArquivoERecusado() throws Exception {
        var token = token();

        interno("registrar_docx", """
                {"token": "%s", "lido": {"arquivo_nome": "solto.docx", "questoes": []}}"""
                .formatted(token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Não reconheci nenhuma questão completa neste arquivo")));
    }

    // --- revisar -------------------------------------------------------------

    @Test
    void aRevisaoMostraOLidoEOQueFaltou() throws Exception {
        entregarDocx(token());

        comando("revisar_importacao", """
                {"importacao": 1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arquivo").value("simulado.docx"))
                .andExpect(jsonPath("$.total_questoes").value(2))
                .andExpect(jsonPath("$.mostrando").value("1 a 2"))
                .andExpect(jsonPath("$.avisos_gerais[0]")
                        .value("1 figura em formato antigo não convertida"))
                .andExpect(jsonPath("$.questoes[0].numero_no_documento").value(1))
                .andExpect(jsonPath("$.questoes[0].enunciado").value("Questão um"))
                .andExpect(jsonPath("$.questoes[0].gabarito").value("A"))
                // a incompleta vem com os blocos do documento, para o Claude montar
                .andExpect(jsonPath("$.incompletas[0].numero").value(2))
                .andExpect(jsonPath("$.incompletas[0].blocos[0].texto")
                        .value("2. Qual o reagente limitante?"))
                .andExpect(jsonPath("$.incompletas[0].blocos[1].texto").value("a) o primeiro"));
    }

    @Test
    void importacaoQueNaoChegouDizIsso() throws Exception {
        token();

        comando("revisar_importacao", """
                {"importacao": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "O arquivo da importação 1 ainda não chegou")));
    }

    // --- completar ------------------------------------------------------------

    /** O texto sai do documento, não de uma redigitação — e o rótulo do bloco sai fora. */
    @Test
    void completaAQuestaoAPartirDosBlocosENaPosicaoDoNumero() throws Exception {
        entregarDocx(token());

        comando("completar_questao_importada", """
                {"importacao": 1, "numero": 2, "enunciado": "4",
                 "alternativas": "5-9", "gabarito": "c"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value(2))
                .andExpect(jsonPath("$.ordem").value(2))
                .andExpect(jsonPath("$.total_questoes").value(3));

        // O "2." do começo do bloco é rótulo do documento, não enunciado.
        assertThat(jdbc.queryForObject(
                "SELECT enunciado FROM questions WHERE enunciado LIKE '%reagente%'", String.class))
                .isEqualTo("Qual o reagente limitante?");
        // E o "a)" some de cada alternativa.
        assertThat(jdbc.queryForObject("""
                SELECT texto FROM question_options o JOIN questions q ON q.id = o.questao_id
                 WHERE q.enunciado LIKE '%reagente%' AND o.letra = 'A'""", String.class))
                .isEqualTo("o primeiro");
    }

    @Test
    void completarDuasVezesERecusado() throws Exception {
        entregarDocx(token());
        var corpo = """
                {"importacao": 1, "numero": 2, "enunciado": "4",
                 "alternativas": "5-9", "gabarito": "c"}""";
        comando("completar_questao_importada", corpo).andExpect(status().isOk());

        comando("completar_questao_importada", corpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "A questão 2 já está no rascunho; ajuste com editar_questao."));
    }

    @Test
    void faixaDeAlternativasComTamanhoErradoERecusada() throws Exception {
        entregarDocx(token());

        comando("completar_questao_importada", """
                {"importacao": 1, "numero": 2, "enunciado": "4",
                 "alternativas": "5-7", "gabarito": "c"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Uma faixa só de alternativas precisa ter exatamente cinco blocos, de A a E."));
    }

    @Test
    void blocoQueNaoExisteDizQualE() throws Exception {
        entregarDocx(token());

        comando("completar_questao_importada", """
                {"importacao": 1, "numero": 2, "enunciado": "99",
                 "alternativas": "5-9", "gabarito": "c"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Blocos [99] não existem neste documento."));
    }

    @Test
    void numeroQueNaoFoiLidoListaAsIncompletas() throws Exception {
        entregarDocx(token());

        comando("completar_questao_importada", """
                {"importacao": 1, "numero": 9, "enunciado": "4",
                 "alternativas": "5-9", "gabarito": "c"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "A questão 9 não foi lida neste documento. Incompletas: [2]."));
    }

    // --- as figuras do .docx --------------------------------------------------

    /**
     * O parser não tem id para dar: ele nomeia as figuras f1, f2… e é aqui que elas viram id.
     *
     * <p>f2 vai sem conteúdo de propósito — é o EMF que o LibreOffice não converteu. A marca dela
     * vira pendente e a importação segue, que é o combinado.
     */
    @Test
    void asChavesDoParserViramIdsEAQueFaltouViraPendente() throws Exception {
        var png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

        interno("registrar_docx", """
                {"token": "%s",
                 "lido": {
                   "arquivo_nome": "simulado.docx",
                   "figuras": [{"chave": "f1", "nome": "image1.png", "tipo": "image/png",
                                "conteudo_base64": "%s"}],
                   "questoes": [
                     {"numero": 1, "dados": {"enunciado": "Veja ![](figura:f1) e ![](figura:f2)",
                      "alternativas": %s, "gabarito": "A"}}],
                   "blocos": [{"indice": 1, "texto": "Veja ![](figura:f1)"}]}}"""
                .formatted(token(), png, CINCO))
                .andExpect(status().isOk());

        var figura = jdbc.queryForObject("SELECT id FROM images", Integer.class);
        assertThat(jdbc.queryForObject("SELECT enunciado FROM questions", String.class))
                .isEqualTo("Veja ![](figura:%d) e ![](figura:pendente)".formatted(figura));
        // A figura fica ligada à questão e à parte, lidas do próprio texto.
        assertThat(jdbc.queryForObject("SELECT parte FROM images WHERE id = ?", String.class, figura))
                .isEqualTo("ENUNCIADO");
        // Marca pendente sobrando é o que faz a questão nascer com imagem pendente.
        assertThat(jdbc.queryForObject("SELECT imagem_pendente FROM questions", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT blocos::text FROM imports", String.class))
                .contains("figura:%d".formatted(figura));
    }

    // --- figuras --------------------------------------------------------------

    /** O adaptador recorta com Pillow; guardar e devolver os bytes é daqui. */
    @Test
    void aFiguraVaiEVoltaEmBase64() throws Exception {
        var png = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});

        var resposta = comando("guardar_figura", """
                {"conteudo_base64": "%s", "tipo": "image/png", "nome": "recorte.png"}""".formatted(png))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figura_id").isNumber())
                .andReturn().getResponse().getContentAsString();
        int figura = JsonPath.read(resposta, "$.figura_id");

        comando("bytes_da_figura", """
                {"figura": %d}""".formatted(figura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo_base64").value(png))
                .andExpect(jsonPath("$.tipo").value("image/png"));
    }

    // --- prints ---------------------------------------------------------------

    @Test
    void osPrintsChegamNaOrdemEOAdaptadorPegaOsIds() throws Exception {
        var resposta = comando("importar_prints", "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resposta, "$.token");
        var um = java.util.Base64.getEncoder().encodeToString(new byte[] {1});
        var dois = java.util.Base64.getEncoder().encodeToString(new byte[] {2});

        interno("registrar_prints", """
                {"token": "%s", "arquivos": [
                  {"nome": "q01.png", "tipo": "image/png", "conteudo_base64": "%s"},
                  {"nome": "q02.png", "tipo": "image/png", "conteudo_base64": "%s"}]}"""
                .formatted(token, um, dois))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prints").value(2));

        comando("prints_da_importacao", """
                {"importacao": 1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_prints").value(2))
                .andExpect(jsonPath("$.figuras.length()").value(2));
    }

    @Test
    void pedirPrintsDeUmDocxDizQueEOutroCaminho() throws Exception {
        entregarDocx(token());

        comando("prints_da_importacao", """
                {"importacao": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "A importação 1 é de um .docx: revise com revisar_importacao."));
    }

    @Test
    void figuraQueNaoExisteDizIsso() throws Exception {
        comando("bytes_da_figura", """
                {"figura": 404}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Figura 404 não existe."));
    }
}
