package br.com.plataforma.estrutura;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * O capítulo como a turma o enxerga: "K01 - Introdução à química orgânica".
 *
 * <p>O {@code @SQLRestriction} é o que no Python é o {@code selecionar()}: toda consulta a esta
 * entidade já sai sem o removido. Lá é disciplina de quem escreve a consulta; aqui é o mapeador.
 */
@Entity
@Table(name = "modules")
@SQLRestriction("removido_em IS NULL")
public class Modulo extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "turma_id", nullable = false)
    private Turma turma;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Integer ordem;

    protected Modulo() {}

    /** Package-private: módulo só nasce pelo {@link EstruturaServico}, que aplica as regras. */
    Modulo(Turma turma, String nome, int ordem) {
        this.turma = turma;
        this.nome = nome;
        this.ordem = ordem;
    }

    @Override
    public Integer getId() {
        return id;
    }

    public Turma getTurma() {
        return turma;
    }

    @Override
    public String getNome() {
        return nome;
    }

    public Integer getOrdem() {
        return ordem;
    }

    void renomear(String nome) {
        this.nome = nome;
    }

    void reordenar(int ordem) {
        this.ordem = ordem;
    }
}
