package br.com.plataforma.portal;

import br.com.plataforma.agenda.AgendaServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A agenda (decisão 0012): o aluno lê a das turmas dele até hoje; o professor monta. */
@RestController
public class AgendaPortal {

    private final AgendaServico agenda;
    private final CatalogoServico catalogo;

    public AgendaPortal(AgendaServico agenda, CatalogoServico catalogo) {
        this.agenda = agenda;
        this.catalogo = catalogo;
    }

    public record LigacaoIn(AgendaServico.TipoDeLigacao tipo, Integer id) {}

    /** Datas com fuso, como o navegador manda (toISOString). */
    public record EventoIn(String titulo, String descricao, String inicioEm, String fimEm, String categoria,
            List<String> turmas, LigacaoIn ligacao) {}

    private static Instant horario(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(texto.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw new RegraDeNegocio("O horário do evento precisa de fuso.");
        }
    }

    private AgendaServico.Dados dados(EventoIn d) {
        return new AgendaServico.Dados(d.titulo(), d.descricao(), horario(d.inicioEm()), horario(d.fimEm()),
                d.categoria(), d.turmas() == null ? null : catalogo.resolverTurmas(d.turmas()),
                d.ligacao() == null ? null : new AgendaServico.Ligacao(d.ligacao().tipo(), d.ligacao().id()));
    }

    @GetMapping("/api/aluno/agenda")
    public List<AgendaServico.Resumo> doAluno(@AuthenticationPrincipal Identidade ident) {
        return agenda.doAluno(ident, Instant.now());
    }

    @GetMapping("/api/admin/agenda")
    public List<AgendaServico.Resumo> listar(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String turma) {
        return agenda.listar(ident, turma == null || turma.isBlank() ? null : catalogo.resolverTurma(turma),
                Instant.now());
    }

    @PostMapping("/api/admin/agenda")
    public AgendaServico.Resumo criar(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody EventoIn d) {
        return agenda.criar(ident, dados(d), Instant.now());
    }

    @PatchMapping("/api/admin/agenda/{evento}")
    public AgendaServico.Resumo editar(@AuthenticationPrincipal Identidade ident, @PathVariable String evento,
            @Valid @RequestBody EventoIn d) {
        return agenda.editar(ident, evento, dados(d), Instant.now());
    }

    @DeleteMapping("/api/admin/agenda/{evento}")
    public AgendaServico.Removido remover(@AuthenticationPrincipal Identidade ident, @PathVariable String evento) {
        return agenda.remover(ident, evento);
    }
}
