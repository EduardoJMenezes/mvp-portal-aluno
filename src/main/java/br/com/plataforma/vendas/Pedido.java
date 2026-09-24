package br.com.plataforma.vendas;

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
 * Uma compra, do formulário até o fim do acesso. O fim do acesso é uma regra só, {@code acessoAte}:
 * vazio com o pedido pago, vale; preenchido, vale até lá. Atraso, cancelamento e pagamento único
 * usam o mesmo campo, e uma rotina tira a matrícula quando ele vence.
 */
@Entity
@Table(name = "pedidos")
public class Pedido {

    public enum Status { AGUARDANDO, PAGO, ATRASADO, CANCELADO, REEMBOLSADO, EXPIRADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plano_id", nullable = false)
    private Plano plano;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String cpf;

    private String celular;

    private String cep;

    private String numero;

    @Column(name = "valor_centavos", nullable = false)
    private Integer valorCentavos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "asaas_checkout_id")
    private String asaasCheckoutId;

    @Column(name = "asaas_cliente_id")
    private String asaasClienteId;

    @Column(name = "asaas_assinatura_id")
    private String asaasAssinaturaId;

    @Column(name = "usuario_id")
    private Integer usuarioId;

    @Column(name = "conta_nova", nullable = false)
    private boolean contaNova;

    @Column(name = "senha_definida", nullable = false)
    private boolean senhaDefinida;

    @Column(name = "acesso_liberado", nullable = false)
    private boolean acessoLiberado;

    @Column(name = "acesso_ate")
    private Instant acessoAte;

    @Column(name = "pago_em")
    private Instant pagoEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "alterado_em")
    private Instant alteradoEm;

    protected Pedido() {}

    Pedido(Plano plano, String tokenHash, String nome, String email, String cpf, String celular, String cep,
            String numero) {
        this.plano = plano;
        this.tokenHash = tokenHash;
        this.nome = nome;
        this.email = email;
        this.cpf = cpf;
        this.celular = celular;
        this.cep = cep;
        this.numero = numero;
        this.valorCentavos = plano.getPrecoCentavos();
        this.status = Status.AGUARDANDO;
    }

    void ligarAssinatura(String assinaturaId) {
        this.asaasAssinaturaId = assinaturaId;
    }

    void checkoutCriado(String checkoutId) {
        this.asaasCheckoutId = checkoutId;
    }

    void pago(Integer usuarioId, boolean contaNova, String clienteId, String assinaturaId, Instant acessoAte,
            Instant agora) {
        if (this.usuarioId == null) {
            this.usuarioId = usuarioId;
            this.contaNova = contaNova;
        }
        if (clienteId != null && !clienteId.isBlank()) {
            this.asaasClienteId = clienteId;
        }
        if (assinaturaId != null && !assinaturaId.isBlank()) {
            this.asaasAssinaturaId = assinaturaId;
        }
        if (this.pagoEm == null) {
            this.pagoEm = agora;
        }
        this.status = Status.PAGO;
        this.acessoLiberado = true;
        this.acessoAte = acessoAte;
        this.alteradoEm = agora;
    }

    /** O acesso continua, mas com data para acabar. */
    void vence(Status status, Instant acessoAte, Instant agora) {
        this.status = status;
        this.acessoAte = acessoAte;
        this.alteradoEm = agora;
    }

    void acessoRevogado(Status status, Instant agora) {
        this.status = status;
        this.acessoLiberado = false;
        this.alteradoEm = agora;
    }

    void expirou(Instant agora) {
        this.status = Status.EXPIRADO;
        this.alteradoEm = agora;
    }

    void senhaFoiDefinida() {
        this.senhaDefinida = true;
    }

    public Integer getId() {
        return id;
    }

    public Plano getPlano() {
        return plano;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public String getCpf() {
        return cpf;
    }

    public String getCelular() {
        return celular;
    }

    public Integer getValorCentavos() {
        return valorCentavos;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getUsuarioId() {
        return usuarioId;
    }

    public boolean isContaNova() {
        return contaNova;
    }

    public boolean isSenhaDefinida() {
        return senhaDefinida;
    }

    public boolean isAcessoLiberado() {
        return acessoLiberado;
    }

    public Instant getAcessoAte() {
        return acessoAte;
    }

    public Instant getPagoEm() {
        return pagoEm;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
