package br.com.plataforma.acervo;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** O acervo de vídeos. Quem os traz do Vimeo é a importação; aqui eles só existem. */
@Service
public class AcervoServico {

    private final VideoRepositorio videos;

    public AcervoServico(VideoRepositorio videos) {
        this.videos = videos;
    }

    @Transactional(readOnly = true)
    public Video exigir(Integer id) {
        return videos.findById(id)
                .orElseThrow(() -> new NaoEncontrado("Vídeo %d não existe no acervo.".formatted(id)));
    }

    /** Os vídeos pelo id, num mapa — a tela do aluno pergunta por dezenas de uma vez. */
    @Transactional(readOnly = true)
    public java.util.Map<Integer, Video> porIds(java.util.Collection<Integer> ids) {
        var mapa = new java.util.LinkedHashMap<Integer, Video>();
        if (!ids.isEmpty()) {
            videos.findAllById(ids).forEach(v -> mapa.put(v.getId(), v));
        }
        return mapa;
    }

    /** Quais destes vídeos o acervo já tem — a simulação da importação compara com isto. */
    @Transactional(readOnly = true)
    public List<String> quaisJaExistem(List<String> vimeoIds) {
        return vimeoIds.isEmpty() ? List.of() : videos.quaisJaExistem(vimeoIds).stream().sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<Video> daPasta(String pasta) {
        return videos.findByPastaVimeoOrderByTituloAsc(pasta);
    }

    /**
     * Espelha o vídeo do Vimeo localmente, sem duplicar.
     *
     * <p>Um vídeo removido e reimportado <b>volta à vida</b> em vez de virar uma segunda linha:
     * {@code vimeo_id} é identidade externa, não nome editável.
     */
    @Transactional
    public Video registrar(Identidade ident, String vimeoId, String titulo, String url, String embedUrl,
            String thumbnailUrl, Integer duracaoSegundos, String pastaVimeo) {
        ident.exigirOperador();

        var existente = videos.acharMesmoRemovido(vimeoId);
        if (existente.isPresent()) {
            var video = existente.get();
            video.restaurar(ident);
            if (embedUrl != null && !embedUrl.isBlank()) {
                video.mudarEmbedUrl(embedUrl);
            }
            return videos.save(video);
        }

        var video = new Video(vimeoId, titulo,
                url == null || url.isBlank() ? "https://vimeo.com/" + vimeoId : url,
                embedUrl, thumbnailUrl, duracaoSegundos, pastaVimeo);
        video.tocar(ident);
        return videos.save(video);
    }

    /** Carimba o vídeo quando algo dele muda fora desta entidade — a classificação, por exemplo. */
    @Transactional
    public void tocar(Identidade ident, Video video) {
        video.tocar(ident);
        videos.save(video);
    }
}
