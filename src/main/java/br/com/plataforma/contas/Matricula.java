package br.com.plataforma.contas;

import br.com.plataforma.catalogo.Turma;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** O aluno na turma. É daqui que sai a segregação: aluno só alcança o que a turma dele tem. */
@Entity
@Table(name = "enrollments")
public class Matricula {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "usuario_id", nullable = false)
    private Integer usuarioId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "turma_id", nullable = false)
    private Turma turma;

    protected Matricula() {}

    Matricula(Integer usuarioId, Turma turma) {
        this.usuarioId = usuarioId;
        this.turma = turma;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUsuarioId() {
        return usuarioId;
    }

    public Turma getTurma() {
        return turma;
    }
}
