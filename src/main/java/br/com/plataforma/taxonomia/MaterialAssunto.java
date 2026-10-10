package br.com.plataforma.taxonomia;

import br.com.plataforma.materiais.Material;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/** Etiqueta de um material (PDF): a mesma ligação do vídeo ({@link VideoAssunto}), para o mesmo fim. */
@Entity
@Table(name = "material_subjects")
public class MaterialAssunto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assunto_id", nullable = false)
    private Assunto assunto;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "subassunto_id")
    private SubAssunto subassunto;

    protected MaterialAssunto() {}

    MaterialAssunto(Material material, Assunto assunto, SubAssunto subassunto) {
        this.material = material;
        this.assunto = assunto;
        this.subassunto = subassunto;
    }

    public Integer getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    public Assunto getAssunto() {
        return assunto;
    }

    public SubAssunto getSubassunto() {
        return subassunto;
    }
}
