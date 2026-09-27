package br.com.plataforma.agenda;

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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;

/**
 * Um evento da agenda (decisão 0012): dia e hora, as turmas para as quais vale e, se quiser, uma
 * ligação — aula, módulo, aula ao vivo ou simulado. Aula e módulo ligados ficam escondidos para
 * aquelas turmas até a hora do evento.
 *
 * <p>As ligações são ids soltos, não associações: o conteúdo removido depois não derruba a agenda.
 */
@Entity
@Table(name = "agenda_events")
@SQLRestriction("removido_em IS NULL")
public class Evento extends Rastreavel implements Nomeavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String titulo;

    private String descricao;

    @Column(name = "inicio_em", nullable = false)
    private Instant inicioEm;

    @Column(name = "fim_em")
    private Instant fimEm;

    private String categoria;

    @Column(name = "modulo_id")
    private Integer moduloId;

    @Column(name = "item_id")
    private Integer itemId;

    @Column(name = "aula_id")
    private Integer aulaId;

    @Column(name = "simulado_id")
    private Integer simuladoId;

    @Column(name = "criado_por_id")
    private Integer criadoPorId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "agenda_event_classes",
            joinColumns = @JoinColumn(name = "evento_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    protected Evento() {}

    Evento(Integer criadoPorId) {
        this.criadoPorId = criadoPorId;
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public String getNome() {
        return titulo;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getDescricao() {
        return descricao;
    }

    public Instant getInicioEm() {
        return inicioEm;
    }

    public Instant getFimEm() {
        return fimEm;
    }

    public String getCategoria() {
        return categoria;
    }

    public Integer getModuloId() {
        return moduloId;
    }

    public Integer getItemId() {
        return itemId;
    }

    public Integer getAulaId() {
        return aulaId;
    }

    public Integer getSimuladoId() {
        return simuladoId;
    }

    public List<Turma> getTurmas() {
        return turmas;
    }

    void mudarTitulo(String titulo) {
        this.titulo = titulo;
    }

    void mudarDescricao(String descricao) {
        this.descricao = descricao;
    }

    void mudarHorario(Instant inicioEm, Instant fimEm) {
        this.inicioEm = inicioEm;
        this.fimEm = fimEm;
    }

    void mudarCategoria(String categoria) {
        this.categoria = categoria;
    }

    /** Uma ligação só: a nova apaga as outras. */
    void ligar(Integer moduloId, Integer itemId, Integer aulaId, Integer simuladoId) {
        this.moduloId = moduloId;
        this.itemId = itemId;
        this.aulaId = aulaId;
        this.simuladoId = simuladoId;
    }
}
