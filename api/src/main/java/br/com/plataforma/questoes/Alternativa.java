package br.com.plataforma.questoes;

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

@Entity
@Table(name = "question_options")
public class Alternativa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questao_id", nullable = false)
    private Questao questao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Letra letra;

    @Column(nullable = false)
    private String texto;

    protected Alternativa() {}

    Alternativa(Questao questao, Letra letra, String texto) {
        this.questao = questao;
        this.letra = letra;
        this.texto = texto;
    }

    public Letra getLetra() {
        return letra;
    }

    public String getTexto() {
        return texto;
    }

    void mudarTexto(String texto) {
        this.texto = texto;
    }
}
