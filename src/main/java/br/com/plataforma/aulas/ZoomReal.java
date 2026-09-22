package br.com.plataforma.aulas;

import br.com.plataforma.comum.ServicoExterno;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * O Zoom de verdade, por Server-to-Server OAuth.
 *
 * <p>Escrito de fora para dentro como uma trava: <b>allowlist de rotas</b> — caminho que não casa
 * levanta erro antes de virar HTTP —, e nada de listagem. O app nem tem escopo para listar.
 */
public class ZoomReal implements Zoom {

    static final String BASE = "https://api.zoom.us/v2";
    static final String TOKEN_URL = "https://zoom.us/oauth/token";
    static final String FUSO = "America/Sao_Paulo";

    /** O que este cliente pode chamar. Qualquer outra coisa nem sai da máquina. */
    private static final List<Map.Entry<String, Pattern>> ROTAS = List.of(
            Map.entry("POST", Pattern.compile("^/users/[^/]+/meetings$")),
            Map.entry("GET", Pattern.compile("^/meetings/\\d+$")),
            Map.entry("PATCH", Pattern.compile("^/meetings/\\d+$")),
            Map.entry("DELETE", Pattern.compile("^/meetings/\\d+$")),
            Map.entry("POST", Pattern.compile("^/meetings/\\d+/registrants$")));

    private final String contaId;
    private final String clientId;
    private final String segredo;
    private final String host;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private String token;
    private Instant tokenAte = Instant.EPOCH;

    public ZoomReal(String contaId, String clientId, String segredo, String host, String base) {
        this.contaId = contaId;
        this.clientId = clientId;
        this.segredo = segredo;
        this.host = host;
        this.base = base.replaceAll("/$", "");
    }

    /** Token de 1 h, guardado até faltar um minuto. O Zoom não dá refresh: pedir de novo é o jeito. */
    private synchronized String autorizacao() {
        if (token != null && Instant.now().isBefore(tokenAte)) {
            return token;
        }
        var basico = Base64.getEncoder().encodeToString((clientId + ":" + segredo).getBytes(StandardCharsets.UTF_8));
        var corpo = "grant_type=account_credentials&account_id=" + URLEncoder.encode(contaId, StandardCharsets.UTF_8);
        var resposta = enviar(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basico)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build());
        if (resposta.statusCode() != 200) {
            throw new ServicoExterno(("O Zoom recusou a credencial (%d): %s. Confira se o app "
                    + "Server-to-Server OAuth está ativado no marketplace.")
                    .formatted(resposta.statusCode(), mensagem(resposta.body())));
        }
        var dados = json.readValue(resposta.body(), Map.class);
        token = String.valueOf(dados.get("access_token"));
        var expira = dados.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        tokenAte = Instant.now().plusSeconds(Math.max(60, expira - 60));
        return token;
    }

    private Map<?, ?> chamar(String metodo, String caminho, Object corpo) {
        if (ROTAS.stream().noneMatch(r -> r.getKey().equals(metodo) && r.getValue().matcher(caminho).matches())) {
            throw new ServicoExterno(("%s %s não está na lista do cliente. A conta é dividida com "
                    + "outra plataforma: chamada nova entra na allowlist, com o porquê.").formatted(metodo, caminho));
        }
        var pedido = HttpRequest.newBuilder(URI.create(base + caminho))
                .header("Authorization", "Bearer " + autorizacao())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20));
        var bytes = corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo));
        var resposta = enviar(pedido.method(metodo, bytes).build());
        if (resposta.statusCode() >= 400) {
            throw new ServicoExterno("%s %s → %d: %s".formatted(metodo, caminho, resposta.statusCode(),
                    mensagem(resposta.body())));
        }
        if (resposta.body() == null || resposta.body().isBlank()) {
            return Map.of();
        }
        return json.readValue(resposta.body(), Map.class);
    }

    private HttpResponse<String> enviar(HttpRequest pedido) {
        try {
            return http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ServicoExterno("Não foi possível falar com o Zoom: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServicoExterno("Chamada ao Zoom interrompida.", e);
        }
    }

    private String mensagem(String corpo) {
        try {
            var dados = json.readValue(corpo, Map.class);
            return String.valueOf(dados.getOrDefault("message", corpo));
        } catch (RuntimeException e) {
            return corpo == null ? "" : corpo.substring(0, Math.min(200, corpo.length()));
        }
    }

    private static String texto(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String instante(Instant momento) {
        return DateTimeFormatter.ISO_INSTANT.format(momento.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private static String ate(String texto, int tamanho) {
        return texto == null ? "" : texto.substring(0, Math.min(texto.length(), tamanho));
    }

    @Override
    public Sala criarAula(String titulo, Instant inicio, int minutos, String descricao, boolean gravar) {
        var corpo = Map.of(
                "topic", ate(titulo, 200),
                "type", 2,
                "start_time", instante(inicio),
                "duration", minutos,
                "timezone", FUSO,
                "agenda", ate(descricao, 2000),
                "settings", Map.of(
                        // Sem sala de espera: a porta é a inscrição, e quem a libera é o portal.
                        "waiting_room", false,
                        "join_before_host", false,
                        "approval_type", 0,
                        "registration_type", 1,
                        "auto_recording", gravar ? "cloud" : "none",
                        "meeting_authentication", false,
                        "registrants_email_notification", false));
        var d = chamar("POST", "/users/" + host + "/meetings", corpo);
        return new Sala(String.valueOf(d.get("id")), texto(d.get("join_url")),
                texto(d.get("password")));
    }

    @Override
    public void editarAula(String meetingId, String titulo, Instant inicio, int minutos) {
        chamar("PATCH", "/meetings/" + meetingId,
                Map.of("topic", ate(titulo, 200), "start_time", instante(inicio), "duration", minutos));
    }

    @Override
    public void cancelarAula(String meetingId) {
        chamar("DELETE", "/meetings/" + meetingId, null);
    }

    @Override
    public String linkDeInicio(String meetingId) {
        return texto(chamar("GET", "/meetings/" + meetingId, null).get("start_url"));
    }

    @Override
    public String inscrever(String meetingId, String nome, String sobrenome, String email) {
        var d = chamar("POST", "/meetings/" + meetingId + "/registrants", Map.of(
                "email", email, "first_name", ate(nome, 64),
                "last_name", sobrenome == null || sobrenome.isBlank() ? "." : ate(sobrenome, 64)));
        return texto(d.get("join_url"));
    }
}
