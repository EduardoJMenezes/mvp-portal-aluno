package br.com.plataforma.taxonomia;

import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Do que o conteúdo trata — a etiqueta, não o endereço.
 *
 * <p>Global de propósito: o mesmo assunto vale para 2025, 2026 e 2027. Por isso o nome
 * <b>nunca</b> carrega numeração de capítulo ("Estequiometria", não "K03 - Estequiometria"):
 * K03 é a posição na apostila de uma turma, e apostilas mudam de um ano para o outro.
 */
@Entity
@Table(name = "subjects")
@SQLRestriction("removido_em IS NULL")
public class Assunto extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    protected Assunto() {}

    Assunto(String nome) {
        this.nome = nome;
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public String getNome() {
        return nome;
    }

    void renomear(String nome) {
        this.nome = nome;
    }
}
