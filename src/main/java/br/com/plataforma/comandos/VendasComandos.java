package br.com.plataforma.comandos;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.vendas.VendasServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A venda pelo chat: o mesmo plano da tela de Vendas, montado com o que o professor fala. Valores
 * chegam em reais (1700, 159.9) e o cartão como "N parcelas de R$ X", que é como a página de
 * vendas anuncia; aqui viram centavos e o total do cartão.
 *
 * <p>Altera na hora, como as outras tools de montar: o preview é no chat, antes da chamada. Plano
 * novo nasce fora do ar (ver {@link VendasServico#montarVenda}).
 */
@RestController
@RequestMapping("/comandos")
public class VendasComandos {

    private final VendasServico vendas;

    public VendasComandos(VendasServico vendas) {
        this.vendas = vendas;
    }

    /** Uma linha da tabela: a partir de quando (vazio na primeira), Pix e cartão. Vazio não oferece. */
    public record Preco(LocalDate aPartirDe, BigDecimal pix, Integer cartaoParcelas, BigDecimal cartaoParcela) {}

    public record MontarVenda(
            @NotBlank(message = "é obrigatório: o fim do endereço público, ex.: 'extensivo-2027'") String link,
            String nome, List<String> turmas, List<Preco> precos, BigDecimal matricula, Integer matriculaParcelas,
            LocalDate acessoAte, String aVendaAte, Boolean aVenda) {}

    @PostMapping("/montar_venda")
    public VendasServico.VendaMontada montar(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody MontarVenda p) {
        var precos = p.precos() == null ? null : p.precos().stream().map(VendasComandos::linha).toList();
        var dados = new VendasServico.DadosDoPlano(p.nome(), null, null, null, null, null, p.acessoAte(), p.turmas(),
                p.aVenda(), p.matricula() == null ? null : centavos(p.matricula(), "matricula"), p.matriculaParcelas(),
                p.aVendaAte(), precos);
        return vendas.montarVenda(ident, p.link(), dados, Instant.now());
    }

    @PostMapping("/ver_vendas")
    public VendasServico.ResumoDasVendas ver(@AuthenticationPrincipal Identidade ident,
            @RequestBody(required = false) Map<String, Object> nada) {
        return vendas.resumo(ident);
    }

    private static VendasServico.PrecoDoPlano linha(Preco l) {
        var parcelas = l.cartaoParcelas() == null ? 1 : l.cartaoParcelas();
        Integer cartao = null;
        if (l.cartaoParcela() != null) {
            cartao = centavos(l.cartaoParcela(), "cartao_parcela") * parcelas;
        }
        return new VendasServico.PrecoDoPlano(l.aPartirDe(), l.pix() == null ? null : centavos(l.pix(), "pix"), cartao,
                parcelas);
    }

    /** Reais (159.9) em centavos (15990). Mais de duas casas é engano de quem digitou, não arredondamento. */
    private static int centavos(BigDecimal reais, String campo) {
        try {
            return reais.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
        } catch (ArithmeticException e) {
            throw new RegraDeNegocio("'%s' é em reais com até dois centavos, ex.: 159.90 (veio %s).".formatted(campo, reais));
        }
    }
}
