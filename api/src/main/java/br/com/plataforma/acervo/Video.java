package br.com.plataforma.acervo;

import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/** Um vídeo do acervo do Vimeo. O mesmo vídeo serve a várias turmas. */
@Entity
@Table(name = "videos")
@SQLRestriction("removido_em IS NULL")
public class Video extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "vimeo_id", nullable = false)
    private String vimeoId;

    @Column(nullable = false)
    private String titulo;

    private String url;

    @Column(name = "embed_url")
    private String embedUrl;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "duracao_segundos")
    private Integer duracaoSegundos;

    @Column(name = "pasta_vimeo")
    private String pastaVimeo;

    protected Video() {}

    Video(String vimeoId, String titulo, String url, String embedUrl, String thumbnailUrl,
            Integer duracaoSegundos, String pastaVimeo) {
        this.vimeoId = vimeoId;
        this.titulo = titulo;
        this.url = url;
        this.embedUrl = embedUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.duracaoSegundos = duracaoSegundos;
        this.pastaVimeo = pastaVimeo;
    }

    @Override
    public Integer getId() {
        return id;
    }

    /** O nome pelo qual se procura um vídeo é o título dele. */
    @Override
    public String getNome() {
        return titulo;
    }

    public String getVimeoId() {
        return vimeoId;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getUrl() {
        return url;
    }

    public String getEmbedUrl() {
        return embedUrl;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public Integer getDuracaoSegundos() {
        return duracaoSegundos;
    }

    public String getPastaVimeo() {
        return pastaVimeo;
    }

    void mudarEmbedUrl(String embedUrl) {
        this.embedUrl = embedUrl;
    }
}
