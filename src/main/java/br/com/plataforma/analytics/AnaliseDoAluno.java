package br.com.plataforma.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Uma análise escrita de um aluno, como estava num dia. Vale a mais recente de cada recorte; as
 * outras são histórico.
 *
 * <p>O recorte é o conjunto de respostas lido: com {@code simuladoId}, as daquela prova — é a
 * análise que o aluno lê no resultado; sem, tudo o que ele respondeu — é a geral, que só o
 * professor vê. Quem escreve é o Claude do professor, pelo MCP, só com o que o aluno respondeu e
 * com os comentários aprovados em cada alternativa. Aluno, operador e simulado vão como id: a
 * análise tem que sobreviver ao que acontecer com eles.
 */
@Entity
@Table(name = "student_analyses")
public class AnaliseDoAluno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "aluno_id", nullable = false)
    private Integer alunoId;

    /** Nulo: é a análise geral, do professor. */
    @Column(name = "simulado_id")
    private Integer simuladoId;

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

    protected AnaliseDoAluno() {}

    AnaliseDoAluno(Integer alunoId, Integer simuladoId, String pontoPrincipal, String padroes, String melhorou,
            String proximosPassos, int respostas, Integer escritaPorId, Instant escritaEm) {
        this.alunoId = alunoId;
        this.simuladoId = simuladoId;
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

    public Integer getSimuladoId() {
        return simuladoId;
    }
}
