package br.com.plataforma.vendas;

import br.com.plataforma.catalogo.Turma;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * O que se vende: um preço, um jeito de pagar e as turmas que ele abre. Plano é o que se vende;
 * turma é o que o aluno acessa — um plano pode abrir várias.
 */
@Entity
@Table(name = "planos")
public class Plano {

    public enum Tipo { MENSAL, UNICO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    /** O fim do endereço público: /assinar/{link}. */
    @Column(nullable = false)
    private String link;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tipo tipo;

    @Column(name = "preco_centavos", nullable = false)
    private Integer precoCentavos;

    @Column(name = "parcelas_max", nullable = false)
    private Integer parcelasMax;

    /** Só no pagamento único: até quando o acesso vale. Vazio é sem prazo. */
    @Column(name = "acesso_ate")
    private LocalDate acessoAte;

    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "alterado_em")
    private Instant alteradoEm;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "plano_turmas", joinColumns = @JoinColumn(name = "plano_id"),
            inverseJoinColumns = @JoinColumn(name = "turma_id"))
    @OrderBy("nome")
    private List<Turma> turmas = new ArrayList<>();

    protected Plano() {}

    Plano(String nome, String link, Tipo tipo, int precoCentavos, int parcelasMax, LocalDate acessoAte,
            List<Turma> turmas) {
        mudar(nome, link, tipo, precoCentavos, parcelasMax, acessoAte, turmas, true, null);
    }

    void mudar(String nome, String link, Tipo tipo, int precoCentavos, int parcelasMax, LocalDate acessoAte,
            List<Turma> turmas, boolean ativo, Instant agora) {
        this.nome = nome;
        this.link = link;
        this.tipo = tipo;
        this.precoCentavos = precoCentavos;
        this.parcelasMax = parcelasMax;
        this.acessoAte = acessoAte;
        this.turmas.clear();
        this.turmas.addAll(turmas);
        this.ativo = ativo;
        this.alteradoEm = agora;
    }

    public Integer getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getLink() {
        return link;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public Integer getPrecoCentavos() {
        return precoCentavos;
    }

    public Integer getParcelasMax() {
        return parcelasMax;
    }

    public LocalDate getAcessoAte() {
        return acessoAte;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public List<Turma> getTurmas() {
        return turmas;
    }
}
