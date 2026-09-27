package br.com.plataforma.comandos;

import br.com.plataforma.agenda.AgendaServico;
import br.com.plataforma.agenda.AgendaServico.Ligacao;
import br.com.plataforma.agenda.AgendaServico.TipoDeLigacao;
import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A agenda pelo chat (decisão 0012). Criar, editar e remover mudam na hora — inclusive o que o
 * aluno vê, porque o evento libera o conteúdo ligado —, então o preview é no chat, antes.
 */
@RestController
@RequestMapping("/comandos")
public class AgendaComandos {

    private final AgendaServico agenda;
    private final CatalogoServico catalogo;
    private final EstruturaServico estrutura;
    private final AulasServico aulas;
    private final SimuladosServico simulados;

    public AgendaComandos(AgendaServico agenda, CatalogoServico catalogo, EstruturaServico estrutura,
            AulasServico aulas, SimuladosServico simulados) {
        this.agenda = agenda;
        this.catalogo = catalogo;
        this.estrutura = estrutura;
        this.aulas = aulas;
        this.simulados = simulados;
    }

    public record ListarAgenda(String turma) {}

    @PostMapping("/listar_agenda")
    public List<AgendaServico.Resumo> listarAgenda(@AuthenticationPrincipal Identidade ident,
            @RequestBody(required = false) ListarAgenda pedido) {
        var turma = pedido == null || pedido.turma() == null || pedido.turma().isBlank() ? null
                : catalogo.resolverTurma(pedido.turma());
        return agenda.listar(ident, turma, Instant.now());
    }

    /**
     * Um evento como o professor fala. A ligação é uma só: aula (com módulo e sub-módulo), módulo,
     * aula ao vivo ou simulado. Datas no horário de Brasília, ex.: '2027-02-01T19:00'.
     */
    public record EventoPedido(
            String titulo, String inicio, String fim, List<String> turmas, String categoria, String descricao,
            String modulo, String submodulo, String aula, String aulaAoVivo, String simulado, Boolean semLigacao) {}

    public record CriarEventos(@NotEmpty(message = "é obrigatória: a lista de eventos") List<EventoPedido> eventos) {}

    /** Todos ou nenhum: um evento com erro desfaz a lista inteira, e a mensagem diz qual. */
    @PostMapping("/criar_eventos")
    @Transactional
    public List<AgendaServico.Resumo> criarEventos(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CriarEventos pedido) {
        var agora = Instant.now();
        var criados = new java.util.ArrayList<AgendaServico.Resumo>();
        for (int i = 0; i < pedido.eventos().size(); i++) {
            var p = pedido.eventos().get(i);
            try {
                if (p.turmas() == null || p.turmas().isEmpty()) {
                    throw new RegraDeNegocio("diga para que turmas ele vale");
                }
                criados.add(agenda.criar(ident, dados(p), agora));
            } catch (RegraDeNegocio | NaoEncontrado e) {
                var titulo = p.titulo() == null ? "sem título" : p.titulo();
                throw new RegraDeNegocio("Evento %d ('%s'): %s".formatted(i + 1, titulo, e.getMessage()));
            }
        }
        return criados;
    }

    public record EditarEvento(
            @NotBlank(message = "é obrigatório: o id do evento") String evento,
            String titulo, String inicio, String fim, List<String> turmas, String categoria, String descricao,
            String modulo, String submodulo, String aula, String aulaAoVivo, String simulado, Boolean semLigacao) {}

    @PostMapping("/editar_evento")
    @Transactional
    public AgendaServico.Resumo editarEvento(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody EditarEvento p) {
        return agenda.editar(ident, p.evento(), dados(new EventoPedido(p.titulo(), p.inicio(), p.fim(), p.turmas(),
                p.categoria(), p.descricao(), p.modulo(), p.submodulo(), p.aula(), p.aulaAoVivo(), p.simulado(),
                p.semLigacao())), Instant.now());
    }

    public record RemoverEvento(@NotBlank(message = "é obrigatório: o id do evento") String evento) {}

    @PostMapping("/remover_evento")
    @Transactional
    public AgendaServico.Removido removerEvento(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody RemoverEvento pedido) {
        return agenda.remover(ident, pedido.evento());
    }

    // --- apoio ---------------------------------------------------------------------

    private AgendaServico.Dados dados(EventoPedido p) {
        var turmas = p.turmas() == null ? null : catalogo.resolverTurmas(p.turmas());
        return new AgendaServico.Dados(p.titulo(), p.descricao(), SimuladosServico.lerDataHora(p.inicio()),
                SimuladosServico.lerDataHora(p.fim()), p.categoria(), turmas,
                ligacao(p, turmas == null || turmas.isEmpty() ? null : turmas.getFirst()));
    }

    /** O módulo se acha primeiro entre os da turma do evento (K03 existe em mais de um ano). */
    private Ligacao ligacao(EventoPedido p, Turma contexto) {
        if (Boolean.TRUE.equals(p.semLigacao())) {
            return new Ligacao(TipoDeLigacao.NENHUMA, null);
        }
        if (p.aula() != null) {
            if (p.modulo() == null || p.submodulo() == null) {
                throw new RegraDeNegocio("Para ligar uma aula, diga também o módulo e o sub-módulo dela.");
            }
            return new Ligacao(TipoDeLigacao.AULA, alvos(contexto, p.modulo(), p.submodulo(), p.aula()).item().getId());
        }
        if (p.modulo() != null) {
            return new Ligacao(TipoDeLigacao.MODULO, alvos(contexto, p.modulo(), null, null).modulo().getId());
        }
        if (p.aulaAoVivo() != null) {
            return new Ligacao(TipoDeLigacao.AULA_AO_VIVO, aulas.exigir(p.aulaAoVivo()).getId());
        }
        if (p.simulado() != null) {
            return new Ligacao(TipoDeLigacao.SIMULADO, simulados.resolver(p.simulado()).getId());
        }
        return null;
    }

    private EstruturaServico.Alvos alvos(Turma contexto, String modulo, String submodulo, String item) {
        if (contexto != null) {
            try {
                return estrutura.alvos(contexto, modulo, submodulo, item);
            } catch (NaoEncontrado naoEDaTurma) {
                // Ainda não é da turma: procura na biblioteca inteira.
            }
        }
        return estrutura.alvos(null, modulo, submodulo, item);
    }
}
