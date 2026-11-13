package br.com.plataforma.vendas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Uma linha da tabela de preços do pagamento único: vale a partir de {@code aPartirDe} (vazio: desde
 * já) até a linha seguinte. Pix e cartão têm preços próprios; um dos dois pode faltar.
 */
@Entity
@Table(name = "plano_precos")
public class PlanoPreco {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plano_id", nullable = false)
    private Plano plano;

    @Column(name = "a_partir_de")
    private LocalDate aPartirDe;

    @Column(name = "pix_centavos")
    private Integer pixCentavos;

    @Column(name = "cartao_centavos")
    private Integer cartaoCentavos;

    @Column(name = "cartao_parcelas", nullable = false)
    private Integer cartaoParcelas;

    protected PlanoPreco() {}

    PlanoPreco(Plano plano, LocalDate aPartirDe, Integer pixCentavos, Integer cartaoCentavos, int cartaoParcelas) {
        this.plano = plano;
        this.aPartirDe = aPartirDe;
        this.pixCentavos = pixCentavos;
        this.cartaoCentavos = cartaoCentavos;
        this.cartaoParcelas = cartaoParcelas;
    }

    public LocalDate getAPartirDe() {
        return aPartirDe;
    }

    public Integer getPixCentavos() {
        return pixCentavos;
    }

    public Integer getCartaoCentavos() {
        return cartaoCentavos;
    }

    public Integer getCartaoParcelas() {
        return cartaoParcelas;
    }
}
