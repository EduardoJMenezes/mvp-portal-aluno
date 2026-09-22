package br.com.plataforma.contas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Senha (bcrypt), senha temporária, token do MCP e o SHA-256 dele. */
public final class Senhas {

    // O mesmo bcrypt do Python ($2b$ confere aqui): as senhas gravadas continuam valendo.
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom ALEATORIO = new SecureRandom();
    /** 12 caracteres sem os que se confundem (l e 1, o e 0): cerca de 59 bits. */
    private static final String ALFABETO = "abcdefghjkmnpqrstuvwxyz23456789";

    private Senhas() {}

    public static String hash(String senha) {
        return BCRYPT.encode(senha);
    }

    public static boolean confere(String senha, String hash) {
        try {
            return senha != null && hash != null && BCRYPT.matches(senha, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String temporaria() {
        var sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ALFABETO.charAt(ALEATORIO.nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }

    /** Valor em claro do token. Aparece uma vez; o banco guarda só o hash. */
    public static String novoTokenMcp() {
        var bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return "pvm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, não bcrypt: o token é aleatório de 256 bits (não há dicionário para atacar) e é
     * conferido a cada chamada, então precisa ser barato e determinístico para virar índice. Mesmo
     * cálculo do {@code hash_token} do Python — mudar aqui invalida todo token vivo.
     */
    public static String sha256(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM sem SHA-256", e);
        }
    }
}
