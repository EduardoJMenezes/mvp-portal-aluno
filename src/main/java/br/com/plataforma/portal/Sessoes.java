package br.com.plataforma.portal;

import br.com.plataforma.contas.Usuario;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A sessão do portal: um JWT HS256, o mesmo que o Python emitia.
 *
 * <p>O formato é mantido de propósito: o cookie que o professor já tem no navegador continua
 * valendo na troca de backend. São três campos — {@code sub}, {@code iat}, {@code exp} —, e quem
 * decide o que a sessão pode fazer é o papel relido do banco, nunca o que está gravado aqui.
 */
@Component
public class Sessoes {

    public record Dados(Integer usuarioId, Instant emitidaEm) {}

    private static final Base64.Encoder CODIFICA = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODIFICA = Base64.getUrlDecoder();
    private static final String CABECALHO = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] segredo;
    private final Duration validade;
    private final ObjectMapper json;

    /** HS256 pede uma chave do tamanho do hash: menos que isso é adivinhável fora de linha. */
    static final int SEGREDO_MINIMO_BYTES = 32;

    /**
     * Com o cookie Secure — isto é, em produção — o portal não sobe com o segredo de
     * desenvolvimento, que está publicado no repositório, nem com um curto: quem o conhece assina a
     * sessão de qualquer usuário. É a mesma regra do {@code SERVICO_TOKEN}: melhor um deploy que
     * falha do que uma porta aberta. Em http local e nos testes ({@code cookie-seguro=false}) o
     * padrão continua valendo, com o aviso.
     */
    public Sessoes(ConfigDoPortal config, ObjectMapper json) {
        this.segredo = config.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.validade = Duration.ofHours(config.jwtExpiraHoras());
        this.json = json;
        var deDesenvolvimento = config.jwtSecret().equals(ConfigDoPortal.SEGREDO_DE_DESENVOLVIMENTO);
        if (config.cookieSeguro() && (deDesenvolvimento || segredo.length < SEGREDO_MINIMO_BYTES)) {
            throw new IllegalStateException(
                    "defina JWT_SECRET com pelo menos %d caracteres aleatórios: é ele que assina a sessão"
                            .formatted(SEGREDO_MINIMO_BYTES));
        }
        if (deDesenvolvimento) {
            LoggerFactory.getLogger(Sessoes.class)
                    .warn("JWT_SECRET é o valor de desenvolvimento: defina um segredo aleatório fora da máquina local");
        }
    }

    public Duration validade() {
        return validade;
    }

    public String criar(Usuario usuario, Instant agora) {
        var corpo = json.writeValueAsString(Map.of(
                "sub", String.valueOf(usuario.getId()),
                "papel", usuario.getPapel().name(),
                "exp", agora.plus(validade).getEpochSecond(),
                "iat", agora.getEpochSecond()));
        var base = codificar(CABECALHO) + "." + codificar(corpo);
        return base + "." + CODIFICA.encodeToString(assinar(base));
    }

    /** Vazio quando a assinatura não bate, o token está mal formado ou expirou. */
    public Optional<Dados> ler(String token, Instant agora) {
        if (token == null) {
            return Optional.empty();
        }
        var partes = token.split("\\.");
        if (partes.length != 3) {
            return Optional.empty();
        }
        try {
            var esperada = assinar(partes[0] + "." + partes[1]);
            if (!MessageDigest.isEqual(esperada, DECODIFICA.decode(partes[2]))) {
                return Optional.empty();
            }
            var cabecalho = json.readValue(DECODIFICA.decode(partes[0]), Map.class);
            if (!"HS256".equals(cabecalho.get("alg"))) {
                return Optional.empty();
            }
            var corpo = json.readValue(DECODIFICA.decode(partes[1]), Map.class);
            var exp = ((Number) corpo.get("exp")).longValue();
            if (agora.getEpochSecond() >= exp) {
                return Optional.empty();
            }
            var iat = corpo.get("iat") instanceof Number n ? n.longValue() : 0L;
            return Optional.of(new Dados(Integer.parseInt(String.valueOf(corpo.get("sub"))),
                    Instant.ofEpochSecond(iat)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] assinar(String base) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo, "HmacSHA256"));
            return mac.doFinal(base.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JVM sem HmacSHA256", e);
        }
    }

    private static String codificar(String texto) {
        return CODIFICA.encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }
}
