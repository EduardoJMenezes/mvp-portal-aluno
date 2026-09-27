package br.com.plataforma.agenda;

import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Categoria;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.simulados.SimuladosServico;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A agenda (decisão 0012): eventos por turma, que ligam ao conteúdo. O aluno vê os eventos das
 * turmas dele até o fim de hoje; o que vem depois não aparece. A liberação do conteúdo ligado é do
 * {@link LiberacaoServico}.
 */
@Service
public class AgendaServico {

    /** O que o evento pode abrir. {@code NENHUMA} tira a ligação. */
    public enum TipoDeLigacao { AULA, MODULO, AULA_AO_VIVO, SIMULADO, NENHUMA }

    public record Ligacao(TipoDeLigacao tipo, Integer id) {}

    /** Para onde o clique leva. {@code moduloId} vem junto da aula, que abre dentro do módulo. */
    public record Destino(TipoDeLigacao tipo, Integer id, Integer moduloId, String nome) {}

    public record Resumo(
            Integer eventoId, String titulo, String descricao, String inicioEm, String fimEm, String categoria,
            List<String> turmas, Destino destino, boolean liberado) {}

    /** Nulo é "não mexa", menos o fim: quem muda o início diz o fim junto (nulo = sem fim). */
    public record Dados(
            String titulo, String descricao, Instant inicioEm, Instant fimEm, String categoria, List<Turma> turmas,
            Ligacao ligacao) {}

    private final EventoRepositorio eventos;
    private final EstruturaServico estrutura;
    private final AulasServico aulas;
    private final SimuladosServico simulados;
    private final ContasServico contas;

    public AgendaServico(EventoRepositorio eventos, EstruturaServico estrutura, AulasServico aulas,
            SimuladosServico simulados, ContasServico contas) {
        this.eventos = eventos;
        this.estrutura = estrutura;
        this.aulas = aulas;
        this.simulados = simulados;
        this.contas = contas;
    }

    // --- leitura -----------------------------------------------------------------

    /** O professor vê tudo; com turma, só os eventos dela. */
    @Transactional(readOnly = true)
    public List<Resumo> listar(Identidade ident, Turma turma, Instant agora) {
        ident.exigirOperador();
        return eventos.findAllByOrderByInicioEmAscIdAsc().stream()
                .filter(e -> turma == null || e.getTurmas().stream().anyMatch(t -> t.getId().equals(turma.getId())))
                .map(e -> resumo(e, agora))
                .toList();
    }

    /** Os eventos das turmas do aluno, até o fim de hoje (horário de Brasília). */
    @Transactional(readOnly = true)
    public List<Resumo> doAluno(Identidade ident, Instant agora) {
        var turmas = contas.turmasDoAluno(ident.usuarioId());
        if (turmas.isEmpty()) {
            return List.of();
        }
        var fimDeHoje = agora.atZone(Relogio.BRASILIA).toLocalDate().plusDays(1)
                .atStartOfDay(Relogio.BRASILIA).toInstant();
        return eventos.dasTurmasAte(turmas, fimDeHoje).stream().map(e -> resumo(e, agora)).toList();
    }

    @Transactional(readOnly = true)
    public Evento exigir(String referencia) {
        var texto = referencia == null ? "" : referencia.strip();
        var achado = !texto.isEmpty() && texto.length() <= 9 && texto.chars().allMatch(Character::isDigit)
                ? eventos.findById(Integer.parseInt(texto)) : java.util.Optional.<Evento>empty();
        return achado.orElseThrow(() -> new NaoEncontrado("Evento '%s' não existe na agenda.".formatted(referencia)));
    }

    private Resumo resumo(Evento e, Instant agora) {
        return new Resumo(e.getId(), e.getTitulo(), e.getDescricao(), Relogio.iso(e.getInicioEm()),
                e.getFimEm() == null ? null : Relogio.iso(e.getFimEm()), e.getCategoria(),
                e.getTurmas().stream().map(Turma::getNome).toList(), destino(e), !agora.isBefore(e.getInicioEm()));
    }

    /** O conteúdo ligado, com o nome de hoje. Removido depois, o evento fica sem destino. */
    private Destino destino(Evento e) {
        try {
            if (e.getItemId() != null) {
                return estrutura.item(e.getItemId()).map(i -> new Destino(TipoDeLigacao.AULA, i.getId(),
                        i.getSubmodulo().getModulo() == null ? null : i.getSubmodulo().getModulo().getId(), i.getNome()))
                        .orElse(null);
            }
            if (e.getModuloId() != null) {
                return estrutura.modulo(e.getModuloId())
                        .map(m -> new Destino(TipoDeLigacao.MODULO, m.getId(), m.getId(), m.getNome())).orElse(null);
            }
            if (e.getAulaId() != null) {
                var a = aulas.exigir(String.valueOf(e.getAulaId()));
                return new Destino(TipoDeLigacao.AULA_AO_VIVO, a.getId(), null, a.getTitulo());
            }
            if (e.getSimuladoId() != null) {
                var s = simulados.resolver(String.valueOf(e.getSimuladoId()));
                return new Destino(TipoDeLigacao.SIMULADO, s.getId(), null, s.getTitulo());
            }
        } catch (NaoEncontrado removido) {
            return null;
        }
        return null;
    }

    // --- escrita -------------------------------------------------------------------

    @Transactional
    public Resumo criar(Identidade ident, Dados d, Instant agora) {
        ident.exigirOperador();
        if (d.inicioEm() == null) {
            throw new RegraDeNegocio("O evento precisa de dia e hora.");
        }
        var e = new Evento(ident.usuarioId());
        aplicar(e, d);
        if (e.getTitulo() == null) {
            throw new RegraDeNegocio("O evento precisa de um título, ex.: 'Aula 2 - Estequiometria'.");
        }
        if (e.getTurmas().isEmpty()) {
            throw new RegraDeNegocio("Diga para que turmas o evento vale.");
        }
        e.tocar(ident);
        return resumo(eventos.save(e), agora);
    }

    @Transactional
    public Resumo editar(Identidade ident, String referencia, Dados d, Instant agora) {
        ident.exigirOperador();
        var e = exigir(referencia);
        aplicar(e, d);
        if (e.getTurmas().isEmpty()) {
            throw new RegraDeNegocio("Diga para que turmas o evento vale.");
        }
        e.tocar(ident);
        return resumo(eventos.save(e), agora);
    }

    public record Removido(Integer eventoId, String titulo, boolean reversivel) {}

    @Transactional
    public Removido remover(Identidade ident, String referencia) {
        ident.exigirOperador();
        var e = exigir(referencia);
        e.remover(ident);
        eventos.save(e);
        return new Removido(e.getId(), e.getTitulo(), true);
    }

    private void aplicar(Evento e, Dados d) {
        if (d.titulo() != null) {
            var titulo = d.titulo().strip();
            if (titulo.isEmpty() || titulo.length() > 200) {
                throw new RegraDeNegocio("O título do evento vai de 1 a 200 letras.");
            }
            e.mudarTitulo(titulo);
        }
        if (d.descricao() != null) {
            e.mudarDescricao(d.descricao().isBlank() ? null : d.descricao().strip());
        }
        if (d.inicioEm() != null) {
            if (d.fimEm() != null && d.fimEm().isBefore(d.inicioEm())) {
                throw new RegraDeNegocio("O fim do evento precisa vir depois do início.");
            }
            e.mudarHorario(d.inicioEm(), d.fimEm());
        }
        if (d.categoria() != null) {
            e.mudarCategoria(Categoria.limpar(d.categoria()));
        }
        if (d.turmas() != null) {
            var unicas = new LinkedHashMap<Integer, Turma>();
            d.turmas().forEach(t -> unicas.putIfAbsent(t.getId(), t));
            e.getTurmas().clear();
            e.getTurmas().addAll(unicas.values());
        }
        if (d.ligacao() != null) {
            ligar(e, d.ligacao());
        }
    }

    /** Confere que o conteúdo existe antes de ligar: evento apontando para o nada não serve. */
    private void ligar(Evento e, Ligacao l) {
        var tipo = l.tipo() == null ? TipoDeLigacao.NENHUMA : l.tipo();
        if (tipo != TipoDeLigacao.NENHUMA && l.id() == null) {
            throw new RegraDeNegocio("Diga qual %s o evento abre.".formatted(tipo.name().toLowerCase(Locale.ROOT)));
        }
        switch (tipo) {
            case AULA -> {
                estrutura.item(l.id()).orElseThrow(() -> new NaoEncontrado("Aula %d não existe.".formatted(l.id())));
                e.ligar(null, l.id(), null, null);
            }
            case MODULO -> {
                estrutura.modulo(l.id()).orElseThrow(() -> new NaoEncontrado("Módulo %d não existe.".formatted(l.id())));
                e.ligar(l.id(), null, null, null);
            }
            case AULA_AO_VIVO -> e.ligar(null, null, aulas.exigir(String.valueOf(l.id())).getId(), null);
            case SIMULADO -> e.ligar(null, null, null, simulados.resolver(String.valueOf(l.id())).getId());
            case NENHUMA -> e.ligar(null, null, null, null);
        }
    }
}
