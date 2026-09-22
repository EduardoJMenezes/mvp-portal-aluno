package br.com.plataforma.estrutura;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import br.com.plataforma.comum.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Uma linha na lista do aluno — hoje, sempre um vídeo.
 *
 * <p>{@code nome} é a identidade editorial ("Q04", "Aula 1 — cadeias carbônicas") e {@code ordem}
 * é a posição na tela. São coisas diferentes de propósito: juntas, impediriam exibir a Q52 antes
 * da Q04.
 *
 * <p>É nesta linha que vive o {@code status}: publicar é item a item.
 */
@Entity
@Table(name = "items")
@SQLRestriction("removido_em IS NULL")
public class Item extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submodulo_id", nullable = false)
    private SubModulo submodulo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Integer ordem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "rascunho_id")
    private Integer rascunhoId;

    protected Item() {}

    Item(SubModulo submodulo, Video video, String nome, int ordem, Status status, Integer rascunhoId) {
        this.submodulo = submodulo;
        this.video = video;
        this.nome = nome;
        this.ordem = ordem;
        this.status = status;
        this.rascunhoId = rascunhoId;
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public String getNome() {
        return nome;
    }

    public SubModulo getSubmodulo() {
        return submodulo;
    }

    public Video getVideo() {
        return video;
    }

    public Integer getOrdem() {
        return ordem;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getRascunhoId() {
        return rascunhoId;
    }

    void renomear(String nome) {
        this.nome = nome;
    }

    void reordenar(int ordem) {
        this.ordem = ordem;
    }

    void mudarDeSubmodulo(SubModulo destino, int ordem) {
        this.submodulo = destino;
        this.ordem = ordem;
    }

    void publicar() {
        this.status = Status.PUBLICADO;
    }
}
