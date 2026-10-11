package br.com.plataforma.importacoes;

import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.ServicoExterno;
import br.com.plataforma.portal.ConfigDoPortal;
import br.com.plataforma.seguranca.ConfigDaPlataforma;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Pede ao adaptador MCP que leia um .docx — é lá que moram o leitor, o LibreOffice e o Pillow — e
 * devolve o que ele encontrou, sem gravar nada. Quem se identifica é a API, com o segredo de
 * serviço: a sessão do professor já foi conferida antes de chegar aqui.
 */
@Component
public class LeitorDeDocx {

    private final String base;
    private final String segredo;
    private final ObjectMapper json;
    // HTTP/1.1 explícito, como no proxy de envio: o uvicorn recusa o upgrade para h2c e descarta o corpo.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public LeitorDeDocx(ConfigDoPortal portal, ConfigDaPlataforma plataforma, ObjectMapper json) {
        this.base = portal.mcpBaseUrl().replaceAll("/$", "");
        this.segredo = plataforma.tokenDeServico();
        this.json = json;
    }

    public ImportacoesServico.LeituraDoDocx ler(String nome, byte[] conteudo) {
        if (base.isBlank()) {
            throw new ServicoExterno("MCP_BASE_URL não configurado: a leitura de .docx mora no serviço do MCP.");
        }
        var fronteira = "----plataforma" + UUID.randomUUID();
        var corpo = new ByteArrayOutputStream();
        var arquivo = (nome == null || nome.isBlank() ? "simulado.docx" : nome).replace("\"", "");
        try {
            corpo.write(("--" + fronteira + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"" + arquivo
                    + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            corpo.write(conteudo);
            corpo.write(("\r\n--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8));

            var pedido = HttpRequest.newBuilder(URI.create(base + "/api/leitura/docx"))
                    // O LibreOffice pode levar um tempo convertendo figura em formato antigo.
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "multipart/form-data; boundary=" + fronteira)
                    .header("X-Servico", segredo)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build();
            var resposta = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
            if (resposta.statusCode() / 100 != 2) {
                var detalhe = detalheDe(resposta.body());
                // O leitor recusando o arquivo é recado para o professor; o resto é o serviço fora do ar.
                if (resposta.statusCode() == 400 || resposta.statusCode() == 422) {
                    throw new RegraDeNegocio(detalhe);
                }
                throw new ServicoExterno("O leitor de .docx respondeu %d: %s".formatted(resposta.statusCode(), detalhe));
            }
            return json.readValue(resposta.body(), ImportacoesServico.LeituraDoDocx.class);
        } catch (IOException e) {
            throw new ServicoExterno("O leitor de .docx não respondeu: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServicoExterno("Leitura do .docx interrompida.", e);
        }
    }

    private String detalheDe(byte[] corpo) {
        try {
            var lido = json.readValue(corpo, Map.class);
            var detalhe = lido.get("detail");
            return detalhe == null ? new String(corpo, StandardCharsets.UTF_8) : String.valueOf(detalhe);
        } catch (RuntimeException e) {
            return new String(corpo, StandardCharsets.UTF_8);
        }
    }
}
