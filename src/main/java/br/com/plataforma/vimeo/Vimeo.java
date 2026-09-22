package br.com.plataforma.vimeo;

import java.util.List;

/**
 * Leitura do acervo do Vimeo. Só GET, por construção: nenhuma importação altera coisa alguma lá.
 *
 * <p>Duas implementações: a real e o acervo de demonstração, usado enquanto não há token. Trocar
 * uma pela outra é preencher {@code VIMEO_ACCESS_TOKEN}.
 */
public interface Vimeo {

    record Pasta(String id, String nome, String uri, String paiUri, boolean temSubpasta,
            Integer totalVideos, Integer totalVideosComSubpastas) {}

    /** {@code embedUrl} vai como o Vimeo devolve: vídeo unlisted só toca com o hash que vem nela. */
    record Video(String id, String titulo, String url, String thumbnailUrl, Integer duracaoSegundos,
            String pasta, String descricao, String embedUrl, String status, Boolean reproduzivel,
            String privacidadeView, String privacidadeEmbed, String transcricaoStatus) {

        public boolean publicavel() {
            return Boolean.TRUE.equals(reproduzivel) && "available".equals(status);
        }
    }

    boolean real();

    List<Pasta> listarPastas();

    Pasta obterPasta(String pastaId);

    List<Video> listarVideosDaPasta(String pastaId);

    Video obterVideo(String vimeoId);

    /** A busca livre da tela: numa pasta ou na conta inteira. */
    List<Video> listarVideos(String pastaId, String busca, int limite);
}
