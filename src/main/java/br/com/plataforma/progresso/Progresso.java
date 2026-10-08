package br.com.plataforma.progresso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * O que um aluno já viu de um item do curso (vídeo ou PDF): onde parou e se concluiu.
 *
 * <p>Item e aluno vão como id, e não como associação: o item tem remoção lógica, e o registro do
 * que o aluno assistiu tem que sobreviver a ela. Quem grava é o repositório, num upsert — dois
 * avisos do player chegando juntos não podem virar erro.
 */
@Entity
@Table(name = "item_progress")
public class Progresso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "item_id", nullable = false)
    private Integer itemId;

    @Column(name = "aluno_id", nullable = false)
    private Integer alunoId;

    @Column(name = "posicao_segundos", nullable = false)
    private Integer posicaoSegundos;

    @Column(name = "duracao_segundos")
    private Integer duracaoSegundos;

    @Column(name = "concluido_em")
    private Instant concluidoEm;

    @Column(name = "visto_em", nullable = false)
    private Instant vistoEm;

    protected Progresso() {}

    public Integer getItemId() {
        return itemId;
    }

    public Integer getAlunoId() {
        return alunoId;
    }

    public Integer getPosicaoSegundos() {
        return posicaoSegundos;
    }

    public Integer getDuracaoSegundos() {
        return duracaoSegundos;
    }

    public Instant getConcluidoEm() {
        return concluidoEm;
    }

    public Instant getVistoEm() {
        return vistoEm;
    }

    public boolean concluido() {
        return concluidoEm != null;
    }
}
