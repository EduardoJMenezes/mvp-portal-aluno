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

    /** MENSAL: assinatura no cartão. PIX: curso e matrícula num Pix só. CARTAO: matrícula, depois o curso parcelado. */
    public enum Forma { MENSAL, PIX, CARTAO }

    /** O que foi vendido, congelado no pedido: a tabela do plano pode mudar depois. */
    public record Valores(Forma forma, int cursoCentavos, int cartaoParcelas, Integer matriculaCentavos) {
        int total() {
            return cursoCentavos + (matriculaCentavos == null ? 0 : matriculaCentavos);
        }
    }

    public record Endereco(String cep, String rua, String numero, String complemento, String bairro, String cidade,
            String uf) {}

    public record Aceite(String versao, Instant em, String ip) {}

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

    @Enumerated(EnumType.STRING)
    private Forma forma;

    @Column(name = "curso_centavos")
    private Integer cursoCentavos;

    @Column(name = "cartao_parcelas")
    private Integer cartaoParcelas;

    @Column(name = "matricula_centavos")
    private Integer matriculaCentavos;

    @Column(name = "matricula_checkout_id")
    private String matriculaCheckoutId;

    @Column(name = "matricula_paga_em")
    private Instant matriculaPagaEm;

    private String rua;

    private String complemento;

    private String bairro;

    private String cidade;

    private String uf;

    @Column(name = "termos_versao")
    private String termosVersao;

    @Column(name = "termos_aceitos_em")
    private Instant termosAceitosEm;

    @Column(name = "termos_ip")
    private String termosIp;

    @Column(name = "envio1_em")
    private Instant envio1Em;

    @Column(name = "envio2_em")
    private Instant envio2Em;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "alterado_em")
    private Instant alteradoEm;

    protected Pedido() {}

    Pedido(Plano plano, String tokenHash, String nome, String email, String cpf, String celular, Endereco endereco,
            Valores valores, Aceite aceite) {
        this.plano = plano;
        this.tokenHash = tokenHash;
        this.nome = nome;
        this.email = email;
        this.cpf = cpf;
        this.celular = celular;
        this.cep = endereco.cep();
        this.numero = endereco.numero();
        this.rua = endereco.rua();
        this.complemento = endereco.complemento();
        this.bairro = endereco.bairro();
        this.cidade = endereco.cidade();
        this.uf = endereco.uf();
        this.forma = valores.forma();
        this.cursoCentavos = valores.cursoCentavos();
        this.cartaoParcelas = valores.cartaoParcelas();
        this.matriculaCentavos = valores.matriculaCentavos();
        this.valorCentavos = valores.total();
        this.termosVersao = aceite.versao();
        this.termosAceitosEm = aceite.em();
        this.termosIp = aceite.ip();
        this.status = Status.AGUARDANDO;
    }

    void matriculaCheckoutCriado(String checkoutId) {
        this.matriculaCheckoutId = checkoutId;
    }

    void matriculaPaga(String clienteId, Instant agora) {
        if (this.matriculaPagaEm == null) {
            this.matriculaPagaEm = agora;
        }
        if (clienteId != null && !clienteId.isBlank()) {
            this.asaasClienteId = clienteId;
        }
        this.alteradoEm = agora;
    }

    void envio(int qual, boolean enviado, Instant agora) {
        var quando = enviado ? agora : null;
        if (qual == 1) {
            this.envio1Em = enviado && envio1Em != null ? envio1Em : quando;
        } else {
            this.envio2Em = enviado && envio2Em != null ? envio2Em : quando;
        }
        this.alteradoEm = agora;
    }

    /** No cartão com matrícula, o curso é a segunda etapa: matrícula paga, curso ainda não. */
    public boolean faltaPagarOCurso() {
        return forma == Forma.CARTAO && matriculaCentavos != null && matriculaPagaEm != null
                && status == Status.AGUARDANDO;
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

    public Forma getForma() {
        return forma;
    }

    public Integer getCursoCentavos() {
        return cursoCentavos;
    }

    public Integer getCartaoParcelas() {
        return cartaoParcelas;
    }

    public Integer getMatriculaCentavos() {
        return matriculaCentavos;
    }

    public Instant getMatriculaPagaEm() {
        return matriculaPagaEm;
    }

    public String getCep() {
        return cep;
    }

    public String getNumero() {
        return numero;
    }

    public String getRua() {
        return rua;
    }

    public String getComplemento() {
        return complemento;
    }

    public String getBairro() {
        return bairro;
    }

    public String getCidade() {
        return cidade;
    }

    public String getUf() {
        return uf;
    }

    public String getTermosVersao() {
        return termosVersao;
    }

    public Instant getEnvio1Em() {
        return envio1Em;
    }

    public Instant getEnvio2Em() {
        return envio2Em;
    }
}
