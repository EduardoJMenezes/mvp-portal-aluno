package br.com.plataforma.materiais;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Rastreavel;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.Usuario;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;

/**
 * PDF que o professor publica: apostila, lista de exercícios, gabarito.
 *
 * <p>Os bytes <b>não</b> são mapeados de propósito: listar vinte materiais traria meio giga de PDF
 * junto. Quem lê e grava o arquivo é o {@link MateriaisServico}, por SQL nativo, em fatias — a
 * coluna é {@code EXTERNAL} no Postgres e o {@code substring} devolve só a faixa pedida.
 */
@Entity
@Table(name = "materials")
@SQLRestriction("removido_em IS NULL")
public class Material extends Rastreavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String titulo;

    @Column(name = "arquivo_nome")
    private String arquivoNome;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false)
    private Integer tamanho;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "criado_por_id", nullable = false)
    private Integer criadoPorId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "material_classes",
            joinColumns = @JoinColumn(name = "material_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "material_students",
            joinColumns = @JoinColumn(name = "material_id"),
            inverseJoinColumns = @JoinColumn(name = "usuario_id"))
    @OrderBy("nome")
    private List<Usuario> alunos = new ArrayList<>();

    protected Material() {}

    public Integer getId() {
        return id;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getArquivoNome() {
        return arquivoNome;
    }

    public String getTipo() {
        return tipo;
    }

    public Integer getTamanho() {
        return tamanho;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getPublicadoEm() {
        return publicadoEm;
    }

    public List<Turma> getTurmas() {
        return turmas;
    }

    public List<Usuario> getAlunos() {
        return alunos;
    }

    void mudarTitulo(String titulo) {
        this.titulo = titulo;
    }

    void mudarStatus(Status status, Instant agora) {
        this.status = status;
        this.publicadoEm = status == Status.PUBLICADO ? agora : null;
    }
}
