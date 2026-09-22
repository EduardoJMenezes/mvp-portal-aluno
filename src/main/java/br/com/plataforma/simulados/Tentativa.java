package br.com.plataforma.simulados;

import br.com.plataforma.contas.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A prova de um aluno. Existir já é "ter feito": entra no ranking.
 *
 * <p>{@code prazoEm} é o que vier primeiro entre início + duração e o fechamento do simulado.
 * Passou do prazo sem entregar, a entrega é automática — decidida na próxima consulta, sem job.
 */
@Entity
@Table(name = "exam_attempts")
public class Tentativa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "simulado_id", nullable = false)
    private Simulado simulado;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aluno_id", nullable = false)
    private Usuario aluno;

    @Column(name = "iniciado_em", nullable = false)
    private Instant iniciadoEm;

    @Column(name = "prazo_em")
    private Instant prazoEm;

    @Column(name = "finalizado_em")
    private Instant finalizadoEm;

    @Column(name = "entregue_automaticamente", nullable = false)
    private boolean entregueAutomaticamente;

    @OneToMany(mappedBy = "tentativa", fetch = FetchType.LAZY, cascade = jakarta.persistence.CascadeType.ALL)
    private List<Resposta> respostas = new ArrayList<>();

    protected Tentativa() {}

    Tentativa(Simulado simulado, Usuario aluno, Instant iniciadoEm, Instant prazoEm) {
        this.simulado = simulado;
        this.aluno = aluno;
        this.iniciadoEm = iniciadoEm;
        this.prazoEm = prazoEm;
    }

    /** O aluno entregou. Quem já estava consolidada não muda. */
    void entregar(Instant agora) {
        if (finalizadoEm == null) {
            finalizadoEm = agora;
        }
    }

    public Integer getId() {
        return id;
    }

    public Simulado getSimulado() {
        return simulado;
    }

    public Instant getIniciadoEm() {
        return iniciadoEm;
    }

    public Usuario getAluno() {
        return aluno;
    }

    public Instant getPrazoEm() {
        return prazoEm;
    }

    public Instant getFinalizadoEm() {
        return finalizadoEm;
    }

    public boolean isEntregueAutomaticamente() {
        return entregueAutomaticamente;
    }

    public List<Resposta> getRespostas() {
        return respostas;
    }

    /**
     * Entrega automática da tentativa vencida. Devolve se ela está entregue.
     *
     * <p>Não há job agendado de propósito: a tentativa só importa quando alguém a consulta, e é
     * aí que o prazo é conferido.
     */
    public boolean consolidar(Instant agora) {
        if (finalizadoEm != null) {
            return true;
        }
        if (prazoEm != null && !agora.isBefore(prazoEm)) {
            finalizadoEm = prazoEm;
            entregueAutomaticamente = true;
            return true;
        }
        return false;
    }

    public int acertos() {
        return (int) respostas.stream().filter(Resposta::isCorreta).count();
    }
}
