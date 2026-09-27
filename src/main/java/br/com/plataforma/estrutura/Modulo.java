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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import java.util.ArrayList;
import java.util.List;
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

    /** As turmas que recebem o módulo (decisão 0011): ele mora na biblioteca, não numa turma. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "module_classes",
            joinColumns = @JoinColumn(name = "modulo_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Integer ordem;

    private String categoria;

    protected Modulo() {}

    /** Package-private: módulo só nasce pelo {@link EstruturaServico}, que aplica as regras. */
    Modulo(String nome, int ordem) {
        this.nome = nome;
        this.ordem = ordem;
    }

    @Override
    public Integer getId() {
        return id;
    }

    public List<Turma> getTurmas() {
        return turmas;
    }

    public boolean eDa(Turma turma) {
        return turmas.stream().anyMatch(t -> t.getId().equals(turma.getId()));
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

    public String getCategoria() {
        return categoria;
    }

    void mudarCategoria(String categoria) {
        this.categoria = categoria;
    }
}
