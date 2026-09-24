package br.com.plataforma.vendas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onde o Asaas avisa: checkout pago, mensalidade paga ou atrasada, estorno, assinatura cancelada.
 *
 * <p>A credencial é o {@code authToken} cadastrado no webhook, que volta no cabeçalho
 * {@code asaas-access-token}. Sem ele, qualquer um diria "pago" e ganharia a turma.
 *
 * <p>Erro na hora de tratar um aviso nosso devolve 500, e o Asaas tenta de novo; aviso que não
 * reconhecemos devolve 200 — senão ele pausa a fila inteira por causa de um evento sem interesse.
 */
@RestController
public class WebhookDoAsaas {

    private static final Logger log = LoggerFactory.getLogger(WebhookDoAsaas.class);

    private final ConfigDoAsaas.Chaves chaves;
    private final VendasServico vendas;

    public WebhookDoAsaas(ConfigDoAsaas.Chaves chaves, VendasServico vendas) {
        this.chaves = chaves;
        this.vendas = vendas;
    }

    @PostMapping("/api/asaas/webhook")
    public ResponseEntity<Map<String, Object>> receber(@RequestBody Map<String, Object> aviso,
            @RequestHeader(value = "asaas-access-token", required = false) String token) {
        var esperado = chaves.webhookToken();
        if (esperado == null || esperado.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("detail", "ASAAS_WEBHOOK_TOKEN não configurado."));
        }
        if (token == null || !MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("aviso do Asaas com token que não confere: recusado");
            return ResponseEntity.status(401).body(Map.of("detail", "Token do webhook não confere."));
        }
        try {
            vendas.tratarAviso(aviso, Instant.now());
        } catch (br.com.plataforma.comum.RegraDeNegocio e) {
            // Repetir não muda a regra: fica no log para o professor resolver à mão.
            log.error("aviso {} do Asaas barrado por regra: {}", aviso.get("event"), e.getMessage());
        }
        return ResponseEntity.ok(Map.of("recebido", true));
    }
}
