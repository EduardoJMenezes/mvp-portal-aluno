package br.com.plataforma.vimeo;

import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.ServicoExterno;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * A API REST do Vimeo, versão 3.4, com {@code fields} em todo GET — além de encolher o payload,
 * ele dobra a cota de requisições por minuto.
 */
public class VimeoReal implements Vimeo {

    static final String ACEITA = "application/vnd.vimeo.*+json;version=3.4";
    static final int MAXIMO_POR_PAGINA = 100;
    static final int MAXIMO_DE_PAGINAS = 100;

    static final String CAMPOS_PASTA = "uri,name,has_subfolder,metadata.connections.parent_folder,"
            + "metadata.connections.videos.total,metadata.connections.videos.deep_total";
    static final String CAMPOS_VIDEO = "uri,name,description,duration,link,player_embed_url,pictures.sizes,"
            + "status,is_playable,privacy.view,privacy.embed,transcript.status,parent_project";

    private final String token;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public VimeoReal(String token, String base) {
        this.token = token;
        this.base = base.replaceAll("/$", "");
    }

    @Override
    public boolean real() {
        return true;
    }

    // --- transporte ----------------------------------------------------------

    private Map<?, ?> get(String caminho, Map<String, Object> params) {
        var query = new StringBuilder();
        params.forEach((k, v) -> {
            if (v != null) {
                query.append(query.isEmpty() ? "?" : "&").append(k).append('=')
                        .append(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
            }
        });
        var pedido = HttpRequest.newBuilder(URI.create(base + caminho + query))
                .header("Authorization", "bearer " + token)
                .header("Accept", ACEITA)
                .header("User-Agent", "mvp-portal-aluno/0.2")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> resposta;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ServicoExterno(("Não foi possível falar com %s (%s). Verifique se a rede libera "
                    + "api.vimeo.com — filtros corporativos costumam bloquear o domínio.")
                    .formatted(base, e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServicoExterno("Chamada ao Vimeo interrompida.", e);
        }
        var tipo = resposta.headers().firstValue("content-type").orElse("");
        if (resposta.statusCode() == 401) {
            throw new ServicoExterno("Vimeo recusou o token (401). Confira VIMEO_ACCESS_TOKEN e o escopo "
                    + "'private', necessário para ler pastas e vídeos não públicos.");
        }
        if (resposta.statusCode() == 403 && !tipo.contains("json")) {
            throw new ServicoExterno(("A requisição para %s foi barrada por um filtro de rede (resposta 403 "
                    + "em HTML, não da API do Vimeo). Libere api.vimeo.com.").formatted(base));
        }
        if (resposta.statusCode() == 404) {
            throw new NaoEncontrado("O Vimeo não encontrou %s.".formatted(caminho));
        }
        if (resposta.statusCode() >= 400) {
            throw new ServicoExterno("Vimeo respondeu %d em %s: %s".formatted(resposta.statusCode(), caminho,
                    resposta.body() == null ? "" : resposta.body().substring(0, Math.min(200, resposta.body().length()))));
        }
        return resposta.body() == null || resposta.body().isBlank() ? Map.of() : json.readValue(resposta.body(), Map.class);
    }

    /** Segue {@code paging.next} até acabar, ou até o teto: a API não passa de 100 por página. */
    private List<Map<?, ?>> paginar(String caminho, Map<String, Object> params) {
        var todos = new ArrayList<Map<?, ?>>();
        var proximos = new LinkedHashMap<>(params);
        proximos.put("per_page", MAXIMO_POR_PAGINA);
        var pagina = 1;
        while (pagina <= MAXIMO_DE_PAGINAS) {
            proximos.put("page", pagina);
            var corpo = get(caminho, proximos);
            if (corpo.get("data") instanceof List<?> dados) {
                dados.forEach(d -> {
                    if (d instanceof Map<?, ?> m) {
                        todos.add(m);
                    }
                });
            }
            var paging = corpo.get("paging") instanceof Map<?, ?> p ? p.get("next") : null;
            if (paging == null) {
                break;
            }
            pagina++;
        }
        return todos;
    }

    // --- leitura -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Object mergulhar(Map<?, ?> dados, String caminho) {
        Object atual = dados;
        for (var parte : caminho.split("\\.")) {
            if (!(atual instanceof Map<?, ?> m)) {
                return null;
            }
            atual = m.get(parte);
        }
        return atual;
    }

    static String idDoUri(Object uri) {
        if (uri == null) {
            return null;
        }
        var texto = String.valueOf(uri).replaceAll("/$", "");
        return texto.substring(texto.lastIndexOf('/') + 1);
    }

    private static Integer inteiro(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String texto(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    static Pasta pasta(Map<?, ?> d) {
        var pai = mergulhar(d, "metadata.connections.parent_folder");
        var paiUri = pai instanceof Map<?, ?> m ? texto(m.get("uri")) : (pai instanceof String s ? s : null);
        return new Pasta(idDoUri(d.get("uri")), texto(d.get("name")), texto(d.get("uri")), paiUri,
                Boolean.TRUE.equals(d.get("has_subfolder")),
                inteiro(mergulhar(d, "metadata.connections.videos.total")),
                inteiro(mergulhar(d, "metadata.connections.videos.deep_total")));
    }

    static Video video(Map<?, ?> d, String pasta) {
        String thumb = null;
        if (mergulhar(d, "pictures.sizes") instanceof List<?> tamanhos && !tamanhos.isEmpty()
                && tamanhos.getLast() instanceof Map<?, ?> maior) {
            thumb = texto(maior.get("link"));
        }
        return new Video(idDoUri(d.get("uri")), d.get("name") == null ? "(sem título)" : texto(d.get("name")),
                texto(d.get("link")), thumb, inteiro(d.get("duration")), pasta, texto(d.get("description")),
                texto(d.get("player_embed_url")), texto(d.get("status")),
                d.get("is_playable") instanceof Boolean b ? b : null,
                texto(mergulhar(d, "privacy.view")), texto(mergulhar(d, "privacy.embed")),
                texto(mergulhar(d, "transcript.status")));
    }

    @Override
    public List<Pasta> listarPastas() {
        return paginar("/me/projects", Map.of("fields", CAMPOS_PASTA)).stream().map(VimeoReal::pasta).toList();
    }

    @Override
    public Pasta obterPasta(String pastaId) {
        return pasta(get("/me/projects/" + pastaId, Map.of("fields", CAMPOS_PASTA)));
    }

    @Override
    public List<Video> listarVideosDaPasta(String pastaId) {
        return paginar("/me/projects/" + pastaId + "/videos", Map.of("fields", CAMPOS_VIDEO)).stream()
                .map(d -> video(d, null)).toList();
    }

    @Override
    public Video obterVideo(String vimeoId) {
        return video(get("/videos/" + vimeoId, Map.of("fields", CAMPOS_VIDEO)), null);
    }

    @Override
    public List<Video> listarVideos(String pastaId, String busca, int limite) {
        var params = new LinkedHashMap<String, Object>();
        params.put("fields", CAMPOS_VIDEO);
        params.put("per_page", Math.min(limite, MAXIMO_POR_PAGINA));
        params.put("query", busca == null || busca.isBlank() ? null : busca);
        String nomeDaPasta = null;
        String caminho = "/me/videos";
        if (pastaId != null && !pastaId.isBlank()) {
            caminho = "/me/projects/" + pastaId + "/videos";
            nomeDaPasta = listarPastas().stream().filter(p -> pastaId.equals(p.id())).map(Pasta::nome)
                    .findFirst().orElse(null);
        }
        var corpo = get(caminho, params);
        var saida = new ArrayList<Video>();
        if (corpo.get("data") instanceof List<?> dados) {
            for (var d : dados) {
                if (d instanceof Map<?, ?> m) {
                    saida.add(video(m, nomeDaPasta));
                }
            }
        }
        return saida;
    }
}
