package br.com.plataforma.exercicios;

import br.com.plataforma.questoes.Letra;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * O que o aluno marcou numa questão da aula. Uma por aluno por linha: marcou, valeu.
 *
 * <p>Guarda a alternativa, não só o acerto: é a alternativa errada que diz o que o aluno
 * confundiu. Linha e questão vão como id, e não como associação — as duas têm remoção lógica, e a
 * resposta tem que sobreviver à remoção delas.
 */
@Entity
@Table(name = "item_answers")
public class RespostaDeExercicio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "item_id", nullable = false)
    private Integer itemId;

    @Column(name = "aluno_id", nullable = false)
    private Integer alunoId;

    @Column(name = "questao_id", nullable = false)
    private Integer questaoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alternativa_marcada", nullable = false)
    private Letra alternativaMarcada;

    @Column(nullable = false)
    private boolean correta;

    @Column(name = "respondido_em", nullable = false)
    private Instant respondidoEm;

    protected RespostaDeExercicio() {}

    RespostaDeExercicio(Integer itemId, Integer alunoId, Integer questaoId, Letra alternativaMarcada,
            boolean correta, Instant respondidoEm) {
        this.itemId = itemId;
        this.alunoId = alunoId;
        this.questaoId = questaoId;
        this.alternativaMarcada = alternativaMarcada;
        this.correta = correta;
        this.respondidoEm = respondidoEm;
    }

    public Integer getItemId() {
        return itemId;
    }

    public Integer getAlunoId() {
        return alunoId;
    }

    public Integer getQuestaoId() {
        return questaoId;
    }

    public Letra getAlternativaMarcada() {
        return alternativaMarcada;
    }

    public boolean isCorreta() {
        return correta;
    }

    public Instant getRespondidoEm() {
        return respondidoEm;
    }
}
