package br.com.plataforma.aulas;

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
 * Aula ao vivo: a sala é do Zoom, a porta é nossa.
 *
 * <p>Quem alcança a aula é decidido aqui, como num material — turma inteira ou pessoa a pessoa. O
 * Zoom só hospeda a sala, e o {@code zoom_meeting_id} é o único fio entre as duas coisas. O link
 * de iniciar do professor não tem coluna: expira em duas horas, e é buscado na hora.
 */
@Entity
@Table(name = "live_classes")
@SQLRestriction("removido_em IS NULL")
public class Aula extends Rastreavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String titulo;

    private String descricao;

    @Column(name = "inicio_em", nullable = false)
    private Instant inicioEm;

    @Column(nullable = false)
    private Integer minutos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private boolean gravar;

    @Column(name = "zoom_meeting_id")
    private String zoomMeetingId;

    @Column(name = "zoom_join_url")
    private String zoomJoinUrl;

    @Column(name = "submodulo_id")
    private Integer submoduloId;

    @Column(name = "publicar_gravacao", nullable = false)
    private boolean publicarGravacao;

    @Column(name = "gravacao_item_id")
    private Integer gravacaoItemId;

    @Column(name = "gravacao_vimeo_id")
    private String gravacaoVimeoId;

    @Column(name = "iniciada_em")
    private Instant iniciadaEm;

    @Column(name = "encerrada_em")
    private Instant encerradaEm;

    @Column(name = "criado_por_id", nullable = false)
    private Integer criadoPorId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "live_class_classes",
            joinColumns = @JoinColumn(name = "aula_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "live_class_students",
            joinColumns = @JoinColumn(name = "aula_id"),
            inverseJoinColumns = @JoinColumn(name = "usuario_id"))
    @OrderBy("nome")
    private List<Usuario> alunos = new ArrayList<>();

    protected Aula() {}

    Aula(String titulo, String descricao, Instant inicioEm, int minutos, boolean gravar,
            Integer submoduloId, boolean publicarGravacao, Integer criadoPorId) {
        this.titulo = titulo;
        this.descricao = descricao;
        this.inicioEm = inicioEm;
        this.minutos = minutos;
        this.gravar = gravar;
        this.status = Status.RASCUNHO;
        this.submoduloId = submoduloId;
        this.publicarGravacao = publicarGravacao;
        this.criadoPorId = criadoPorId;
    }

    public Integer getId() {
        return id;
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

    public Integer getMinutos() {
        return minutos;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isGravar() {
        return gravar;
    }

    public String getZoomMeetingId() {
        return zoomMeetingId;
    }

    public Integer getGravacaoItemId() {
        return gravacaoItemId;
    }

    public String getGravacaoVimeoId() {
        return gravacaoVimeoId;
    }

    public Integer getSubmoduloId() {
        return submoduloId;
    }

    public Integer getCriadoPorId() {
        return criadoPorId;
    }

    public Instant getIniciadaEm() {
        return iniciadaEm;
    }

    public Instant getEncerradaEm() {
        return encerradaEm;
    }

    /** O professor abriu a sala — ou reabriu, depois de cair: reabrir desfaz o "encerrada". */
    void salaComecou(Instant quando) {
        this.iniciadaEm = quando;
        this.encerradaEm = null;
    }

    void salaTerminou(Instant quando) {
        this.encerradaEm = quando;
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

    void mudarHorario(Instant inicioEm, Integer minutos) {
        if (inicioEm != null) {
            this.inicioEm = inicioEm;
        }
        if (minutos != null) {
            this.minutos = minutos;
        }
    }

    void mudarGravacao(Boolean gravar, Integer submoduloId, Boolean publicarGravacao) {
        if (gravar != null) {
            this.gravar = gravar;
        }
        if (submoduloId != null) {
            this.submoduloId = submoduloId == 0 ? null : submoduloId;
        }
        if (publicarGravacao != null) {
            this.publicarGravacao = publicarGravacao;
        }
    }

    void abrirSala(String meetingId, String joinUrl, Instant agora) {
        this.zoomMeetingId = meetingId;
        this.zoomJoinUrl = joinUrl;
        this.status = Status.PUBLICADO;
        this.publicadoEm = agora;
    }

    void fecharSala() {
        this.zoomMeetingId = null;
        this.zoomJoinUrl = null;
    }

    void gravacaoChegou(String vimeoId, Integer itemId) {
        this.gravacaoVimeoId = vimeoId;
        this.gravacaoItemId = itemId;
    }

    void mudarStatus(Status status) {
        this.status = status;
    }
}
