package br.com.plataforma.questoes;

import br.com.plataforma.taxonomia.Assunto;
import br.com.plataforma.taxonomia.SubAssunto;
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
 * A mesma etiqueta da taxonomia, agora na questão de simulado.
 *
 * <p>É o que liga o erro do aluno ao vídeo que explica aquilo: os dois lados usam a mesma
 * taxonomia.
 */
@Entity
@Table(name = "question_subjects")
public class QuestaoAssunto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questao_id", nullable = false)
    private Questao questao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assunto_id", nullable = false)
    private Assunto assunto;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "subassunto_id")
    private SubAssunto subassunto;

    protected QuestaoAssunto() {}

    QuestaoAssunto(Questao questao, Assunto assunto, SubAssunto subassunto) {
        this.questao = questao;
        this.assunto = assunto;
        this.subassunto = subassunto;
    }

    public Assunto getAssunto() {
        return assunto;
    }

    public SubAssunto getSubassunto() {
        return subassunto;
    }
}
