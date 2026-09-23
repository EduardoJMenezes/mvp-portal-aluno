package br.com.plataforma.comandos;

import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aulas ao vivo pelo chat. Agendar cria a aula em rascunho, <b>sem sala no Zoom</b>: a sala nasce
 * quando o professor publica no portal. Por aqui não há como publicar — é o mesmo "a IA propõe, o
 * humano aprova" dos rascunhos, aplicado à agenda de uma conta de Zoom dividida.
 */
@RestController
@RequestMapping("/comandos")
public class AulasComandos {

    private final CatalogoServico catalogo;
    private final EstruturaServico estrutura;
    private final AulasServico aulas;

    public AulasComandos(CatalogoServico catalogo, EstruturaServico estrutura, AulasServico aulas) {
        this.catalogo = catalogo;
        this.estrutura = estrutura;
        this.aulas = aulas;
    }

    @PostMapping("/listar_aulas")
    public List<AulasServico.Resumo> listarAulas(@AuthenticationPrincipal Identidade ident) {
        ident.exigirOperador();
        return aulas.listar(ident, Instant.now());
    }

    public record AgendarAula(
            @NotBlank(message = "é obrigatório, ex.: 'Revisão de estequiometria'") String titulo,
            @NotBlank(message = "é obrigatório, no horário de Brasília: '2026-10-10T19:00'") String inicio,
            @Min(5) @Max(480) Integer minutos,
            String descricao,
            List<String> turmas,
            List<String> alunos,
            Boolean gravar,
            String modulo,
            String submodulo) {}

    public record AulaAgendada(AulasServico.Resumo aula, String mensagem) {}

    @PostMapping("/agendar_aula")
    @Transactional
    public AulaAgendada agendarAula(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody AgendarAula pedido) {
        var turmas = pedido.turmas() == null ? List.<String>of() : pedido.turmas();
        var destino = (Integer) null;
        if (pedido.modulo() != null && !pedido.modulo().isBlank()) {
            if (turmas.isEmpty()) {
                throw new RegraDeNegocio("O destino da gravação é um sub-módulo de uma turma: informe a turma.");
            }
            var alvos = estrutura.alvos(catalogo.resolverTurma(turmas.getFirst()), pedido.modulo(),
                    pedido.submodulo() == null || pedido.submodulo().isBlank() ? "Aulas" : pedido.submodulo(), null);
            destino = alvos.submodulo().getId();
        }
        var resumo = aulas.criar(ident, new AulasServico.Dados(pedido.titulo(),
                SimuladosServico.lerDataHora(pedido.inicio()), pedido.minutos(), pedido.descricao(), pedido.gravar(),
                catalogo.resolverTurmas(turmas), pedido.alunos() == null ? List.of() : pedido.alunos(), destino,
                null, null), Instant.now());
        return new AulaAgendada(resumo, "Aula criada em RASCUNHO, sem sala no Zoom. A sala nasce quando o "
                + "professor publica em Admin › Aulas ao vivo — é lá que ele confere e libera.");
    }
}
