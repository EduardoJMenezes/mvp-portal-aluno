package br.com.plataforma.importacoes;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Um arquivo chegando pelo link de envio: o .docx do simulado ou os prints das questões.
 *
 * <p>O link é de uso único, tem prazo e é preso a quem pediu. O banco guarda só o
 * <b>hash</b> do token, como em {@code api_tokens}: quem lê a tabela não consegue usar o link.
 *
 * <p>A coluna {@code arquivo} (bytes do .docx) não é mapeada de propósito — carregá-la junto
 * traria o documento inteiro em toda consulta de revisão.
 */
@Entity
@Table(name = "imports")
public class Importacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criado_por_id", nullable = false)
    private Usuario criadoPor;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expira_em", nullable = false)
    private Instant expiraEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusImportacao status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> parametros;

    @Column(name = "arquivo_nome")
    private String arquivoNome;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> blocos;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> relatorio;

    @Column(name = "rascunho_id")
    private Integer rascunhoId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "recebido_em")
    private Instant recebidoEm;

    protected Importacao() {}

    Importacao(Usuario criadoPor, String tokenHash, Instant expiraEm, Map<String, Object> parametros) {
        this.criadoPor = criadoPor;
        this.tokenHash = tokenHash;
        this.expiraEm = expiraEm;
        this.parametros = parametros;
        this.status = StatusImportacao.AGUARDANDO;
    }

    public Integer getId() {
        return id;
    }

    public Usuario getCriadoPor() {
        return criadoPor;
    }

    public Instant getExpiraEm() {
        return expiraEm;
    }

    public StatusImportacao getStatus() {
        return status;
    }

    public Map<String, Object> getParametros() {
        return parametros;
    }

    public String getArquivoNome() {
        return arquivoNome;
    }

    public List<Map<String, Object>> getBlocos() {
        return blocos;
    }

    public Map<String, Object> getRelatorio() {
        return relatorio;
    }

    public Integer getRascunhoId() {
        return rascunhoId;
    }

    public Instant getRecebidoEm() {
        return recebidoEm;
    }

    /** Fecha a importação: uso único, então o que chegou não chega de novo. */
    void processada(String arquivoNome, Integer rascunhoId, List<Map<String, Object>> blocos,
            Map<String, Object> relatorio, Instant quando) {
        this.status = StatusImportacao.PROCESSADA;
        this.arquivoNome = arquivoNome;
        this.rascunhoId = rascunhoId;
        this.blocos = blocos;
        this.relatorio = relatorio;
        this.recebidoEm = quando;
    }
}
