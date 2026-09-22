package br.com.plataforma.simulados;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Nomeavel;
import br.com.plataforma.comum.Rastreavel;
import br.com.plataforma.comum.Status;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;

/**
 * A prova: uma janela só ({@code abreEm} → {@code fechaEm}) e um tempo de prova.
 *
 * <p>Vale para uma ou mais turmas, com um ranking só entre os participantes de todas elas. A
 * agenda é opcional no rascunho e obrigatória para publicar.
 */
@Entity
@Table(name = "exams")
@SQLRestriction("removido_em IS NULL")
public class Simulado extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String titulo;

    @Column(name = "abre_em")
    private Instant abreEm;

    @Column(name = "fecha_em")
    private Instant fechaEm;

    @Column(name = "duracao_minutos")
    private Integer duracaoMinutos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "rascunho_id")
    private Integer rascunhoId;

    @Column(name = "criado_por_id", nullable = false)
    private Integer criadoPorId;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    /** Quem preenche é o DEFAULT do banco; aqui só se lê, para ordenar a listagem. */
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "exam_classes",
            joinColumns = @JoinColumn(name = "simulado_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    @OneToMany(mappedBy = "simulado", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem")
    private List<SimuladoQuestao> questoes = new ArrayList<>();

    protected Simulado() {}

    Simulado(String titulo, Instant abreEm, Instant fechaEm, Integer duracaoMinutos,
            Status status, Integer rascunhoId, Integer criadoPorId) {
        this.titulo = titulo;
        this.abreEm = abreEm;
        this.fechaEm = fechaEm;
        this.duracaoMinutos = duracaoMinutos;
        this.status = status;
        this.rascunhoId = rascunhoId;
        this.criadoPorId = criadoPorId;
    }

    @Override
    public Integer getId() {
        return id;
    }

    /** O nome pelo qual se procura um simulado é o título dele. */
    @Override
    public String getNome() {
        return titulo;
    }

    public String getTitulo() {
        return titulo;
    }

    public Instant getAbreEm() {
        return abreEm;
    }

    public Instant getFechaEm() {
        return fechaEm;
    }

    public Integer getDuracaoMinutos() {
        return duracaoMinutos;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getRascunhoId() {
        return rascunhoId;
    }

    public Integer getCriadoPorId() {
        return criadoPorId;
    }

    public Instant getPublicadoEm() {
        return publicadoEm;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public List<Turma> getTurmas() {
        return turmas;
    }

    public List<SimuladoQuestao> getQuestoes() {
        return questoes;
    }

    void mudarTitulo(String titulo) {
        this.titulo = titulo;
    }

    void mudarJanela(Instant abreEm, Instant fechaEm) {
        this.abreEm = abreEm;
        this.fechaEm = fechaEm;
    }

    void mudarDuracao(Integer minutos) {
        this.duracaoMinutos = minutos;
    }

    void publicar(Instant quando) {
        this.status = Status.PUBLICADO;
        this.publicadoEm = quando;
    }
}
