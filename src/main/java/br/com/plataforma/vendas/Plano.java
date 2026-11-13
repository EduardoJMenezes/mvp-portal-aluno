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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Só na assinatura mensal: quantas cobranças ela faz (ex.: 12). Vazio é até o aluno cancelar. */
    @Column(name = "mensalidades")
    private Integer mensalidades;

    /** Só no pagamento único: até quando o acesso vale. Vazio é sem prazo. */
    @Column(name = "acesso_ate")
    private LocalDate acessoAte;

    /** Matrícula: cobrada no início, à parte do curso (só no pagamento único). Vazio é sem matrícula. */
    @Column(name = "matricula_centavos")
    private Integer matriculaCentavos;

    @Column(name = "matricula_parcelas_max", nullable = false)
    private Integer matriculaParcelasMax = 1;

    /** Último dia de venda; depois dele o link mostra "matrículas encerradas". Vazio é sem prazo. */
    @Column(name = "a_venda_ate")
    private LocalDate aVendaAte;

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

    @OneToMany(mappedBy = "plano", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private List<PlanoPreco> precos = new ArrayList<>();

    /** Linha de preço sem data vem primeiro; as outras, pela data. */
    static final Comparator<LocalDate> PELA_DATA = Comparator.nullsFirst(Comparator.naturalOrder());

    public record LinhaDePreco(LocalDate aPartirDe, Integer pixCentavos, Integer cartaoCentavos, int cartaoParcelas) {}

    protected Plano() {}

    Plano(String nome, String link, Tipo tipo, int precoCentavos, int parcelasMax, LocalDate acessoAte,
            List<Turma> turmas) {
        mudar(nome, link, tipo, precoCentavos, parcelasMax, null, acessoAte, turmas, true, null);
    }

    void mudar(String nome, String link, Tipo tipo, int precoCentavos, int parcelasMax, Integer mensalidades,
            LocalDate acessoAte, List<Turma> turmas, boolean ativo, Instant agora) {
        this.nome = nome;
        this.link = link;
        this.tipo = tipo;
        this.precoCentavos = precoCentavos;
        this.parcelasMax = parcelasMax;
        this.mensalidades = mensalidades;
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

    public Integer getMensalidades() {
        return mensalidades;
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

    public Integer getMatriculaCentavos() {
        return matriculaCentavos;
    }

    public Integer getMatriculaParcelasMax() {
        return matriculaParcelasMax;
    }

    public LocalDate getAVendaAte() {
        return aVendaAte;
    }

    /** A tabela em ordem: a linha sem data, depois as datas. */
    public List<PlanoPreco> getPrecos() {
        return precos.stream().sorted(Comparator.comparing(PlanoPreco::getAPartirDe, PELA_DATA)).toList();
    }

    void mudarMatricula(Integer centavos, int parcelasMax) {
        this.matriculaCentavos = centavos;
        this.matriculaParcelasMax = parcelasMax;
    }

    void mudarVendaAte(LocalDate aVendaAte) {
        this.aVendaAte = aVendaAte;
    }

    void mudarPrecos(List<LinhaDePreco> linhas) {
        precos.clear();
        linhas.forEach(l -> precos.add(new PlanoPreco(this, l.aPartirDe(), l.pixCentavos(), l.cartaoCentavos(),
                l.cartaoParcelas())));
    }

    /** O preço que vale no dia: a última linha que já começou. */
    public java.util.Optional<PlanoPreco> precoDoDia(LocalDate dia) {
        PlanoPreco vale = null;
        for (var p : getPrecos()) {
            if (p.getAPartirDe() == null || !p.getAPartirDe().isAfter(dia)) {
                vale = p;
            }
        }
        return java.util.Optional.ofNullable(vale);
    }

    /** Quando o preço do dia deixa de valer: a véspera da próxima linha. Vazio é "não muda". */
    public LocalDate precoValeAte(LocalDate dia) {
        return getPrecos().stream().map(PlanoPreco::getAPartirDe)
                .filter(d -> d != null && d.isAfter(dia)).findFirst().map(d -> d.minusDays(1)).orElse(null);
    }

    public boolean vendaEncerrada(LocalDate dia) {
        return aVendaAte != null && dia.isAfter(aVendaAte);
    }
}
