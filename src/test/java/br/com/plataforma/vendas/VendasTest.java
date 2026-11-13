package br.com.plataforma.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.portal.BaseDoPortal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Do link divulgado até o fim do acesso: o aluno compra sem conta, e a matrícula segue o pagamento. */
class VendasTest extends BaseDoPortal {

    private static final String CPF = "52998224725";

    @Autowired Asaas asaas;
    @Autowired VendasServico vendas;

    private Asaas.DeMentira asaasDeMentira() {
        return (Asaas.DeMentira) asaas;
    }

    @BeforeEach
    void preparar() {
        comSenhas();
        asaasDeMentira().criados.clear();
    }

    private void planoMensal() throws Exception {
        post("/api/admin/vendas/planos", """
                {"nome": "Extensivo 2027 — mensal", "link": "extensivo-2027", "tipo": "MENSAL",
                 "preco_centavos": 19700, "turmas": ["Extensivo 2027"]}""", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value("extensivo-2027"))
                .andExpect(jsonPath("$.turmas[0]").value("Extensivo 2027"));
    }

    private ResultActions comprar(String link, String nome, String email, String cpf) throws Exception {
        return comprar(link, nome, email, cpf, null);
    }

    private ResultActions comprar(String link, String nome, String email, String cpf, String forma) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/vendas/planos/" + link + "/comprar")
                .contentType(APPLICATION_JSON)
                .content("""
                        {"nome": "%s", "email": "%s", "cpf": "%s", "celular": "(81) 99999-0000",
                         "cep": "53030-260", "numero": "100", "complemento": "apto 302",
                         "forma": %s, "aceite_termos": true}"""
                        .formatted(nome, email, cpf, forma == null ? "null" : "\"" + forma + "\"")));
    }

    /** O token do pedido é o que volta na URL de sucesso do checkout. */
    private String tokenDoUltimoCheckout() {
        var url = asaasDeMentira().criados.getLast().urlDeSucesso();
        return url.substring(url.indexOf("pedido=") + "pedido=".length());
    }

    private ResultActions avisar(String corpo) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/asaas/webhook").contentType(APPLICATION_JSON)
                .header("asaas-access-token", TOKEN_DO_ASAAS).content(corpo));
    }

    private ResultActions situacao(String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/vendas/pedidos/" + token));
    }

    private int matriculasDoPedido() {
        return jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE pedido_id IS NOT NULL", Integer.class);
    }

    private static final String PAGO = """
            {"event": "CHECKOUT_PAID", "checkout": {"id": "chk-de-mentira-1", "customer": "cus_1",
             "subscription": {"id": "sub_1"}}}""";

    // --- o plano -------------------------------------------------------------

    @Test
    void oProfessorMontaOPlanoEOLinkEUnico() throws Exception {
        planoMensal();
        post("/api/admin/vendas/planos", """
                {"nome": "Outro", "link": "extensivo-2027", "tipo": "UNICO", "preco_centavos": 99700,
                 "turmas": ["Intensivo 2027"]}""", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Já existe um plano com o link 'extensivo-2027'. Escolha outro."));
        post("/api/admin/vendas/planos", """
                {"nome": "Sem turma", "link": "sem-turma", "tipo": "MENSAL", "preco_centavos": 1000, "turmas": []}""", ADMIN)
                .andExpect(status().isBadRequest());
        get("/api/admin/vendas/planos", ALUNO).andExpect(status().isForbidden());

        // A página pública, sem conta; desligado, o link sai do ar.
        mvc.perform(MockMvcRequestBuilders.get("/api/vendas/planos/extensivo-2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preco_centavos").value(19700))
                .andExpect(jsonPath("$.turmas[0].nome").value("Extensivo 2027"));
        var id = jdbc.queryForObject("SELECT id FROM planos", Integer.class);
        patch("/api/admin/vendas/planos/" + id, "{\"ativo\": false}", ADMIN).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/api/vendas/planos/extensivo-2027"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Este plano não está à venda."));
    }

    /** O link divulgado leva à página de assinar; "pronto" é a página de volta, não um plano. */
    @Test
    void oLinkDivulgadoLevaAPaginaDeAssinar() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/assinar/extensivo-2027"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", "/assinar/?plano=extensivo-2027"));
        mvc.perform(MockMvcRequestBuilders.get("/assinar/pronto"))
                .andExpect(status().is(org.hamcrest.Matchers.not(302)));
    }

    // --- a compra ------------------------------------------------------------

    @Test
    void compraSemContaPagaCriaASenhaEEntraNaTurma() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "Carla@Exemplo.com", "529.982.247-25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkout").value("https://sandbox.asaas.com/checkoutSession/show/chk-de-mentira-1"));
        var enviado = asaasDeMentira().criados.getLast();
        assertThat(enviado.forma()).isEqualTo(Asaas.Forma.MENSAL);
        assertThat(enviado.valorCentavos()).isEqualTo(19700);
        assertThat(enviado.mensalidades()).as("sem número, cobra até o aluno cancelar").isNull();
        assertThat(enviado.comprador().cpf()).isEqualTo(CPF);
        assertThat(enviado.urlDeVolta()).endsWith("/assinar/extensivo-2027");
        var token = tokenDoUltimoCheckout();

        // Antes do aviso, nada: nem conta, nem matrícula.
        situacao(token).andExpect(jsonPath("$.status").value("AGUARDANDO"))
                .andExpect(jsonPath("$.pode_criar_senha").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'carla@exemplo.com'", Integer.class)).isZero();

        avisar(PAGO).andExpect(status().isOk());
        avisar(PAGO).andExpect(status().isOk()); // o Asaas pode repetir
        assertThat(matriculasDoPedido()).isEqualTo(1);
        situacao(token).andExpect(jsonPath("$.status").value("PAGO"))
                .andExpect(jsonPath("$.pode_criar_senha").value(true));

        mvc.perform(MockMvcRequestBuilders.post("/api/vendas/pedidos/" + token + "/senha").contentType(APPLICATION_JSON)
                        .content("{\"senha\": \"minha-senha-nova-7\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("sessao"));
        login("carla@exemplo.com", "minha-senha-nova-7").andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.turmas[0]").value("Extensivo 2027"));
        // O token não serve de novo: senha definida é senha do aluno.
        mvc.perform(MockMvcRequestBuilders.post("/api/vendas/pedidos/" + token + "/senha").contentType(APPLICATION_JSON)
                        .content("{\"senha\": \"outra-senha-qualquer-9\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dadosRuinsNaoChegamAoAsaas() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", "111.111.111-11")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("CPF inválido."));
        comprar("extensivo-2027", "Carla", "carla@exemplo.com", CPF)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Informe o nome completo."));
        assertThat(asaasDeMentira().criados).isEmpty();
    }

    /** Comprar com o e-mail de alguém paga a turma dele — e não dá a senha da conta dele. */
    @Test
    void contaQueJaExisteGanhaATurmaMasNaoASenha() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Aluno Bruno", "bruno@teste.invalid", CPF).andExpect(status().isOk());
        var token = tokenDoUltimoCheckout();
        avisar(PAGO).andExpect(status().isOk());

        situacao(token).andExpect(jsonPath("$.pode_criar_senha").value(false))
                .andExpect(jsonPath("$.ja_tinha_conta").value(true));
        mvc.perform(MockMvcRequestBuilders.post("/api/vendas/pedidos/" + token + "/senha").contentType(APPLICATION_JSON)
                        .content("{\"senha\": \"tomei-a-conta-123\"}"))
                .andExpect(status().isBadRequest());

        // Ele já estava na turma à mão: o reembolso não tira essa matrícula.
        avisar("""
                {"event": "PAYMENT_REFUNDED", "payment": {"subscription": "sub_1", "customer": "cus_1"}}""");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE usuario_id = ?", Integer.class, ALUNO))
                .isEqualTo(1);
    }

    @Test
    void emailDaEquipeNaoViraAluno() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Professora Ana", "ana@teste.invalid", CPF).andExpect(status().isOk());
        avisar(PAGO).andExpect(status().isOk());
        assertThat(matriculasDoPedido()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM pedidos", String.class)).isEqualTo("AGUARDANDO");
    }

    @Test
    void semOTokenDoWebhookNinguemDizQuePagou() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", CPF);
        mvc.perform(MockMvcRequestBuilders.post("/api/asaas/webhook").contentType(APPLICATION_JSON).content(PAGO))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/asaas/webhook").contentType(APPLICATION_JSON)
                        .header("asaas-access-token", "chute").content(PAGO))
                .andExpect(status().isUnauthorized());
        assertThat(matriculasDoPedido()).isZero();
    }

    // --- o fim do acesso -----------------------------------------------------

    @Test
    void atrasoDaCincoDiasEPagarDevolveOAcesso() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", CPF);
        avisar(PAGO);

        avisar("""
                {"event": "PAYMENT_OVERDUE", "payment": {"subscription": "sub_1", "customer": "cus_1"}}""");
        assertThat(jdbc.queryForObject("SELECT status FROM pedidos", String.class)).isEqualTo("ATRASADO");
        vendas.revogarVencidos(Instant.now().plus(4, ChronoUnit.DAYS));
        assertThat(matriculasDoPedido()).isEqualTo(1);
        vendas.revogarVencidos(Instant.now().plus(6, ChronoUnit.DAYS));
        assertThat(matriculasDoPedido()).isZero();

        avisar("""
                {"event": "PAYMENT_RECEIVED", "payment": {"subscription": "sub_1", "customer": "cus_1"}}""");
        assertThat(matriculasDoPedido()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pedidos", String.class)).isEqualTo("PAGO");
    }

    @Test
    void cancelouValeAteOFimDoMesPago() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", CPF);
        avisar(PAGO);
        var proxima = java.time.LocalDate.now(VendasServico.BRASILIA).plusDays(20);
        avisar("""
                {"event": "SUBSCRIPTION_DELETED", "subscription": {"id": "sub_1", "nextDueDate": "%s"}}""".formatted(proxima));

        assertThat(jdbc.queryForObject("SELECT status FROM pedidos", String.class)).isEqualTo("CANCELADO");
        vendas.revogarVencidos(Instant.now().plus(19, ChronoUnit.DAYS));
        assertThat(matriculasDoPedido()).isEqualTo(1);
        vendas.revogarVencidos(Instant.now().plus(21, ChronoUnit.DAYS));
        assertThat(matriculasDoPedido()).isZero();
    }

    /** Se o aviso do checkout não trouxe a assinatura, a primeira mensalidade a amarra. */
    @Test
    void assinaturaSemIdNoCheckoutSeAmarraPeloCliente() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", CPF);
        avisar("""
                {"event": "CHECKOUT_PAID", "checkout": {"id": "chk-de-mentira-1", "customer": "cus_9"}}""");
        avisar("""
                {"event": "PAYMENT_CONFIRMED", "payment": {"subscription": "sub_9", "customer": "cus_9"}}""");
        assertThat(jdbc.queryForObject("SELECT asaas_assinatura_id FROM pedidos", String.class)).isEqualTo("sub_9");
        avisar("""
                {"event": "PAYMENT_REFUNDED", "payment": {"subscription": "sub_9", "customer": "cus_9"}}""");
        assertThat(matriculasDoPedido()).isZero();
    }

    @Test
    void assinaturaDoAnoParaNaDecimaSegundaMensalidade() throws Exception {
        post("/api/admin/vendas/planos", """
                {"nome": "Extensivo 2027 — 12x", "link": "extensivo-2027-12x", "tipo": "MENSAL",
                 "preco_centavos": 19700, "mensalidades": 12, "turmas": ["Extensivo 2027"]}""", ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensalidades").value(12));
        mvc.perform(MockMvcRequestBuilders.get("/api/vendas/planos/extensivo-2027-12x"))
                .andExpect(jsonPath("$.mensalidades").value(12));
        comprar("extensivo-2027-12x", "Carla Souza", "carla@exemplo.com", CPF).andExpect(status().isOk());
        assertThat(asaasDeMentira().criados.getLast().mensalidades()).isEqualTo(12);

        post("/api/admin/vendas/planos", """
                {"nome": "Mensal demais", "link": "mensal-demais", "tipo": "MENSAL",
                 "preco_centavos": 19700, "mensalidades": 30, "turmas": ["Extensivo 2027"]}""", ADMIN)
                .andExpect(status().is4xxClientError());
    }

    @Test
    void dataLimiteDaAssinaturaPegaAUltimaMensalidadeENaoAProxima() {
        var primeira = java.time.LocalDate.of(2027, 1, 31);
        var limite = Asaas.ultimoVencimentoAte(primeira, 12);
        // 12ª vence em 31/12/2027; a 13ª seria 31/01/2028.
        assertThat(limite).isAfterOrEqualTo(java.time.LocalDate.of(2027, 12, 31))
                .isBefore(java.time.LocalDate.of(2028, 1, 28));
        assertThat(Asaas.ultimoVencimentoAte(java.time.LocalDate.of(2026, 11, 9), 12))
                .isAfterOrEqualTo(java.time.LocalDate.of(2027, 10, 9)).isBefore(java.time.LocalDate.of(2027, 11, 9));
    }

    // --- o curso com matrícula e tabela de preços ------------------------------

    /** O Extensivo como na página de vendas: matrícula à parte e o preço que muda com a data. */
    private void planoDoCurso() throws Exception {
        var depois = java.time.LocalDate.now(VendasServico.BRASILIA).plusDays(30);
        post("/api/admin/vendas/planos", """
                {"nome": "Extensivo 2027", "link": "extensivo-2027-curso", "tipo": "UNICO",
                 "turmas": ["Extensivo 2027", "Intensivo 2027"], "matricula_centavos": 33000,
                 "matricula_parcelas_max": 3, "a_venda_ate": "%s",
                 "precos": [
                   {"a_partir_de": "%s", "pix_centavos": 124000, "cartao_centavos": 139920, "cartao_parcelas": 8},
                   {"a_partir_de": null, "pix_centavos": 170000, "cartao_centavos": 191880, "cartao_parcelas": 12}]}"""
                .formatted(depois.plusDays(60), depois), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precos.length()").value(2))
                .andExpect(jsonPath("$.precos[0].a_partir_de").doesNotExist())
                .andExpect(jsonPath("$.matricula_centavos").value(33000));
    }

    @Test
    void oAlunoVeOPrecoDoDiaEAMatricula() throws Exception {
        planoDoCurso();
        var depois = java.time.LocalDate.now(VendasServico.BRASILIA).plusDays(30);
        mvc.perform(MockMvcRequestBuilders.get("/api/vendas/planos/extensivo-2027-curso"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pix_centavos").value(170000))
                .andExpect(jsonPath("$.cartao_centavos").value(191880))
                .andExpect(jsonPath("$.cartao_parcelas").value(12))
                .andExpect(jsonPath("$.preco_vale_ate").value(depois.minusDays(1).toString()))
                .andExpect(jsonPath("$.matricula_centavos").value(33000))
                .andExpect(jsonPath("$.matricula_parcelas_max").value(3))
                .andExpect(jsonPath("$.termos_versao").value(VendasServico.TERMOS_VERSAO));

        // Sem aceitar os termos, não há compra.
        mvc.perform(MockMvcRequestBuilders.post("/api/vendas/planos/extensivo-2027-curso/comprar")
                        .contentType(APPLICATION_JSON).content("""
                                {"nome": "Carla Souza", "email": "carla@exemplo.com", "cpf": "%s", "cep": "53030260",
                                 "numero": "100", "forma": "PIX"}""".formatted(CPF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Para seguir, leia e aceite os termos de matrícula."));
        assertThat(asaasDeMentira().criados).isEmpty();

        // Passou o "à venda até": as matrículas encerram.
        var id = jdbc.queryForObject("SELECT id FROM planos", Integer.class);
        patch("/api/admin/vendas/planos/" + id, "{\"a_venda_ate\": \"%s\"}"
                .formatted(java.time.LocalDate.now(VendasServico.BRASILIA).minusDays(1)), ADMIN).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/api/vendas/planos/extensivo-2027-curso"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("As matrículas deste plano estão encerradas."));
        patch("/api/admin/vendas/planos/" + id, "{\"a_venda_ate\": \"\"}", ADMIN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.a_venda_ate").doesNotExist());
        // A tabela continua lá depois de editar outra coisa.
        get("/api/admin/vendas/planos", ADMIN).andExpect(jsonPath("$[0].precos.length()").value(2));
    }

    @Test
    void tabelaDePrecosPrecisaDaLinhaDesdeJa() throws Exception {
        post("/api/admin/vendas/planos", """
                {"nome": "Sem desde já", "link": "sem-desde-ja", "tipo": "UNICO", "turmas": ["Extensivo 2027"],
                 "precos": [{"a_partir_de": "2027-04-01", "pix_centavos": 124000}]}""", ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A primeira linha de preço vale desde já: deixe a data dela vazia."));
        post("/api/admin/vendas/planos", """
                {"nome": "Linha vazia", "link": "linha-vazia", "tipo": "UNICO", "turmas": ["Extensivo 2027"],
                 "precos": [{"a_partir_de": null}]}""", ADMIN)
                .andExpect(status().isBadRequest());
    }

    @Test
    void noPixMatriculaECursoVaoNumPagamentoSo() throws Exception {
        planoDoCurso();
        comprar("extensivo-2027-curso", "Carla Souza", "carla@exemplo.com", CPF, "PIX").andExpect(status().isOk());
        var enviado = asaasDeMentira().criados.getLast();
        assertThat(enviado.forma()).isEqualTo(Asaas.Forma.PIX);
        assertThat(enviado.itens()).extracting(Asaas.Item::valorCentavos).containsExactly(33000, 170000);
        assertThat(enviado.comprador().complemento()).isEqualTo("apto 302");

        avisar(PAGO).andExpect(status().isOk());
        assertThat(matriculasDoPedido()).isEqualTo(2);
        var pedido = jdbc.queryForMap("SELECT * FROM pedidos");
        assertThat(pedido.get("valor_centavos")).isEqualTo(203000);
        assertThat(pedido.get("forma")).isEqualTo("PIX");
        assertThat(pedido.get("matricula_paga_em")).isNotNull();
        assertThat(pedido.get("termos_versao")).isEqualTo(VendasServico.TERMOS_VERSAO);
        assertThat(pedido.get("termos_ip")).isNotNull();
        assertThat(pedido.get("cidade")).isEqualTo("Recife");

        // O endereço vai para a lista de envios, e o professor marca o que saiu.
        var id = (Integer) pedido.get("id");
        get("/api/admin/vendas/pedidos", ADMIN)
                .andExpect(jsonPath("$[0].endereco").value("Rua do Teste, 100, apto 302 — Centro, Recife/PE — CEP 53030-260"));
        patch("/api/admin/vendas/pedidos/" + id + "/envio", "{\"envio\": 1, \"enviado\": true}", ADMIN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.envio1_em").exists())
                .andExpect(jsonPath("$.envio2_em").doesNotExist());
        patch("/api/admin/vendas/pedidos/" + id + "/envio", "{\"envio\": 1, \"enviado\": true}", ALUNO)
                .andExpect(status().isForbidden());
    }

    @Test
    void noCartaoAMatriculaVemAntesDoCurso() throws Exception {
        planoDoCurso();
        comprar("extensivo-2027-curso", "Carla Souza", "carla@exemplo.com", CPF, "CARTAO").andExpect(status().isOk());
        var matricula = asaasDeMentira().criados.getLast();
        assertThat(matricula.forma()).isEqualTo(Asaas.Forma.PIX_OU_CARTAO);
        assertThat(matricula.parcelasMax()).isEqualTo(3);
        assertThat(matricula.valorCentavos()).isEqualTo(33000);
        var token = tokenDoUltimoCheckout();
        var curso = "/api/vendas/pedidos/" + token + "/curso";

        situacao(token).andExpect(jsonPath("$.falta_pagar_curso").value(false));
        mvc.perform(MockMvcRequestBuilders.post(curso)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A matrícula ainda não foi confirmada. Aguarde um instante."));

        // Matrícula paga: ainda sem acesso, e a página de volta oferece o curso.
        avisar("""
                {"event": "CHECKOUT_PAID", "checkout": {"id": "chk-de-mentira-1", "customer": "cus_1"}}""");
        assertThat(matriculasDoPedido()).isZero();
        situacao(token).andExpect(jsonPath("$.status").value("AGUARDANDO"))
                .andExpect(jsonPath("$.matricula_paga").value(true))
                .andExpect(jsonPath("$.falta_pagar_curso").value(true))
                .andExpect(jsonPath("$.curso_centavos").value(191880))
                .andExpect(jsonPath("$.cartao_parcelas").value(12));

        mvc.perform(MockMvcRequestBuilders.post(curso)).andExpect(status().isOk());
        var doCurso = asaasDeMentira().criados.getLast();
        assertThat(doCurso.forma()).isEqualTo(Asaas.Forma.CARTAO);
        assertThat(doCurso.parcelasMax()).isEqualTo(12);
        assertThat(doCurso.valorCentavos()).isEqualTo(191880);

        // O checkout do curso expirou: o pedido não morre, abre-se outro.
        avisar("""
                {"event": "CHECKOUT_EXPIRED", "checkout": {"id": "chk-de-mentira-2"}}""");
        situacao(token).andExpect(jsonPath("$.falta_pagar_curso").value(true));
        mvc.perform(MockMvcRequestBuilders.post(curso)).andExpect(status().isOk());

        avisar("""
                {"event": "CHECKOUT_PAID", "checkout": {"id": "chk-de-mentira-3", "customer": "cus_1"}}""");
        assertThat(matriculasDoPedido()).isEqualTo(2);
        situacao(token).andExpect(jsonPath("$.status").value("PAGO"))
                .andExpect(jsonPath("$.falta_pagar_curso").value(false));
        mvc.perform(MockMvcRequestBuilders.post(curso)).andExpect(status().isBadRequest());
    }

    @Test
    void pagamentoUnicoValeAteADataDoPlano() throws Exception {
        var fim = java.time.LocalDate.now(VendasServico.BRASILIA).plusMonths(3);
        post("/api/admin/vendas/planos", """
                {"nome": "Extensivo 2027 — à vista", "link": "extensivo-2027-avista", "tipo": "UNICO",
                 "preco_centavos": 199700, "parcelas_max": 12, "acesso_ate": "%s",
                 "turmas": ["Extensivo 2027", "Intensivo 2027"]}""".formatted(fim), ADMIN)
                .andExpect(status().isOk());
        comprar("extensivo-2027-avista", "Carla Souza", "carla@exemplo.com", CPF)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escolha como pagar: Pix à vista ou cartão."));
        comprar("extensivo-2027-avista", "Carla Souza", "carla@exemplo.com", CPF, "CARTAO").andExpect(status().isOk());
        assertThat(asaasDeMentira().criados.getLast().parcelasMax()).isEqualTo(12);
        assertThat(asaasDeMentira().criados.getLast().forma()).isEqualTo(Asaas.Forma.CARTAO);

        avisar("""
                {"event": "CHECKOUT_PAID", "checkout": {"id": "chk-de-mentira-1", "customer": "cus_2"}}""");
        assertThat(matriculasDoPedido()).isEqualTo(2);
        vendas.revogarVencidos(fim.atStartOfDay(VendasServico.BRASILIA).toInstant().plus(12, ChronoUnit.HOURS));
        assertThat(matriculasDoPedido()).isEqualTo(2); // o último dia vale inteiro
        vendas.revogarVencidos(fim.plusDays(1).atStartOfDay(VendasServico.BRASILIA).toInstant().plusSeconds(60));
        assertThat(matriculasDoPedido()).isZero();
    }

    @Test
    void oProfessorVeOsPedidos() throws Exception {
        planoMensal();
        comprar("extensivo-2027", "Carla Souza", "carla@exemplo.com", CPF);
        avisar(PAGO);
        get("/api/admin/vendas/pedidos", ADMIN)
                .andExpect(jsonPath("$[0].nome").value("Carla Souza"))
                .andExpect(jsonPath("$[0].status").value("PAGO"))
                .andExpect(jsonPath("$[0].plano").value("Extensivo 2027 — mensal"))
                .andExpect(jsonPath("$[0].acesso_liberado").value(true));
    }
}
