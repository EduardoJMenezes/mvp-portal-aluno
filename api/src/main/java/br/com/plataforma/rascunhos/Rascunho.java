package br.com.plataforma.rascunhos;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.estrutura.SubModulo;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
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

/**
 * A proposta que espera o "pode" de um humano.
 *
 * <p>A regra que sustenta a POC: a IA propõe, o humano aprova, o backend publica. A aprovação
 * humana mora em {@code aprovadoPorId} — <b>estado no banco</b>, conferido a cada publicação, não
 * uma checagem no cliente.
 */
@Entity
@Table(name = "drafts")
public class Rascunho {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoRascunho tipo;

    /**
     * O rascunho sobrevive ao que ele aponta.
     *
     * <p>Remover é preencher {@code removido_em}, e {@code @SQLRestriction} esconde a linha — para
     * o Hibernate ela deixou de existir. Sem {@code @NotFound(IGNORE)}, tocar no proxy de uma
     * turma ou de um sub-módulo removido estoura {@code ObjectNotFoundException}, e a listagem
     * inteira de rascunhos cai por causa de um só. Com ele, "removido" chega aqui como
     * {@code null} — que é o que o resumo já sabia tratar, porque estes campos sempre puderam
     * faltar.
     *
     * <p>Nas associações <b>obrigatórias</b> ({@code optional = false}) a escolha é a oposta: pai
     * removido ali é furo de integridade, e estourar alto é melhor do que devolver meia verdade.
     *
     * <p>Sem {@code fetch = LAZY} de propósito: {@code @NotFound} força busca imediata de
     * qualquer jeito — não dá para adiar o que pode não existir — e deixar a palavra escrita só
     * rendia um aviso (HHH160133) a cada partida.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "turma_id")
    private Turma turma;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "submodulo_id")
    private SubModulo submodulo;

    @Column(nullable = false)
    private String resumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Canal origem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criado_por_id", nullable = false)
    private Usuario criadoPor;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aprovado_por_id")
    private Usuario aprovadoPor;

    @Column(name = "aprovado_em")
    private Instant aprovadoEm;

    @Enumerated(EnumType.STRING)
    @Column(name = "aprovado_via")
    private ViaAprovacao aprovadoVia;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    protected Rascunho() {}

    Rascunho(TipoRascunho tipo, Turma turma, SubModulo submodulo, String resumo, Canal origem,
            Usuario criadoPor) {
        this.tipo = tipo;
        this.turma = turma;
        this.submodulo = submodulo;
        this.resumo = resumo;
        this.origem = origem;
        this.status = Status.RASCUNHO;
        this.criadoPor = criadoPor;
    }

    public Integer getId() {
        return id;
    }

    public TipoRascunho getTipo() {
        return tipo;
    }

    public Turma getTurma() {
        return turma;
    }

    public SubModulo getSubmodulo() {
        return submodulo;
    }

    public String getResumo() {
        return resumo;
    }

    public Canal getOrigem() {
        return origem;
    }

    public Status getStatus() {
        return status;
    }

    public Usuario getCriadoPor() {
        return criadoPor;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Usuario getAprovadoPor() {
        return aprovadoPor;
    }

    public Instant getAprovadoEm() {
        return aprovadoEm;
    }

    public ViaAprovacao getAprovadoVia() {
        return aprovadoVia;
    }

    public Instant getPublicadoEm() {
        return publicadoEm;
    }

    public boolean temAprovacaoHumana() {
        return aprovadoPor != null;
    }

    void mudarResumo(String resumo) {
        this.resumo = resumo;
    }

    /** Grava quem aprovou, quando e por onde. É o estado que autoriza a publicação. */
    void aprovar(Usuario quem, ViaAprovacao via, Instant quando) {
        this.aprovadoPor = quem;
        this.aprovadoVia = via;
        this.aprovadoEm = quando;
    }

    void publicar(Instant quando) {
        this.status = Status.PUBLICADO;
        this.publicadoEm = quando;
    }
}
