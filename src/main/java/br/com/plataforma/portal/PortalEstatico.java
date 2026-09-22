package br.com.plataforma.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O portal exportado pelo Next: cada rota é um {@code index.html} na pasta dela.
 *
 * <p>Três casos fogem do arquivo em disco: {@code /api/...} que não é rota devolve JSON, não a
 * página 404; o resto que não existe recebe a 404 do portal, com status 404; e a pasta pode não
 * existir (desenvolvimento com {@code next dev} na porta 3000, ou os testes).
 */
@RestController
public class PortalEstatico {

    // O export estático do Next injeta scripts inline e não há servidor para dar nonce a eles.
    // ponytail: 'unsafe-inline' em script-src; o XSS fica contido pelo texto escapado e pela
    // sessão httpOnly. Nonce exige o Next rodando como servidor.
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob: https://i.vimeocdn.com",
            "font-src 'self' data:",
            "connect-src 'self'",
            "frame-src https://player.vimeo.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private final Path raiz;

    public PortalEstatico(ConfigDoPortal config) {
        var pasta = Path.of(config.frontendDir()).toAbsolutePath().normalize();
        this.raiz = Files.isDirectory(pasta) ? pasta : null;
        var log = LoggerFactory.getLogger(PortalEstatico.class);
        if (raiz == null) {
            log.warn("portal estático não encontrado em {}: só a API responde", pasta);
        } else {
            log.info("portal servido de {}", raiz);
        }
    }

    @RequestMapping(value = "/**", method = {org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public ResponseEntity<?> servir(HttpServletRequest pedido) {
        var caminho = pedido.getRequestURI().replaceAll("^/+", "");
        if (caminho.startsWith("api/") || caminho.equals("api")) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("detail", "Não encontrado."));
        }
        if (raiz == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("detail", "Não encontrado."));
        }

        var arquivo = resolver(caminho);
        var status = 200;
        if (arquivo == null) {
            arquivo = raiz.resolve("404.html");
            status = 404;
            if (!Files.isRegularFile(arquivo)) {
                return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("detail", "Não encontrado."));
            }
        }

        var tipo = MediaTypeFactory.getMediaType(arquivo.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (tipo.equals(MediaType.TEXT_HTML)) {
            tipo = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
        }
        // O nome do arquivo em /_next/static carrega o hash do conteúdo: mudou o conteúdo, mudou o
        // nome. Então ele pode ser guardado para sempre. O HTML aponta para esses arquivos e não
        // pode envelhecer, senão o deploy novo demora a aparecer.
        var cache = caminho.startsWith("_next/static/") ? "public, max-age=31536000, immutable" : "no-cache";
        return ResponseEntity.status(status)
                .header("Content-Security-Policy", CSP)
                .header(HttpHeaders.CACHE_CONTROL, cache)
                .contentType(tipo)
                .body(new FileSystemResource(arquivo));
    }

    /** O arquivo em disco para o caminho pedido, ou nulo. Nunca sai da raiz. */
    private Path resolver(String caminho) {
        var limpo = caminho.isEmpty() ? "" : caminho;
        var alvo = raiz.resolve(limpo).normalize();
        if (!alvo.startsWith(raiz)) {
            return null;
        }
        // /enviar/<token> cai na página /enviar/, que lê o token do endereço — o link chega pelo
        // chat e não existe como arquivo.
        if (limpo.startsWith("enviar/") || limpo.equals("enviar")) {
            var pagina = raiz.resolve("enviar/index.html");
            return Files.isRegularFile(pagina) ? pagina : null;
        }
        if (Files.isRegularFile(alvo)) {
            return alvo;
        }
        var indice = alvo.resolve("index.html");
        if (Files.isRegularFile(indice)) {
            return indice;
        }
        var html = raiz.resolve(limpo + ".html").normalize();
        return html.startsWith(raiz) && Files.isRegularFile(html) ? html : null;
    }
}
