package br.com.plataforma.aulas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.plataforma.comum.ServicoExterno;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A gravação sobe ao Vimeo com a privacidade pedida no próprio envio. Sem isso, quem decide quem
 * assiste é o padrão da conta do Vimeo, que o portal não enxerga. O Vimeo aqui é um servidor local.
 */
class EnvioAoVimeoTest {

    private HttpServer vimeo;

    @AfterEach
    void desligar() {
        if (vimeo != null) {
            vimeo.stop(0);
        }
    }

    /** Sobe um Vimeo de mentira que guarda o corpo recebido e responde com a privacidade dada. */
    private EnvioAoVimeo.Real vimeoQueResponde(String privacidade, AtomicReference<String> recebido) throws Exception {
        vimeo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vimeo.createContext("/me/videos", troca -> {
            recebido.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var resposta = """
                    {"uri": "/videos/123", "link": "https://vimeo.com/123/abc",
                     "player_embed_url": "https://player.vimeo.com/video/123?h=abc",
                     "privacy": {"view": "%s"}}""".formatted(privacidade).getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(201, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        vimeo.start();
        return new EnvioAoVimeo.Real("token-de-teste", "http://127.0.0.1:" + vimeo.getAddress().getPort());
    }

    @Test
    @SuppressWarnings("unchecked")
    void oEnvioPedeAGravacaoComoNaoListada() throws Exception {
        var recebido = new AtomicReference<String>();
        var enviado = vimeoQueResponde("unlisted", recebido)
                .enviar("Aula ao vivo", "descrição", "https://zoom.exemplo/gravacao.mp4");

        var corpo = new ObjectMapper().readValue(recebido.get(), Map.class);
        assertThat((Map<String, Object>) corpo.get("privacy")).containsEntry("view", "unlisted");
        assertThat(enviado.vimeoId()).isEqualTo("123");
        assertThat(enviado.embedUrl()).isEqualTo("https://player.vimeo.com/video/123?h=abc");
    }

    @Test
    void gravacaoQueOVimeoCriouComoPublicaNaoSegueParaOPortal() throws Exception {
        var envio = vimeoQueResponde("anybody", new AtomicReference<>());

        assertThatThrownBy(() -> envio.enviar("Aula ao vivo", "descrição", "https://zoom.exemplo/gravacao.mp4"))
                .isInstanceOf(ServicoExterno.class).hasMessageContaining("anybody");
    }
}
