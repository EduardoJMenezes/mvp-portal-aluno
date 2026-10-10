package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Papel;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A escrita idêntica, repetida logo em seguida, não roda de novo: o servidor devolve a resposta da
 * primeira. É a segunda tranca do clique repetido — a primeira é o botão do portal, que se trava.
 */
class RepeticaoTest extends BaseDoPortal {

    private static final String NOVA = """
            {"nome": "Q01", "nova": {"enunciado": "Qual a massa de 2 mol de água?",
              "alternativas": {"A": "36 g", "B": "18 g", "C": "20 g", "D": "34 g"}, "gabarito": "A"}}""";

    private int apostila;
    private String rota;

    @BeforeEach
    void cenario() throws Exception {
        comSenhas();
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
        apostila = jdbc.queryForObject("SELECT id FROM submodules WHERE nome = 'Questões da apostila'", Integer.class);
        rota = "/api/admin/submodulos/" + apostila + "/questao";
        repeticoes.janela(Duration.ofSeconds(5));
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private int linhas() {
        return contar("items WHERE submodulo_id = ? AND removido_em IS NULL", apostila);
    }

    @Test
    void oMesmoPedidoEmSeguidaNaoCriaDuasQuestoes() throws Exception {
        var primeira = post(rota, NOVA, ADMIN)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var segunda = post(rota, NOVA, ADMIN)
                .andExpect(status().isOk())
                .andExpect(header().string(FiltroDaRepeticao.CABECALHO, "1"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // A mesma resposta, byte a byte, e uma linha só no curso.
        assertThat(segunda).isEqualTo(primeira);
        assertThat(linhas()).isEqualTo(1);
        assertThat(contar("questions")).isEqualTo(1);
    }

    @Test
    void outroCorpoOuOutraPessoaNaoERepeticao() throws Exception {
        post(rota, NOVA, ADMIN).andExpect(status().isOk());
        post(rota, NOVA.replace("Q01", "Q02").replace("2 mol", "3 mol"), ADMIN)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO))
                .andExpect(jsonPath("$.nome").value("Q02"));
        assertThat(linhas()).isEqualTo(2);

        // Outro operador mandando o mesmo corpo é outra pessoa pedindo: roda.
        var gerenciador = criarUsuario("Gil", "gil@teste.invalid", SENHA, "GERENCIADOR", false);
        post(rota, NOVA.replace("Q01", "Q02").replace("2 mol", "3 mol"), gerenciador)
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO));
        assertThat(linhas()).isEqualTo(3);
    }

    @Test
    void comAlgoNoMeioOPedidoIgualRodaDeNovo() throws Exception {
        // Adiciona, remove e adiciona de novo: três intenções, e a terceira não pode ser engolida.
        var corpo = post(rota, NOVA, ADMIN).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var linha = JsonPath.<Integer>read(corpo, "$.id");
        var modulo = jdbc.queryForObject("SELECT modulo_id FROM submodules WHERE id = ?", Integer.class, apostila);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/turmas/biblioteca/modulos/" + modulo + "/submodulos/" + apostila + "/itens/" + linha)
                        .cookie(sessao(ADMIN)))
                .andExpect(status().isOk());
        assertThat(linhas()).isZero();

        post(rota, NOVA, ADMIN)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO));
        assertThat(linhas()).isEqualTo(1);
    }

    @Test
    void oPedidoQueFalhouNaoFicaGuardado() throws Exception {
        // Recusado pela regra: quem corrige e manda de novo, ou só tenta outra vez, é atendido de verdade.
        var semGabarito = NOVA.replace("\"gabarito\": \"A\"", "\"gabarito\": \"Z\"");
        post(rota, semGabarito, ADMIN).andExpect(status().is4xxClientError());
        post(rota, semGabarito, ADMIN)
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO));
    }

    @Test
    void passadaAJanelaOPedidoIgualRodaDeNovo() throws Exception {
        repeticoes.janela(Duration.ofMillis(40));
        post(rota, NOVA, ADMIN).andExpect(status().isOk());
        Thread.sleep(80);
        // Fora da janela o pedido igual é um pedido novo, e quem decide o que fazer com ele é a regra de negócio.
        post(rota, NOVA, ADMIN).andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO));
    }

    @Test
    void leituraNuncaERepeticao() throws Exception {
        get("/api/admin/questoes", ADMIN).andExpect(status().isOk());
        get("/api/admin/questoes", ADMIN)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(FiltroDaRepeticao.CABECALHO));
    }

    /**
     * Os dois pedidos chegam juntos, antes de o primeiro terminar: só um roda, e o outro espera e
     * recebe a mesma resposta. É o caso que o botão travado não cobre (duas abas, pedido repetido
     * no caminho), e por isso é testado no filtro, com as duas linhas de execução de verdade.
     */
    @Test
    void doisPedidosJuntosRodamUmaVezSo() throws Exception {
        var filtro = new FiltroDaRepeticao(new Repeticoes(), Duration.ofSeconds(5));
        var rodou = new AtomicInteger();
        var entrou = new CountDownLatch(1);
        var podeTerminar = new CountDownLatch(1);
        var ident = new Identidade(ADMIN, "Professora Ana", "ana@teste.invalid", Papel.ADMIN, Canal.PORTAL);

        java.util.concurrent.Callable<MockHttpServletResponse> pedido = () -> {
            var contexto = SecurityContextHolder.createEmptyContext();
            contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ident, null, java.util.List.of()));
            SecurityContextHolder.setContext(contexto);
            var req = new MockHttpServletRequest("POST", "/api/admin/turmas");
            req.setContentType("application/json");
            req.setContent("{\"nome\":\"Extensivo 2028\"}".getBytes(StandardCharsets.UTF_8));
            var res = new MockHttpServletResponse();
            filtro.doFilter(req, res, (q, r) -> {
                rodou.incrementAndGet();
                // O corpo chega inteiro a quem vem depois do filtro.
                assertThat(new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("Extensivo 2028");
                entrou.countDown();
                try {
                    podeTerminar.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                var saida = (jakarta.servlet.http.HttpServletResponse) r;
                saida.setStatus(201);
                saida.setContentType("application/json");
                saida.getOutputStream().write("{\"id\":7}".getBytes(StandardCharsets.UTF_8));
            });
            return res;
        };

        try (var linhas = Executors.newFixedThreadPool(2)) {
            var primeiro = linhas.submit(pedido);
            assertThat(entrou.await(5, TimeUnit.SECONDS)).isTrue();
            var segundo = linhas.submit(pedido);
            // O segundo está esperando o primeiro: ainda não respondeu, e não rodou.
            Thread.sleep(150);
            assertThat(segundo.isDone()).isFalse();
            podeTerminar.countDown();

            var a = primeiro.get(5, TimeUnit.SECONDS);
            var b = segundo.get(5, TimeUnit.SECONDS);
            assertThat(rodou.get()).isEqualTo(1);
            assertThat(a.getStatus()).isEqualTo(201);
            assertThat(b.getStatus()).isEqualTo(201);
            assertThat(b.getContentAsString()).isEqualTo(a.getContentAsString()).isEqualTo("{\"id\":7}");
            assertThat(a.getHeader(FiltroDaRepeticao.CABECALHO)).isNull();
            assertThat(b.getHeader(FiltroDaRepeticao.CABECALHO)).isEqualTo("1");
        }
    }

    /** Corpo grande demais para comparar passa direto, inteiro, e nunca é tratado como repetição. */
    @Test
    void corpoGrandePassaInteiroESemComparacao() throws Exception {
        var filtro = new FiltroDaRepeticao(new Repeticoes(), Duration.ofSeconds(5));
        var ident = new Identidade(ADMIN, "Professora Ana", "ana@teste.invalid", Papel.ADMIN, Canal.PORTAL);
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ident, null, java.util.List.of()));
        SecurityContextHolder.setContext(contexto);
        var grande = ("{\"texto\":\"" + "a".repeat(FiltroDaRepeticao.MAXIMO + 100) + "\"}").getBytes(StandardCharsets.UTF_8);
        var rodou = new AtomicInteger();

        for (int vez = 0; vez < 2; vez++) {
            var req = new MockHttpServletRequest("POST", "/api/admin/importacoes/1/recortes");
            req.setContentType("application/json");
            req.setContent(grande);
            var res = new MockHttpServletResponse();
            filtro.doFilter(req, res, (q, r) -> {
                rodou.incrementAndGet();
                assertThat(q.getInputStream().readAllBytes()).isEqualTo(grande);
            });
            assertThat(res.getHeader(FiltroDaRepeticao.CABECALHO)).isNull();
        }
        assertThat(rodou.get()).isEqualTo(2);
    }
}
