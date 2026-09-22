package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Status;
import br.com.plataforma.rascunhos.RascunhosServico;
import br.com.plataforma.simulados.MontagemDaProva;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comandos da prova. */
@RestController
@RequestMapping("/comandos")
public class SimuladosComandos {

    private final SimuladosServico simulados;
    private final CatalogoServico catalogo;
    private final RascunhosServico rascunhos;
    private final MontagemDaProva montagem;
    private final EntradasDaProva entradas;

    public SimuladosComandos(SimuladosServico simulados, CatalogoServico catalogo,
            RascunhosServico rascunhos, MontagemDaProva montagem, EntradasDaProva entradas) {
        this.simulados = simulados;
        this.catalogo = catalogo;
        this.rascunhos = rascunhos;
        this.montagem = montagem;
        this.entradas = entradas;
    }

    // --- listar_simulados ----------------------------------------------------

    public record ListarSimulados(String turma) {}

    @PostMapping("/listar_simulados")
    @Transactional(readOnly = true)
    public List<SimuladosServico.ResumoDoSimulado> listarSimulados(
            @AuthenticationPrincipal Identidade ident,
            @RequestBody(required = false) ListarSimulados pedido) {
        var turma = pedido == null || pedido.turma() == null
                ? null
                : catalogo.resolverTurma(pedido.turma());
        return simulados.listar(ident, turma, Instant.now());
    }

    // --- detalhar_simulado ---------------------------------------------------

    public record DetalharSimulado(
            @NotBlank(message = "é obrigatório: o título ou o id do simulado") String simulado) {}

    @PostMapping("/detalhar_simulado")
    @Transactional(readOnly = true)
    public SimuladosServico.SimuladoDetalhado detalharSimulado(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharSimulado pedido) {
        return simulados.detalhar(ident, pedido.simulado(), Instant.now());
    }

    // --- buscar_ranking_simulado ---------------------------------------------

    @PostMapping("/buscar_ranking_simulado")
    @Transactional
    public SimuladosServico.Ranking buscarRanking(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharSimulado pedido) {
        return simulados.ranking(ident, pedido.simulado(), Instant.now());
    }

    // --- editar_simulado -----------------------------------------------------

    public record EditarSimulado(
            @NotBlank(message = "é obrigatório: o título ou o id do simulado") String simulado,
            String titulo,
            String abreEm,
            String fechaEm,
            Integer duracaoMinutos,
            List<String> turmas,
            List<Object> questoes) {}

    /**
     * Altera o simulado direto — o preview é no chat, antes da chamada.
     *
     * <p>Antes de abrir, tudo muda. Aberto, só título e fechamento, e o fechamento só para mais
     * tarde: encurtar tiraria tempo de quem está fazendo a prova. Encerrado, só o título: reabrir
     * devolveria a prova a quem já viu o gabarito.
     */
    @PostMapping("/editar_simulado")
    @Transactional
    public SimuladosServico.ResumoDoSimulado editarSimulado(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody EditarSimulado pedido) {
        ident.exigirOperador();
        var agora = Instant.now();
        var s = simulados.resolver(pedido.simulado());
        var novoFechamento = SimuladosServico.lerDataHora(pedido.fechaEm());

        var mexeNaProva = pedido.abreEm() != null || pedido.duracaoMinutos() != null
                || pedido.turmas() != null || pedido.questoes() != null;
        simulados.exigirMudancaPermitida(s, agora, mexeNaProva, novoFechamento);

        var prova = pedido.questoes() == null ? null : montagem.montar(ident,
                entradas.traduzir(pedido.questoes()),
                s.getStatus() == Status.RASCUNHO ? s.getRascunhoId() : null,
                rascunhos.questoesAtuaisDe(s), Map.of());

        return simulados.aplicar(ident, s, pedido.titulo(),
                SimuladosServico.lerDataHora(pedido.abreEm()), pedido.duracaoMinutos(),
                pedido.turmas() == null ? null : catalogo.resolverTurmas(pedido.turmas()),
                prova, novoFechamento, agora);
    }

    // --- remover_simulado ----------------------------------------------------

    @PostMapping("/remover_simulado")
    @Transactional
    public SimuladosServico.SimuladoRemovido removerSimulado(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharSimulado pedido) {
        return simulados.remover(ident, pedido.simulado(), Instant.now());
    }
}
