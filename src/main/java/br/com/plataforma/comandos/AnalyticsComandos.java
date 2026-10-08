package br.com.plataforma.comandos;

import br.com.plataforma.analytics.AnalyticsServico;
import br.com.plataforma.comum.Identidade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A leitura que o professor pede: como um aluno foi, e como a turma foi. */
@RestController
@RequestMapping("/comandos")
public class AnalyticsComandos {

    private final AnalyticsServico analytics;
    private final br.com.plataforma.analytics.DevolutivaServico devolutiva;
    private final br.com.plataforma.catalogo.CatalogoServico catalogo;

    public AnalyticsComandos(AnalyticsServico analytics, br.com.plataforma.analytics.DevolutivaServico devolutiva,
            br.com.plataforma.catalogo.CatalogoServico catalogo) {
        this.analytics = analytics;
        this.devolutiva = devolutiva;
        this.catalogo = catalogo;
    }

    // --- buscar_desempenho_por_assunto ---------------------------------------

    public record BuscarDesempenhoPorAssunto(String turma, String aluno) {}

    /**
     * A devolutiva por assunto, juntando questão de aula e de simulado: da turma inteira (com as
     * questões das aulas e o que cada um marcou) ou de um aluno. Um dos dois, não os dois.
     */
    @PostMapping("/buscar_desempenho_por_assunto")
    @Transactional
    public Object buscarDesempenhoPorAssunto(
            @AuthenticationPrincipal Identidade ident, @RequestBody BuscarDesempenhoPorAssunto pedido) {
        var temTurma = pedido.turma() != null && !pedido.turma().isBlank();
        var temAluno = pedido.aluno() != null && !pedido.aluno().isBlank();
        if (temTurma == temAluno) {
            throw new br.com.plataforma.comum.RegraDeNegocio(
                    "Informe a turma ou o aluno — um dos dois. Turmas: listar_turmas.");
        }
        ident.exigirOperador();
        return temTurma
                ? devolutiva.daTurma(ident, catalogo.resolverTurma(pedido.turma()), Instant.now())
                : devolutiva.doAluno(ident, pedido.aluno(), Instant.now());
    }

    // --- buscar_desempenho_aluno ---------------------------------------------

    public record BuscarDesempenhoAluno(
            @NotBlank(message = "é obrigatório: nome, e-mail ou id do aluno") String aluno,
            String simulado) {}

    @PostMapping("/buscar_desempenho_aluno")
    @Transactional
    public AnalyticsServico.DesempenhoDoAluno buscarDesempenhoAluno(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody BuscarDesempenhoAluno pedido) {
        return analytics.desempenhoDoAluno(ident, pedido.aluno(), pedido.simulado(), Instant.now());
    }

    // --- buscar_estatisticas_simulado ----------------------------------------

    public record BuscarEstatisticas(
            @NotBlank(message = "é obrigatório: o título ou o id do simulado") String simulado) {}

    @PostMapping("/buscar_estatisticas_simulado")
    @Transactional
    public AnalyticsServico.EstatisticasDoSimulado buscarEstatisticas(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody BuscarEstatisticas pedido) {
        return analytics.estatisticasDoSimulado(ident, pedido.simulado(), Instant.now());
    }
}
