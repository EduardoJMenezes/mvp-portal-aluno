package br.com.plataforma.taxonomia;

import br.com.plataforma.acervo.Video;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VideoAssuntoRepositorio extends JpaRepository<VideoAssunto, Integer> {

    Optional<VideoAssunto> findFirstByVideoAndAssuntoAndSubassuntoIsNull(Video video, Assunto assunto);

    Optional<VideoAssunto> findFirstByVideoAndAssuntoAndSubassunto(
            Video video, Assunto assunto, SubAssunto subassunto);

    /** Só os vínculos cujo assunto ainda existe; ver a nota em QuestaoAssuntoRepositorio. */
    @Query("""
            select va from VideoAssunto va
              join fetch va.assunto
              left join fetch va.subassunto
             where va.video = :video""")
    List<VideoAssunto> vivosDo(Video video);

    @Query("select va.video from VideoAssunto va where va.subassunto.id = :subassuntoId order by va.video.id")
    List<Video> videosDoSubassunto(Integer subassuntoId, org.springframework.data.domain.Limit limite);

    @Query("select va.video from VideoAssunto va where va.assunto.id = :assuntoId order by va.video.id")
    List<Video> videosDoAssunto(Integer assuntoId, org.springframework.data.domain.Limit limite);
}
