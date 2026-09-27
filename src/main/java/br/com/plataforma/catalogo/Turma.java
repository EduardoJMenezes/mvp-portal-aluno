package br.com.plataforma.catalogo;

import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "classes")
@SQLRestriction("removido_em IS NULL")
public class Turma extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    protected Turma() {}

    Turma(String nome) {
        this.nome = nome;
    }

    void renomear(String nome) {
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
}
