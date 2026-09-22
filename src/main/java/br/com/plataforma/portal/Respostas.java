package br.com.plataforma.portal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** O erro no formato que o portal mostra: {@code {"detail": "..."}}. */
final class Respostas {

    private Respostas() {}

    static void erro(HttpServletResponse resposta, int status, String detalhe) throws IOException {
        resposta.setStatus(status);
        resposta.setContentType("application/json;charset=UTF-8");
        resposta.getOutputStream().write(("{\"detail\":" + aspas(detalhe) + "}").getBytes(StandardCharsets.UTF_8));
    }

    static String aspas(String texto) {
        var sb = new StringBuilder("\"");
        for (var c : texto.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
