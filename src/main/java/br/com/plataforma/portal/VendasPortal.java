package br.com.plataforma.portal;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.vendas.VendasServico;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Vendas no portal. {@code /api/admin/vendas/**} é do professor; {@code /api/vendas/**} é público —
 * a página de assinar e a de volta do pagamento, que o aluno abre sem conta.
 */
@RestController
public class VendasPortal {

    private final VendasServico vendas;
    private final AutenticacaoPortal autenticacao;

    public VendasPortal(VendasServico vendas, AutenticacaoPortal autenticacao) {
        this.vendas = vendas;
        this.autenticacao = autenticacao;
    }

    // --- professor -----------------------------------------------------------

    public record PlanoIn(@Size(max = 120) String nome, @Size(max = 60) String link, String tipo, Integer precoCentavos,
            Integer parcelasMax, LocalDate acessoAte, List<String> turmas, Boolean ativo) {

        VendasServico.DadosDoPlano dados() {
            return new VendasServico.DadosDoPlano(nome, link, tipo, precoCentavos, parcelasMax, acessoAte, turmas, ativo);
        }
    }

    @GetMapping("/api/admin/vendas/planos")
    public List<VendasServico.PlanoNaLista> planos(@AuthenticationPrincipal Identidade ident) {
        return vendas.listarPlanos(ident);
    }

    @PostMapping("/api/admin/vendas/planos")
    public VendasServico.PlanoNaLista criarPlano(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody PlanoIn d) {
        return vendas.criarPlano(ident, d.dados(), Instant.now());
    }

    @PatchMapping("/api/admin/vendas/planos/{plano}")
    public VendasServico.PlanoNaLista editarPlano(@AuthenticationPrincipal Identidade ident, @PathVariable Integer plano,
            @Valid @RequestBody PlanoIn d) {
        return vendas.editarPlano(ident, plano, d.dados(), Instant.now());
    }

    @GetMapping("/api/admin/vendas/pedidos")
    public List<VendasServico.PedidoNaLista> pedidos(@AuthenticationPrincipal Identidade ident) {
        return vendas.listarPedidos(ident);
    }

    // --- público -------------------------------------------------------------

    /**
     * O link que o professor divulga, {@code /assinar/extensivo-2026}, leva à página de assinar com o
     * plano na query: a exportação estática do Next só tem a página {@code /assinar/}. "pronto" é a
     * página de volta do pagamento, que existe de verdade.
     */
    @GetMapping({"/assinar/{link:(?!pronto$)[a-z0-9-]+}", "/assinar/{link:(?!pronto$)[a-z0-9-]+}/"})
    public org.springframework.http.ResponseEntity<Void> linkDoPlano(@PathVariable String link) {
        return org.springframework.http.ResponseEntity.status(302)
                .location(java.net.URI.create("/assinar/?plano=" + link)).build();
    }

    @GetMapping("/api/vendas/planos/{link}")
    public VendasServico.PlanoPublico plano(@PathVariable String link) {
        return vendas.planoPublico(link);
    }

    public record CompraIn(@NotBlank @Size(max = 200) String nome, @NotBlank @Size(max = 200) String email,
            @NotBlank @Size(max = 20) String cpf, @Size(max = 20) String celular, @Size(max = 10) String cep,
            @Size(max = 10) String numero) {}

    @PostMapping("/api/vendas/planos/{link}/comprar")
    public VendasServico.CompraIniciada comprar(@PathVariable String link, @Valid @RequestBody CompraIn d,
            HttpServletRequest pedido) {
        // Atrás do proxy, o Spring já montou o endereço público (https, domínio do Railway).
        var base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return vendas.comprar(link, new VendasServico.DadosDaCompra(d.nome(), d.email(), d.cpf(), d.celular(), d.cep(), d.numero()),
                AutenticacaoPortal.ip(pedido), base, Instant.now());
    }

    @GetMapping("/api/vendas/pedidos/{token}")
    public VendasServico.Situacao situacao(@PathVariable String token) {
        return vendas.situacao(token);
    }

    public record SenhaIn(@NotBlank @Size(max = 200) String senha) {}

    /** Cria a senha e já deixa o aluno logado: o próximo passo dele é a turma, não a tela de login. */
    @PostMapping("/api/vendas/pedidos/{token}/senha")
    public Map<String, Object> criarSenha(@PathVariable String token, @Valid @RequestBody SenhaIn d,
            HttpServletResponse resposta) {
        var usuario = vendas.criarSenha(token, d.senha(), Instant.now());
        autenticacao.abrirSessao(resposta, usuario);
        return Map.of("entrou", true);
    }
}
