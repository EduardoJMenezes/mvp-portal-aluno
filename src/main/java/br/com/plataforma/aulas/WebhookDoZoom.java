package br.com.plataforma.aulas;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Onde o Zoom avisa que algo aconteceu: a gravação ficou pronta, alguém entrou ou saiu.
 *
 * <p>Não há sessão aqui — a credencial é a assinatura. Sem ela, qualquer um diria "a aula acabou"
 * e faria o servidor mandar o Vimeo buscar um arquivo à escolha dele. Até o desafio de validação
 * passa pela assinatura: responder a um desafio sem conferir entregaria o HMAC de qualquer texto,
 * e com ele dá para forjar um aviso.
 */
@RestController
public class WebhookDoZoom {

    private static final Logger log = LoggerFactory.getLogger(WebhookDoZoom.class);
    /** Aviso mais velho que isto é repetição de alguém, não do Zoom. */
    static final Duration JANELA = Duration.ofMinutes(5);

    private final ConfigDoZoom.Chaves chaves;
    private final EventosDoZoom eventos;
    private final ConfigDoZoom.TrabalhoDoZoom trabalho;
    private final ObjectMapper json = new ObjectMapper();

    public WebhookDoZoom(ConfigDoZoom.Chaves chaves, EventosDoZoom eventos, ConfigDoZoom.TrabalhoDoZoom trabalho) {
        this.chaves = chaves;
        this.eventos = eventos;
        this.trabalho = trabalho;
    }

    @PostMapping("/api/zoom/webhook")
    public ResponseEntity<Map<String, String>> receber(@RequestBody byte[] corpo,
            @RequestHeader(value = "x-zm-signature", required = false) String assinatura,
            @RequestHeader(value = "x-zm-request-timestamp", required = false) String timestamp) {
        var segredo = chaves.webhookSecret();
        if (segredo == null || segredo.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("detail", "ZOOM_WEBHOOK_SECRET não configurado."));
        }
        if (!assinaturaConfere(corpo, timestamp, assinatura, segredo, Instant.now())) {
            log.warn("aviso do Zoom com assinatura que não confere: recusado");
            return ResponseEntity.status(401).body(Map.of("detail", "Assinatura do Zoom não confere."));
        }
        var evento = json.readValue(corpo, Map.class);
        if ("endpoint.url_validation".equals(evento.get("event"))) {
            var plano = String.valueOf(((Map<?, ?>) evento.get("payload")).get("plainToken"));
            // Map, não record: as chaves vão em camelCase, como o Zoom exige.
            return ResponseEntity.ok(Map.of("plainToken", plano, "encryptedToken", hmac(segredo, plano)));
        }
        trabalho.executor().execute(() -> {
            try {
                eventos.tratar(evento);
            } catch (RuntimeException e) {
                log.error("aviso {} do Zoom falhou", evento.get("event"), e);
            }
        });
        return ResponseEntity.ok(Map.of());
    }

    /**
     * {@code v0=HMAC-SHA256("v0:<timestamp>:<corpo>")}. O timestamp vem em segundos; aceita
     * milissegundos também, que já foi o engano de uma versão anterior deste código.
     */
    static boolean assinaturaConfere(byte[] corpo, String timestamp, String assinatura, String segredo,
            Instant agora) {
        if (timestamp == null || assinatura == null || !timestamp.chars().allMatch(Character::isDigit)
                || timestamp.isEmpty() || timestamp.length() > 15) {
            return false;
        }
        var numero = Long.parseLong(timestamp);
        var quando = numero > 100_000_000_000L ? Instant.ofEpochMilli(numero) : Instant.ofEpochSecond(numero);
        if (Duration.between(quando, agora).abs().compareTo(JANELA) > 0) {
            return false;
        }
        var esperado = "v0=" + hmac(segredo, "v0:" + timestamp + ":" + new String(corpo, StandardCharsets.UTF_8));
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                assinatura.getBytes(StandardCharsets.UTF_8));
    }

    static String hmac(String segredo, String mensagem) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(mensagem.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
