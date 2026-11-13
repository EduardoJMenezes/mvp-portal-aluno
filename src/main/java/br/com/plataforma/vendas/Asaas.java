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

    record Comprador(String nome, String email, String cpf, String celular, String cep, String numero, String rua,
            String complemento, String bairro) {}

    /** Uma linha do checkout: o que o aluno vê que está pagando. */
    record Item(String nome, String descricao, int valorCentavos) {}

    /**
     * Como o checkout cobra. MENSAL: assinatura no cartão. PIX: só Pix. CARTAO: só cartão, parcelado
     * até {@code parcelasMax}. PIX_OU_CARTAO: o aluno escolhe (a matrícula).
     */
    enum Forma { MENSAL, PIX, CARTAO, PIX_OU_CARTAO }

    /** {@code mensalidades}: quantas cobranças a assinatura mensal faz; null é até o aluno cancelar. */
    record NovoCheckout(List<Item> itens, Forma forma, int parcelasMax, Integer mensalidades, Comprador comprador,
            String referencia, String urlDeSucesso, String urlDeVolta) {

        public int valorCentavos() {
            return itens.stream().mapToInt(Item::valorCentavos).sum();
        }
    }

    record Checkout(String id, String link) {}

    record Endereco(String rua, String bairro, String cidade, String uf) {}

    Checkout criarCheckout(NovoCheckout pedido);

    /** Rua, bairro, cidade e UF pelo CEP: o aluno digita só CEP, número e complemento. */
    Endereco endereco(String cep);

    /**
     * O checkout não aceita "número de cobranças", só a data limite dos vencimentos ({@code endDate}).
     * A n-ésima mensalidade vence {@code n - 1} meses depois da primeira; a data limite fica 15 dias
     * depois dela — folga para o ajuste de fim de mês (31 vira 30, 28) sem alcançar a seguinte.
     */
    static LocalDate ultimoVencimentoAte(LocalDate primeira, int mensalidades) {
        return primeira.plusMonths(mensalidades - 1).plusDays(15);
    }

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
            var corpo = new HashMap<String, Object>();
            corpo.put("billingTypes", switch (p.forma()) {
                case MENSAL, CARTAO -> List.of("CREDIT_CARD");
                case PIX -> List.of("PIX");
                case PIX_OU_CARTAO -> List.of("PIX", "CREDIT_CARD");
            });
            var mensal = p.forma() == Forma.MENSAL;
            var parcelado = !mensal && p.forma() != Forma.PIX && p.parcelasMax() > 1;
            corpo.put("chargeTypes", mensal ? List.of("RECURRENT")
                    : parcelado ? List.of("DETACHED", "INSTALLMENT") : List.of("DETACHED"));
            if (mensal) {
                // A primeira mensalidade cai hoje; as seguintes, no mesmo dia dos meses seguintes.
                var hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
                var assinatura = new HashMap<String, Object>(Map.of("cycle", "MONTHLY", "nextDueDate", hoje.toString()));
                if (p.mensalidades() != null) {
                    assinatura.put("endDate", ultimoVencimentoAte(hoje, p.mensalidades()).toString());
                }
                corpo.put("subscription", assinatura);
            } else if (parcelado) {
                corpo.put("installment", Map.of("maxInstallmentCount", p.parcelasMax()));
            }
            corpo.put("minutesToExpire", 60);
            corpo.put("externalReference", p.referencia());
            corpo.put("callback", Map.of("successUrl", p.urlDeSucesso(), "cancelUrl", p.urlDeVolta(),
                    "expiredUrl", p.urlDeVolta()));
            corpo.put("items", p.itens().stream().map(i -> Map.of(
                    "name", ate(i.nome(), 30),
                    "description", ate(i.descricao(), 150),
                    "quantity", 1,
                    "value", BigDecimal.valueOf(i.valorCentavos(), 2),
                    "imageBase64", icone)).toList());
            var c = p.comprador();
            var cliente = new HashMap<String, Object>();
            cliente.put("name", c.nome());
            cliente.put("email", c.email());
            cliente.put("cpfCnpj", c.cpf());
            if (c.celular() != null) {
                cliente.put("phone", c.celular());
            }
            cliente.put("postalCode", c.cep());
            cliente.put("addressNumber", c.numero());
            cliente.put("address", c.rua());
            cliente.put("province", c.bairro());
            if (c.complemento() != null) {
                cliente.put("complement", c.complemento());
            }
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

        /**
         * Pelo ViaCEP. CEP geral de cidade pequena não tem rua: vale o nome da cidade, que é o que o
         * Correio usa nesses casos.
         */
        @Override
        public Endereco endereco(String cep) {
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
            var uf = dados.get("uf") == null ? "" : String.valueOf(dados.get("uf"));
            return new Endereco(rua.isBlank() ? cidade : rua, bairro.isBlank() ? cidade : bairro, cidade, uf);
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

        @Override
        public Endereco endereco(String cep) {
            return new Endereco("Rua do Teste", "Centro", "Recife", "PE");
        }
    }
}
