package br.com.plataforma.simulados;

import br.com.plataforma.questoes.Questao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A questão dentro da prova, na posição em que o aluno a vê. */
@Entity
@Table(name = "exam_questions")
public class SimuladoQuestao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "simulado_id", nullable = false)
    private Simulado simulado;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questao_id", nullable = false)
    private Questao questao;

    @Column(nullable = false)
    private Integer ordem;

    protected SimuladoQuestao() {}

    SimuladoQuestao(Simulado simulado, Questao questao, int ordem) {
        this.simulado = simulado;
        this.questao = questao;
        this.ordem = ordem;
    }

    public Simulado getSimulado() {
        return simulado;
    }

    public Questao getQuestao() {
        return questao;
    }

    public Integer getOrdem() {
        return ordem;
    }

    void reordenar(int ordem) {
        this.ordem = ordem;
    }
}
