package br.com.plataforma.portal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * O que o portal precisa saber do ambiente.
 *
 * <p>A sessão viaja num cookie httpOnly assinado com {@code jwtSecret}. Em produção (https) ele é
 * Secure; só o teste e o desenvolvimento em http desligam. {@code modoDemo} ligado deixa qualquer
 * visitante entrar nas contas {@code .demo} sem senha — nunca em ambiente com gente de verdade.
 */
@ConfigurationProperties("portal")
public record ConfigDoPortal(
        @DefaultValue("dev-only-trocar-antes-de-qualquer-coisa-real") String jwtSecret,
        @DefaultValue("12") int jwtExpiraHoras,
        @DefaultValue("true") boolean cookieSeguro,
        @DefaultValue("false") boolean modoDemo,
        @DefaultValue({"http://localhost:3000", "http://127.0.0.1:3000"}) List<String> corsOrigins,
        /** Onde mora o adaptador MCP — é lá que a página de envio do .docx e dos prints vive. */
        @DefaultValue("") String mcpBaseUrl,
        /** A pasta com o portal exportado pelo Next ({@code frontend/out}). */
        @DefaultValue("../frontend/out") String frontendDir) {

    public static final String SEGREDO_DE_DESENVOLVIMENTO = "dev-only-trocar-antes-de-qualquer-coisa-real";
}
