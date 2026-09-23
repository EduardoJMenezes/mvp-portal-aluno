package br.com.plataforma.aulas;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AulaRepositorio extends JpaRepository<Aula, Integer> {

    List<Aula> findAllByOrderByInicioEmDesc();

    Optional<Aula> findFirstByZoomMeetingId(String zoomMeetingId);

    /** Só um aviso sobe a gravação: quem marca primeiro leva. O Zoom reenvia avisos. */
    @Modifying
    @Query(value = "UPDATE live_classes SET gravacao_vimeo_id = 'enviando' WHERE id = :id AND gravacao_vimeo_id IS NULL",
            nativeQuery = true)
    int reservarGravacao(Integer id);

    @Modifying
    @Query(value = "UPDATE live_classes SET gravacao_vimeo_id = NULL WHERE id = :id AND gravacao_vimeo_id = 'enviando'",
            nativeQuery = true)
    int liberarGravacao(Integer id);
}

interface AulaPresencaRepositorio extends JpaRepository<AulaPresenca, Integer> {

    Optional<AulaPresenca> findByAulaIdAndUsuarioId(Integer aulaId, Integer usuarioId);

    @Modifying
    @Query("delete from AulaPresenca p where p.aulaId = :aulaId")
    void apagarDaAula(Integer aulaId);

    List<AulaPresenca> findByAulaId(Integer aulaId);

    /** O participante do Zoom vira aluno pelo e-mail com que o portal o inscreveu. */
    @Query("select p from AulaPresenca p, br.com.plataforma.contas.Usuario u "
            + "where p.usuarioId = u.id and p.aulaId = :aulaId and lower(u.email) = lower(:email)")
    Optional<AulaPresenca> daAulaPorEmail(Integer aulaId, String email);
}
