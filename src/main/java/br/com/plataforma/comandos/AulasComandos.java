package br.com.plataforma.comandos;

import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aulas ao vivo pelo chat. Sem {@code publicar}, a aula nasce em rascunho, <b>sem sala no Zoom</b>.
 * Com {@code publicar}, que o Claude só manda depois do ok do professor no chat (decisão 0008 do
 * cofre), a sala nasce na hora — fora dos horários da outra plataforma que divide a conta.
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
            String submodulo,
            Boolean publicar) {}

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
        var inicio = SimuladosServico.lerDataHora(pedido.inicio());
        var publicar = Boolean.TRUE.equals(pedido.publicar());
        if (publicar) {
            exigirForaDaOutraPlataforma(inicio, pedido.minutos() == null ? 60 : pedido.minutos());
        }
        var agora = Instant.now();
        var resumo = aulas.criar(ident, new AulasServico.Dados(pedido.titulo(), inicio, pedido.minutos(),
                pedido.descricao(), pedido.gravar(), catalogo.resolverTurmas(turmas),
                pedido.alunos() == null ? List.of() : pedido.alunos(), destino, null, null), agora);
        if (!publicar) {
            return new AulaAgendada(resumo, "Aula criada em RASCUNHO, sem sala no Zoom. Para abrir a sala, "
                    + "publique em Admin › Aulas ao vivo. Não agende de novo com publicar: criaria outra aula.");
        }
        resumo = aulas.editar(ident, String.valueOf(resumo.aulaId()),
                new AulasServico.Dados(null, null, null, null, null, null, null, null, null, "PUBLICADO"), agora);
        return new AulaAgendada(resumo, "Aula publicada: a sala do Zoom foi criada e a turma já vê a aula"
                + (destino == null ? "." : " no capítulo."));
    }

    /**
     * Os horários da outra plataforma que divide a conta do Zoom, no horário de Brasília.
     * ponytail: supõe 2 h para cada aula de lá; se a agenda deles mudar, muda aqui.
     */
    private record Bloqueio(DayOfWeek dia, LocalTime de, LocalTime ate) {}

    private static final List<Bloqueio> DA_OUTRA_PLATAFORMA = List.of(
            new Bloqueio(DayOfWeek.TUESDAY, LocalTime.of(17, 0), LocalTime.of(19, 0)),
            new Bloqueio(DayOfWeek.WEDNESDAY, LocalTime.of(19, 0), LocalTime.of(21, 0)));

    static void exigirForaDaOutraPlataforma(Instant inicio, int minutos) {
        if (inicio == null) {
            return;
        }
        var comeco = inicio.atZone(Relogio.BRASILIA);
        var fim = comeco.plusMinutes(minutos);
        for (var dia = comeco.toLocalDate(); !dia.isAfter(fim.toLocalDate()); dia = dia.plusDays(1)) {
            for (var b : DA_OUTRA_PLATAFORMA) {
                if (dia.getDayOfWeek() == b.dia()
                        && comeco.isBefore(dia.atTime(b.ate()).atZone(Relogio.BRASILIA))
                        && fim.isAfter(dia.atTime(b.de()).atZone(Relogio.BRASILIA))) {
                    throw new RegraDeNegocio("Terça das 17h às 19h e quarta das 19h às 21h a conta do Zoom é da "
                            + "outra plataforma. Escolha outro horário, ou publique pelo portal se tiver certeza.");
                }
            }
        }
    }
}
