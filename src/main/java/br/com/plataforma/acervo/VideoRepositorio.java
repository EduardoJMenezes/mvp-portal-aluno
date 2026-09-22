package br.com.plataforma.acervo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VideoRepositorio extends JpaRepository<Video, Integer> {

    List<Video> findByPastaVimeoOrderByTituloAsc(String pastaVimeo);

    /**
     * Acha o vídeo <b>inclusive removido</b>, de propósito.
     *
     * <p>O unique de {@code vimeo_id} é total, não parcial: reimportar um vídeo removido tem que
     * ressuscitá-lo, não inserir uma segunda linha. Nativa porque é o único jeito de escapar do
     * {@code @SQLRestriction} — e é o preço que ele cobra, documentado.
     */
    @Query(value = "SELECT * FROM videos WHERE vimeo_id = :vimeoId", nativeQuery = true)
    Optional<Video> acharMesmoRemovido(String vimeoId);

    @Query("select v.vimeoId from Video v where v.vimeoId in :vimeoIds")
    List<String> quaisJaExistem(List<String> vimeoIds);
}
