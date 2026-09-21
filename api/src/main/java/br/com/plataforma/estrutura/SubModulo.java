package br.com.plataforma.estrutura;

import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
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
import org.hibernate.annotations.SQLRestriction;

/** A seção dentro do módulo: "Aulas", "Questões da apostila". */
@Entity
@Table(name = "submodules")
@SQLRestriction("removido_em IS NULL")
public class SubModulo extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "modulo_id", nullable = false)
    private Modulo modulo;

    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoSubModulo tipo;

    @Column(nullable = false)
    private Integer ordem;

    protected SubModulo() {}

    SubModulo(Modulo modulo, String nome, TipoSubModulo tipo, int ordem) {
        this.modulo = modulo;
        this.nome = nome;
        this.tipo = tipo;
        this.ordem = ordem;
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public String getNome() {
        return nome;
    }

    public TipoSubModulo getTipo() {
        return tipo;
    }

    public Integer getOrdem() {
        return ordem;
    }

    public Modulo getModulo() {
        return modulo;
    }

    void renomear(String nome) {
        this.nome = nome;
    }

    void reordenar(int ordem) {
        this.ordem = ordem;
    }
}
