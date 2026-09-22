package br.com.plataforma.vimeo;

import br.com.plataforma.comum.NaoEncontrado;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Acervo fixo, no formato exato da API, para a POC rodar sem credencial. Os títulos seguem o padrão
 * do acervo real (capítulo + número da questão), porque é isso que o agente lê para propor.
 */
public class VimeoDemo implements Vimeo {

    private final Map<String, List<Video>> pastas = new LinkedHashMap<>();

    public VimeoDemo() {
        pastas.put("Atomística", demo("Atomística", List.of(
                Map.entry("910000101", "Atomística — Questão 01 — Modelo de Rutherford"),
                Map.entry("910000102", "Atomística — Questão 02 — Distribuição eletrônica"),
                Map.entry("910000103", "Atomística — Questão 03 — Isótopos e isóbaros"))));
        pastas.put("Estequiometria", demo("Estequiometria", List.of(
                Map.entry("920000201", "Estequiometria — Questão 01 — Balanceamento"),
                Map.entry("920000202", "Estequiometria — Questão 02 — Mol e massa molar"),
                Map.entry("920000203", "Estequiometria — Questão 03 — Reagente limitante"),
                Map.entry("920000204", "Estequiometria — Questão 04 — Rendimento de reação"),
                Map.entry("920000205", "Estequiometria — Questão 05 — Pureza de reagentes"))));
        pastas.put("Cinética", demo("Cinética", List.of(
                Map.entry("930000301", "Cinética — Questão 01 — Velocidade média"),
                Map.entry("930000302", "Cinética — Questão 02 — Fatores que alteram a velocidade"),
                Map.entry("930000303", "Cinética — Questão 03 — Energia de ativação"))));
    }

    private static List<Video> demo(String pasta, List<Map.Entry<String, String>> ids) {
        var saida = new ArrayList<Video>();
        for (int i = 0; i < ids.size(); i++) {
            var vid = ids.get(i).getKey();
            var titulo = ids.get(i).getValue();
            saida.add(new Video(vid, titulo, "https://vimeo.com/" + vid, null, 300 + i * 37, pasta,
                    "Resolução em vídeo — " + titulo, "https://player.vimeo.com/video/" + vid,
                    "available", true, "unlisted", "public", null));
        }
        return saida;
    }

    @Override
    public boolean real() {
        return false;
    }

    @Override
    public List<Pasta> listarPastas() {
        var saida = new ArrayList<Pasta>();
        var i = 1;
        for (var e : pastas.entrySet()) {
            var id = "demo-" + i++;
            saida.add(new Pasta(id, e.getKey(), "/me/projects/" + id, null, false, e.getValue().size(),
                    e.getValue().size()));
        }
        return saida;
    }

    /** Aceita o id ("demo-2") ou o nome: quem chama costuma ser um modelo repetindo o professor. */
    private String nomeDaPasta(String pastaId) {
        var achada = listarPastas().stream().filter(p -> p.id().equals(pastaId)).map(Pasta::nome).findFirst();
        return achada.orElseGet(() -> pastas.keySet().stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(pastaId == null ? "" : pastaId.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null));
    }

    @Override
    public Pasta obterPasta(String pastaId) {
        var nome = nomeDaPasta(pastaId);
        return listarPastas().stream().filter(p -> p.nome().equals(nome)).findFirst()
                .orElseThrow(() -> new NaoEncontrado("A pasta '%s' não existe no acervo de demonstração."
                        .formatted(pastaId)));
    }

    @Override
    public List<Video> listarVideosDaPasta(String pastaId) {
        return pastas.getOrDefault(obterPasta(pastaId).nome(), List.of());
    }

    @Override
    public Video obterVideo(String vimeoId) {
        return pastas.values().stream().flatMap(List::stream).filter(v -> v.id().equals(vimeoId)).findFirst()
                .orElseThrow(() -> new NaoEncontrado("O vídeo %s não existe no acervo de demonstração.".formatted(vimeoId)));
    }

    @Override
    public List<Video> listarVideos(String pastaId, String busca, int limite) {
        List<Video> videos;
        if (pastaId != null && !pastaId.isBlank()) {
            var nome = nomeDaPasta(pastaId);
            videos = nome == null ? List.of() : pastas.get(nome);
        } else {
            videos = pastas.values().stream().flatMap(List::stream).toList();
        }
        if (busca != null && !busca.isBlank()) {
            var termo = busca.toLowerCase(Locale.ROOT);
            videos = videos.stream().filter(v -> v.titulo().toLowerCase(Locale.ROOT).contains(termo)).toList();
        }
        return videos.stream().limit(limite).toList();
    }
}
