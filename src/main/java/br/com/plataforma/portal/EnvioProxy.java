package br.com.plataforma.portal;

import br.com.plataforma.comum.ServicoExterno;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * A página de envio do .docx e dos prints mora no adaptador MCP — é lá que estão o leitor de .docx,
 * o LibreOffice e o Pillow. As telas do professor no portal chamam estas rotas na mesma origem, e
 * este proxy as repassa. O link é a credencial: não há sessão para conferir aqui.
 */
@RestController
@RequestMapping("/api/importacoes")
public class EnvioProxy {

    private final String base;
    // HTTP/1.1 explícito: em texto puro o cliente tentaria o upgrade para h2c, e o uvicorn do mcp
    // recusa o upgrade e descarta o corpo — o multipart chegava vazio.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public EnvioProxy(ConfigDoPortal config) {
        this.base = config.mcpBaseUrl().replaceAll("/$", "");
    }

    @RequestMapping(value = {"/{token}", "/{token}/arquivo", "/{token}/prints"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> repassar(@PathVariable String token, HttpServletRequest pedido)
            throws IOException, ServletException {
        if (base.isBlank()) {
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"MCP_BASE_URL não configurado: o envio de arquivos mora no serviço do MCP.\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        var montado = HttpRequest.newBuilder(URI.create(base + pedido.getRequestURI())).timeout(Duration.ofMinutes(3));
        if ("POST".equalsIgnoreCase(pedido.getMethod())) {
            var tipo = pedido.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : pedido.getContentType();
            if (tipo.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                var fronteira = "----plataforma" + UUID.randomUUID();
                montado.header("Content-Type", "multipart/form-data; boundary=" + fronteira)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(remontar(pedido, fronteira)));
            } else {
                montado.header("Content-Type", tipo)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(pedido.getInputStream().readAllBytes()));
            }
        } else {
            montado.GET();
        }
        try {
            var resposta = http.send(montado.build(), HttpResponse.BodyHandlers.ofByteArray());
            var tipo = resposta.headers().firstValue("content-type").orElse(MediaType.APPLICATION_JSON_VALUE);
            return ResponseEntity.status(resposta.statusCode()).header("Content-Type", tipo).body(resposta.body());
        } catch (IOException e) {
            throw new ServicoExterno("O serviço de envio não respondeu: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServicoExterno("Envio interrompido.", e);
        }
    }

    /** O Tomcat já leu as partes e o corpo cru não existe mais: o multipart é remontado delas. */
    private static byte[] remontar(HttpServletRequest pedido, String fronteira) throws IOException, ServletException {
        var corpo = new ByteArrayOutputStream();
        var quebra = "\r\n".getBytes(StandardCharsets.UTF_8);
        for (var parte : pedido.getParts()) {
            var cabecalho = new StringBuilder("--").append(fronteira).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(parte.getName()).append('"');
            if (parte.getSubmittedFileName() != null) {
                cabecalho.append("; filename=\"").append(parte.getSubmittedFileName().replace("\"", "")).append('"');
            }
            cabecalho.append("\r\n");
            if (parte.getContentType() != null) {
                cabecalho.append("Content-Type: ").append(parte.getContentType()).append("\r\n");
            }
            cabecalho.append("\r\n");
            corpo.write(cabecalho.toString().getBytes(StandardCharsets.UTF_8));
            try (var entrada = parte.getInputStream()) {
                entrada.transferTo(corpo);
            }
            corpo.write(quebra);
        }
        corpo.write(("--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return corpo.toByteArray();
    }
}
