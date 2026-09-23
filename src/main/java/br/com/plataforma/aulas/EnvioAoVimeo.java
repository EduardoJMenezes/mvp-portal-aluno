package br.com.plataforma.aulas;

import br.com.plataforma.comum.ServicoExterno;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.ObjectMapper;

/**
 * A gravação da aula indo para o Vimeo, no modo <b>pull</b>: o Vimeo busca o arquivo direto no
 * Zoom, servidor a servidor, e nenhum byte passa por aqui.
 *
 * <p>Fica fora de {@code br.com.plataforma.vimeo}, que é só leitura por construção: esta é a única
 * escrita que a plataforma faz no Vimeo, e só a gravação de uma aula nossa chega aqui.
 */
public interface EnvioAoVimeo {

    record Enviado(String vimeoId, String url, String embedUrl) {}

    Enviado enviar(String titulo, String descricao, String linkDoArquivo);

    final class Real implements EnvioAoVimeo {

        private final String token;
        private final String base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final ObjectMapper json = new ObjectMapper();

        Real(String token, String base) {
            this.token = token;
            this.base = base.replaceAll("/$", "");
        }

        @Override
        public Enviado enviar(String titulo, String descricao, String linkDoArquivo) {
            var corpo = Map.of(
                    "upload", Map.of("approach", "pull", "link", linkDoArquivo),
                    "name", titulo,
                    "description", descricao);
            var pedido = HttpRequest.newBuilder(URI.create(base + "/me/videos?fields=uri,link,player_embed_url"))
                    .header("Authorization", "bearer " + token)
                    .header("Accept", "application/vnd.vimeo.*+json;version=3.4")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
            HttpResponse<String> resposta;
            try {
                resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new ServicoExterno("Não foi possível falar com o Vimeo: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServicoExterno("Envio ao Vimeo interrompido.", e);
            }
            if (resposta.statusCode() >= 400) {
                var texto = resposta.body() == null ? "" : resposta.body();
                throw new ServicoExterno("O Vimeo recusou a gravação (%d): %s"
                        .formatted(resposta.statusCode(), texto.substring(0, Math.min(300, texto.length()))));
            }
            var video = json.readValue(resposta.body(), Map.class);
            var uri = String.valueOf(video.get("uri"));
            return new Enviado(uri.substring(uri.lastIndexOf('/') + 1), String.valueOf(video.get("link")),
                    String.valueOf(video.get("player_embed_url")));
        }
    }

    /** Guarda o que recebeu, para os testes conferirem o link que iria ao Vimeo. */
    final class DeMentira implements EnvioAoVimeo {

        public final List<String> links = new CopyOnWriteArrayList<>();

        @Override
        public Enviado enviar(String titulo, String descricao, String linkDoArquivo) {
            links.add(linkDoArquivo);
            var id = String.valueOf(990_000_000 + links.size());
            return new Enviado(id, "https://vimeo.com/" + id, "https://player.vimeo.com/video/" + id);
        }
    }
}
