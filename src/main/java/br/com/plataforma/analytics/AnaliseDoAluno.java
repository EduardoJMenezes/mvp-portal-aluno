package br.com.plataforma.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * O retrato do aluno em texto, como estava num dia. Vale a mais recente; as outras são histórico.
 *
 * <p>Quem escreve é o Claude do professor, pelo MCP, só com o que o aluno respondeu e com os
 * comentários aprovados em cada alternativa. O aluno e o operador vão como id: a análise tem que
 * sobreviver ao que acontecer com as contas.
 */
@Entity
@Table(name = "student_analyses")
public class AnaliseDoAluno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "aluno_id", nullable = false)
    private Integer alunoId;

    @Column(name = "ponto_principal", nullable = false)
    private String pontoPrincipal;

    private String padroes;

    private String melhorou;

    @Column(name = "proximos_passos")
    private String proximosPassos;

    @Column(nullable = false)
    private Integer respostas;

    @Column(name = "escrita_por_id", nullable = false)
    private Integer escritaPorId;

    @Column(name = "escrita_em", nullable = false)
    private Instant escritaEm;

    @Column(name = "oculta_em")
    private Instant ocultaEm;

    @Column(name = "oculta_por_id")
    private Integer ocultaPorId;

    @Column(name = "refazer_pedido_em")
    private Instant refazerPedidoEm;

    @Column(name = "refazer_pedido_por_id")
    private Integer refazerPedidoPorId;

    @Column(name = "refazer_orientacao")
    private String refazerOrientacao;

    protected AnaliseDoAluno() {}

    AnaliseDoAluno(Integer alunoId, String pontoPrincipal, String padroes, String melhorou, String proximosPassos,
            int respostas, Integer escritaPorId, Instant escritaEm) {
        this.alunoId = alunoId;
        this.pontoPrincipal = pontoPrincipal;
        this.padroes = padroes;
        this.melhorou = melhorou;
        this.proximosPassos = proximosPassos;
        this.respostas = respostas;
        this.escritaPorId = escritaPorId;
        this.escritaEm = escritaEm;
    }

    /** O aluno deixa de ver, ou volta a ver. */
    void ocultar(boolean oculta, Integer porId, Instant agora) {
        this.ocultaEm = oculta ? (ocultaEm != null ? ocultaEm : agora) : null;
        this.ocultaPorId = oculta ? porId : null;
    }

    /** Pedir outra tira esta da frente do aluno: quem pede é porque não quer que ela fique. */
    void pedirOutra(String orientacao, Integer porId, Instant agora) {
        this.refazerPedidoEm = agora;
        this.refazerPedidoPorId = porId;
        this.refazerOrientacao = orientacao;
        ocultar(true, porId, agora);
    }

    public Integer getId() {
        return id;
    }

    public Integer getAlunoId() {
        return alunoId;
    }

    public String getPontoPrincipal() {
        return pontoPrincipal;
    }

    public String getPadroes() {
        return padroes;
    }

    public String getMelhorou() {
        return melhorou;
    }

    public String getProximosPassos() {
        return proximosPassos;
    }

    public Integer getRespostas() {
        return respostas;
    }

    public Integer getEscritaPorId() {
        return escritaPorId;
    }

    public Instant getEscritaEm() {
        return escritaEm;
    }

    public boolean oculta() {
        return ocultaEm != null;
    }

    public Instant getRefazerPedidoEm() {
        return refazerPedidoEm;
    }

    public String getRefazerOrientacao() {
        return refazerOrientacao;
    }
}
