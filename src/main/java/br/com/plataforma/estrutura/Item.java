package br.com.plataforma.estrutura;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.Table;
import br.com.plataforma.materiais.Material;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.SQLRestriction;

/**
 * Uma linha na lista do aluno: um vídeo, um PDF, ou os dois — a gravação da aula ao vivo leva o
 * material da aula junto (decisão 0013).
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    private Video video;

    // Material removido chega como null: a linha perde o PDF, e a de só PDF some da tela.
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "material_id")
    private Material material;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Integer ordem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "rascunho_id")
    private Integer rascunhoId;

    /** Vazia: o item aparece para toda turma que tem o módulo. Com turmas: só para elas. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "item_classes",
            joinColumns = @JoinColumn(name = "item_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

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

    public Material getMaterial() {
        return material;
    }

    void anexarMaterial(Material material) {
        this.material = material;
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

    public List<Turma> getTurmas() {
        return turmas;
    }

    /** Aula restrita aparece só para as turmas dela; sem restrição, para quem tem o módulo. */
    public boolean visivelPara(Turma turma) {
        return turmas.isEmpty()
                ? submodulo.getModulo() != null && submodulo.getModulo().eDa(turma)
                : turmas.stream().anyMatch(t -> t.getId().equals(turma.getId()));
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
