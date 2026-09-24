package br.com.plataforma.vendas;

import br.com.plataforma.comum.ServicoExterno;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.ObjectMapper;

/**
 * O Asaas, do jeito que a venda usa: abrir um checkout. O cartão é digitado na página dele e nunca
 * passa por aqui. O resto — pago, atrasado, cancelado — chega pelo webhook.
 */
public interface Asaas {

    record Comprador(String nome, String email, String cpf, String celular, String cep, String numero) {}

    record NovoCheckout(String titulo, String descricao, int valorCentavos, Plano.Tipo tipo, int parcelasMax,
            Comprador comprador, String referencia, String urlDeSucesso, String urlDeVolta) {}

    record Checkout(String id, String link) {}

    Checkout criarCheckout(NovoCheckout pedido);

    /** Chave de teste (sandbox) e de produção são contas diferentes; o prefixo diz qual é qual. */
    static String baseDaChave(String chave) {
        return chave.startsWith("$aact_prod_") ? "https://api.asaas.com/v3" : "https://api-sandbox.asaas.com/v3";
    }

    final class Real implements Asaas {

        private final String chave;
        private final String base;
        private final String icone;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final ObjectMapper json = new ObjectMapper();

        Real(String chave, String base, byte[] icone) {
            this.chave = chave;
            this.base = base.replaceAll("/$", "");
            this.icone = Base64.getEncoder().encodeToString(icone);
        }

        @Override
        public Checkout criarCheckout(NovoCheckout p) {
            var mensal = p.tipo() == Plano.Tipo.MENSAL;
            var corpo = new HashMap<String, Object>();
            corpo.put("billingTypes", mensal ? List.of("CREDIT_CARD") : List.of("PIX", "CREDIT_CARD"));
            corpo.put("chargeTypes", mensal ? List.of("RECURRENT")
                    : p.parcelasMax() > 1 ? List.of("DETACHED", "INSTALLMENT") : List.of("DETACHED"));
            if (mensal) {
                // A primeira mensalidade cai hoje; as seguintes, no mesmo dia dos meses seguintes.
                corpo.put("subscription", Map.of("cycle", "MONTHLY",
                        "nextDueDate", LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString()));
            } else if (p.parcelasMax() > 1) {
                corpo.put("installment", Map.of("maxInstallmentCount", p.parcelasMax()));
            }
            corpo.put("minutesToExpire", 60);
            corpo.put("externalReference", p.referencia());
            corpo.put("callback", Map.of("successUrl", p.urlDeSucesso(), "cancelUrl", p.urlDeVolta(),
                    "expiredUrl", p.urlDeVolta()));
            corpo.put("items", List.of(Map.of(
                    "name", ate(p.titulo(), 30),
                    "description", ate(p.descricao(), 150),
                    "quantity", 1,
                    "value", BigDecimal.valueOf(p.valorCentavos(), 2),
                    "imageBase64", icone)));
            var cliente = new HashMap<String, Object>();
            cliente.put("name", p.comprador().nome());
            cliente.put("email", p.comprador().email());
            cliente.put("cpfCnpj", p.comprador().cpf());
            if (p.comprador().celular() != null) {
                cliente.put("phone", p.comprador().celular());
            }
            var endereco = endereco(p.comprador().cep());
            cliente.put("postalCode", p.comprador().cep());
            cliente.put("addressNumber", p.comprador().numero());
            cliente.put("address", endereco.rua());
            cliente.put("province", endereco.bairro());
            corpo.put("customerData", cliente);

            var resposta = enviar(HttpRequest.newBuilder(URI.create(base + "/checkouts"))
                    .header("access_token", chave)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "plataforma-educacional")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build());
            if (resposta.statusCode() >= 400) {
                throw new ServicoExterno("O Asaas recusou o checkout (%d): %s"
                        .formatted(resposta.statusCode(), erros(resposta.body())));
            }
            var dados = json.readValue(resposta.body(), Map.class);
            return new Checkout(String.valueOf(dados.get("id")), String.valueOf(dados.get("link")));
        }

        record Endereco(String rua, String bairro) {}

        /**
         * Rua e bairro pelo CEP (ViaCEP), para o aluno digitar só CEP e número. CEP geral de cidade
         * pequena não tem rua: vale o nome da cidade, que é o que o Correio usa nesses casos.
         */
        private Endereco endereco(String cep) {
            var resposta = enviar(HttpRequest.newBuilder(URI.create("https://viacep.com.br/ws/" + cep + "/json/"))
                    .timeout(Duration.ofSeconds(8)).GET().build());
            Map<?, ?> dados;
            try {
                dados = resposta.statusCode() == 200 ? json.readValue(resposta.body(), Map.class) : Map.of("erro", true);
            } catch (RuntimeException e) {
                dados = Map.of("erro", true);
            }
            if (dados.containsKey("erro")) {
                throw new br.com.plataforma.comum.RegraDeNegocio("CEP não encontrado. Confira os números.");
            }
            var cidade = String.valueOf(dados.get("localidade"));
            var rua = dados.get("logradouro") == null ? "" : String.valueOf(dados.get("logradouro"));
            var bairro = dados.get("bairro") == null ? "" : String.valueOf(dados.get("bairro"));
            return new Endereco(rua.isBlank() ? cidade : rua, bairro.isBlank() ? cidade : bairro);
        }

        private HttpResponse<String> enviar(HttpRequest pedido) {
            try {
                return http.send(pedido, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new ServicoExterno("Não foi possível falar com " + pedido.uri().getHost() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServicoExterno("Chamada ao Asaas interrompida.", e);
            }
        }

        /** O Asaas devolve {"errors":[{"description":...}]}; é a descrição que explica o que houve. */
        private String erros(String corpo) {
            try {
                var lista = (List<?>) json.readValue(corpo, Map.class).get("errors");
                return lista.stream().map(e -> String.valueOf(((Map<?, ?>) e).get("description")))
                        .reduce((a, b) -> a + "; " + b).orElse(corpo);
            } catch (RuntimeException e) {
                return corpo == null ? "" : corpo.substring(0, Math.min(300, corpo.length()));
            }
        }

        private static String ate(String texto, int tamanho) {
            return texto == null ? "" : texto.length() <= tamanho ? texto : texto.substring(0, tamanho);
        }
    }

    /** Guarda os pedidos de checkout, para os testes conferirem o que iria ao Asaas. */
    final class DeMentira implements Asaas {

        public final List<NovoCheckout> criados = new CopyOnWriteArrayList<>();

        @Override
        public Checkout criarCheckout(NovoCheckout pedido) {
            criados.add(pedido);
            var id = "chk-de-mentira-" + criados.size();
            return new Checkout(id, "https://sandbox.asaas.com/checkoutSession/show/" + id);
        }
    }
}
