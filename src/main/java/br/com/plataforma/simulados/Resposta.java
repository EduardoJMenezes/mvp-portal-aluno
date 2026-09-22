package br.com.plataforma.simulados;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** O que o aluno marcou numa questão, e se acertou. */
@Entity
@Table(name = "exam_answers")
public class Resposta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tentativa_id", nullable = false)
    private Tentativa tentativa;

    @Column(name = "questao_id", nullable = false)
    private Integer questaoId;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "alternativa_marcada", nullable = false)
    private br.com.plataforma.questoes.Letra alternativaMarcada;

    @Column(nullable = false)
    private boolean correta;

    @Column(name = "respondido_em", nullable = false)
    private java.time.Instant respondidoEm;

    protected Resposta() {}

    Resposta(Tentativa tentativa, Integer questaoId, br.com.plataforma.questoes.Letra marcada,
            boolean correta, java.time.Instant agora) {
        this.tentativa = tentativa;
        this.questaoId = questaoId;
        this.alternativaMarcada = marcada;
        this.correta = correta;
        this.respondidoEm = agora;
    }

    void marcar(br.com.plataforma.questoes.Letra marcada, boolean correta, java.time.Instant agora) {
        this.alternativaMarcada = marcada;
        this.correta = correta;
        this.respondidoEm = agora;
    }

    public Integer getQuestaoId() {
        return questaoId;
    }

    public br.com.plataforma.questoes.Letra getAlternativaMarcada() {
        return alternativaMarcada;
    }

    public boolean isCorreta() {
        return correta;
    }
}
