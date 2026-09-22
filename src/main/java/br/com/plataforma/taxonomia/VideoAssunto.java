package br.com.plataforma.taxonomia;

import br.com.plataforma.acervo.Video;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Etiqueta de um vídeo. Sub-assunto vazio = classificado só no nível do assunto, que basta para a
 * recomendação grossa.
 *
 * <p>Não é {@code Rastreavel}: o vínculo é fato, não conteúdo editável. Quem carrega o rastro é o
 * vídeo.
 */
@Entity
@Table(name = "video_subjects")
public class VideoAssunto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assunto_id", nullable = false)
    private Assunto assunto;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "subassunto_id")
    private SubAssunto subassunto;

    protected VideoAssunto() {}

    VideoAssunto(Video video, Assunto assunto, SubAssunto subassunto) {
        this.video = video;
        this.assunto = assunto;
        this.subassunto = subassunto;
    }

    public Integer getId() {
        return id;
    }

    public Video getVideo() {
        return video;
    }

    public Assunto getAssunto() {
        return assunto;
    }

    public SubAssunto getSubassunto() {
        return subassunto;
    }
}
