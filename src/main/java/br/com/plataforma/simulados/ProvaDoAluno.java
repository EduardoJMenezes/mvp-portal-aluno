package br.com.plataforma.simulados;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.analytics.AnalyticsServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.questoes.Letra;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A prova do ponto de vista de quem faz.
 *
 * <p>As regras vêm de docs/MODELO-SIMULADO.md: uma janela só e um tempo de prova contado de quando
 * o aluno começa; estourou o prazo, entrega automática; o resultado só sai quando o simulado
 * fecha — e é o backend que recusa antes, não a tela que esconde.
 */
@Service
public class ProvaDoAluno {

    public enum Estado { AGENDADO, EM_ANDAMENTO, ENTREGUE, ENCERRADO }

    private final SimuladosServico simulados;
    private final TentativaRepositorio tentativas;
    private final ContasServico contas;
    private final AcessoServico acesso;
    private final AnalyticsServico analytics;

    public ProvaDoAluno(SimuladosServico simulados, TentativaRepositorio tentativas,
            ContasServico contas, AcessoServico acesso, AnalyticsServico analytics) {
        this.simulados = simulados;
        this.tentativas = tentativas;
        this.contas = contas;
        this.acesso = acesso;
        this.analytics = analytics;
    }

    /** Operador vê todos; aluno, só o publicado de alguma turma dele (seção 11). */
    private void exigirVisivel(Identidade ident, Simulado s) {
        if (ident.eOperador()) {
            return;
        }
        if (s.getStatus() != Status.PUBLICADO) {
            throw new NaoAutorizado("Este simulado ainda não foi publicado.");
        }
        var minhas = contas.turmasDoAluno(ident.usuarioId());
        if (s.getTurmas().stream().map(Turma::getId).noneMatch(minhas::contains)) {
            throw new NaoAutorizado("%s não está em nenhuma turma deste simulado.".formatted(ident.nome()));
        }
    }

    // --- listar --------------------------------------------------------------

    public record MinhaProva(boolean iniciada, boolean entregue, String prazoEm) {}

    public record SimuladoDoAluno(
            Integer simuladoId, String titulo, Status status, Situacao situacao, List<String> turmas,
            String abreEm, String fechaEm, Integer duracaoMinutos, int totalQuestoes,
            MinhaProva minhaProva, boolean resultadoDisponivel) {}

    @Transactional
    public List<SimuladoDoAluno> listar(Identidade ident, Instant agora) {
        var minhas = contas.turmasDoAluno(ident.usuarioId());
        var saida = new ArrayList<SimuladoDoAluno>();
        for (var r : simulados.listar(ident, null, agora)) {
            var s = simulados.resolver(String.valueOf(r.simuladoId()));
            if (s.getStatus() != Status.PUBLICADO
                    || s.getTurmas().stream().map(Turma::getId).noneMatch(minhas::contains)) {
                continue;
            }
            var t = tentativas.doAluno(s, ident.usuarioId()).orElse(null);
            var entregue = t != null && t.consolidar(agora);
            saida.add(new SimuladoDoAluno(r.simuladoId(), r.titulo(), r.status(), r.situacao(),
                    r.turmas(), r.abreEm(), r.fechaEm(), r.duracaoMinutos(), r.totalQuestoes(),
                    new MinhaProva(t != null, entregue, t == null ? null : Relogio.iso(t.getPrazoEm())),
                    r.situacao() == Situacao.ENCERRADO && t != null));
        }
        return saida;
    }

    // --- abrir ---------------------------------------------------------------

    public record QuestaoDaProva(
            Integer ordem, Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Letra marcada) {}

    public record Prova(
            Integer simuladoId, String titulo, Status status, Situacao situacao, List<String> turmas,
            String abreEm, String fechaEm, Integer duracaoMinutos, int totalQuestoes, Estado estado,
            Boolean resultadoDisponivel, Boolean entregueAutomaticamente, String resultadoEm,
            String prazoEm, Long segundosRestantes, List<QuestaoDaProva> questoes) {}

    /**
     * A prova para o aluno: agendado, em andamento (sem gabarito), entregue ou encerrado. Abrir pela
     * primeira vez é começar: é aqui que o prazo nasce.
     */
    @Transactional
    public Prova abrir(Identidade ident, String referencia, Instant agora) {
        if (!ident.eAluno()) {
            throw new RegraDeNegocio("Abrir a prova é a visão do aluno. Para ver o simulado, use detalhar.");
        }
        var s = simulados.resolver(referencia);
        exigirVisivel(ident, s);
        var r = simulados.resumo(s, agora, null);
        var sit = r.situacao();

        if (sit == Situacao.AGENDADO) {
            return prova(r, Estado.AGENDADO, null, null, null, null, null, null);
        }
        var t = tentativas.doAluno(s, ident.usuarioId()).orElse(null);
        if (sit == Situacao.ENCERRADO) {
            if (t != null) {
                t.consolidar(agora);
            }
            return prova(r, Estado.ENCERRADO, t != null, null, null, null, null, null);
        }
        if (t == null) {
            var limite = agora.plus(Duration.ofMinutes(s.getDuracaoMinutos() == null ? 0 : s.getDuracaoMinutos()));
            var prazo = s.getFechaEm() != null && s.getFechaEm().isBefore(limite) ? s.getFechaEm() : limite;
            var aluno = contas.buscar(ident.usuarioId()).orElseThrow();
            t = tentativas.saveAndFlush(new Tentativa(s, aluno, agora, prazo));
        }
        if (t.consolidar(agora)) {
            return prova(r, Estado.ENTREGUE, null, t.isEntregueAutomaticamente(),
                    Relogio.iso(s.getFechaEm()), null, null, null);
        }
        var marcadas = new LinkedHashMap<Integer, Letra>();
        t.getRespostas().forEach(x -> marcadas.put(x.getQuestaoId(), x.getAlternativaMarcada()));
        var questoes = s.getQuestoes().stream().map(sq -> {
            var q = sq.getQuestao();
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            return new QuestaoDaProva(sq.getOrdem(), q.getId(), q.getEnunciado(), alternativas,
                    marcadas.get(q.getId()));
        }).toList();
        return prova(r, Estado.EM_ANDAMENTO, null, null, null, Relogio.iso(t.getPrazoEm()),
                restantes(t, agora), questoes);
    }

    private static Prova prova(SimuladosServico.ResumoDoSimulado r, Estado estado, Boolean resultado,
            Boolean automatica, String resultadoEm, String prazoEm, Long segundos,
            List<QuestaoDaProva> questoes) {
        return new Prova(r.simuladoId(), r.titulo(), r.status(), r.situacao(), r.turmas(), r.abreEm(),
                r.fechaEm(), r.duracaoMinutos(), r.totalQuestoes(), estado, resultado, automatica,
                resultadoEm, prazoEm, segundos, questoes);
    }

    private static long restantes(Tentativa t, Instant agora) {
        return Math.max(0, Duration.between(agora, t.getPrazoEm()).toSeconds());
    }

    // --- responder e entregar ------------------------------------------------

    public record Registrada(boolean registrado, int respondidas, int total, long segundosRestantes) {}

    /** Grava a resposta do aluno. A correção acontece aqui, e não é revelada. */
    @Transactional
    public Registrada responder(Identidade ident, String referencia, Integer questaoId, String alternativa,
            Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos respondem simulados.");
        }
        var texto = alternativa == null ? "" : alternativa.strip().toUpperCase(Locale.ROOT);
        Letra letra;
        try {
            letra = Letra.valueOf(texto);
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Alternativa '%s' inválida. Use A a E.".formatted(alternativa));
        }

        var s = simulados.resolver(referencia);
        exigirVisivel(ident, s);
        if (SimuladosServico.situacao(s, agora) != Situacao.ABERTO) {
            throw new RegraDeNegocio("'%s' não está aberto para respostas.".formatted(s.getTitulo()));
        }
        var vinculo = s.getQuestoes().stream()
                .filter(sq -> sq.getQuestao().getId().equals(questaoId)).findFirst()
                .orElseThrow(() -> new RegraDeNegocio(
                        "A questão %d não faz parte deste simulado.".formatted(questaoId)));

        var t = tentativas.doAluno(s, ident.usuarioId()).orElseThrow(() -> new RegraDeNegocio(
                "Abra o simulado antes de responder: é ao abrir que o tempo começa."));
        if (t.consolidar(agora)) {
            throw new RegraDeNegocio(t.isEntregueAutomaticamente()
                    ? "O tempo acabou: a prova foi entregue com o que estava respondido."
                    : "Esta prova já foi entregue.");
        }

        var correta = letra == vinculo.getQuestao().getGabarito();
        var existente = t.getRespostas().stream().filter(x -> x.getQuestaoId().equals(questaoId)).findFirst();
        if (existente.isPresent()) {
            existente.get().marcar(letra, correta, agora);
        } else {
            t.getRespostas().add(new Resposta(t, questaoId, letra, correta, agora));
        }
        tentativas.saveAndFlush(t);
        // Sem `correta`: o aluno só vê o resultado quando o simulado fechar.
        return new Registrada(true, t.getRespostas().size(), s.getQuestoes().size(), restantes(t, agora));
    }

    public record Entregue(
            Integer simuladoId, String titulo, boolean entregue, boolean entregueAutomaticamente,
            int respondidas, int total, String resultadoEm, String mensagem) {}

    /** O aluno entrega. O recibo diz quando o resultado sai — não o resultado. */
    @Transactional
    public Entregue entregar(Identidade ident, String referencia, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos entregam uma prova.");
        }
        var s = simulados.resolver(referencia);
        exigirVisivel(ident, s);
        var t = tentativas.doAluno(s, ident.usuarioId())
                .orElseThrow(() -> new RegraDeNegocio("Você ainda não começou este simulado."));
        if (!t.consolidar(agora)) {
            t.entregar(agora);
        }
        return new Entregue(s.getId(), s.getTitulo(), true, t.isEntregueAutomaticamente(),
                t.getRespostas().size(), s.getQuestoes().size(), Relogio.iso(s.getFechaEm()),
                "Prova entregue. O resultado sai em %s.".formatted(Relogio.emBrasilia(s.getFechaEm())));
    }

    // --- resultado e histórico -----------------------------------------------

    public record QuestaoCorrigida(
            Integer ordem, Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Letra marcada, boolean emBranco, Letra gabarito, boolean correta, String resolucaoComentada,
            AcessoServico.VideoDescrito resolucao) {}

    public record Resultado(
            Integer simuladoId, String titulo, int acertos, int total, double percentual, int emBranco,
            boolean entregueAutomaticamente, int posicao, int participantes,
            List<QuestaoCorrigida> questoes, List<AnalyticsServico.Recomendacao> analise) {}

    /**
     * O resultado individual: nota, posição, gabarito, resolução e análise. Só depois do fechamento.
     * O ranking completo não sai daqui — o aluno vê a própria posição e quantos participaram.
     */
    @Transactional
    public Resultado resultado(Identidade ident, String referencia, Instant agora) {
        if (!ident.eAluno()) {
            throw new RegraDeNegocio(
                    "Resultado individual é a visão do aluno. Para a turma, use o ranking e as estatísticas.");
        }
        var s = simulados.resolver(referencia);
        exigirVisivel(ident, s);
        if (SimuladosServico.situacao(s, agora) != Situacao.ENCERRADO) {
            throw new RegraDeNegocio("O resultado de '%s' sai em %s, quando o simulado fechar."
                    .formatted(s.getTitulo(), Relogio.emBrasilia(s.getFechaEm())));
        }
        var linhas = simulados.classificar(s, agora);
        var minha = linhas.stream().filter(c -> c.tentativa().getAluno().getId().equals(ident.usuarioId()))
                .findFirst().orElseThrow(() -> new RegraDeNegocio("Você não fez '%s'.".formatted(s.getTitulo())));

        var t = minha.tentativa();
        var respostas = new LinkedHashMap<Integer, Resposta>();
        t.getRespostas().forEach(x -> respostas.put(x.getQuestaoId(), x));
        var idsResolucao = s.getQuestoes().stream().map(sq -> sq.getQuestao().getVideo())
                .filter(v -> v != null).map(v -> v.getId()).toList();
        var liberados = acesso.videosLiberados(ident, idsResolucao, agora);

        var questoes = new ArrayList<QuestaoCorrigida>();
        for (var sq : s.getQuestoes()) {
            var q = sq.getQuestao();
            var r = respostas.get(q.getId());
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            questoes.add(new QuestaoCorrigida(sq.getOrdem(), q.getId(), q.getEnunciado(), alternativas,
                    r == null ? null : r.getAlternativaMarcada(), r == null, q.getGabarito(),
                    r != null && r.isCorreta(), q.getResolucaoComentada(),
                    q.getVideo() == null ? null
                            : AcessoServico.descrever(q.getVideo(), liberados.contains(q.getVideo().getId()))));
        }
        var total = s.getQuestoes().size();
        return new Resultado(s.getId(), s.getTitulo(), minha.acertos(), total,
                SimuladosServico.percentual(minha.acertos(), total),
                (int) questoes.stream().filter(QuestaoCorrigida::emBranco).count(),
                t.isEntregueAutomaticamente(), minha.posicao(), linhas.size(), questoes,
                analytics.recomendarVideos(ident,
                        questoes.stream().filter(q -> !q.correta()).map(QuestaoCorrigida::questaoId).toList(),
                        5, agora));
    }

    public record SimuladoFeito(
            Integer simuladoId, String titulo, String fechouEm, int acertos, int total, double percentual,
            int posicao, int participantes) {}

    public record Historico(List<SimuladoFeito> simulados, List<AnalyticsServico.Recomendacao> topicos) {}

    /**
     * Os simulados encerrados que o aluno fez, com nota e posição, e os tópicos em que mais errou.
     * Só encerrados: o histórico não pode ser uma porta dos fundos para o resultado.
     */
    @Transactional
    public Historico historico(Identidade ident, Instant agora) {
        if (!ident.eAluno()) {
            throw new RegraDeNegocio("O histórico é a visão do aluno. Para um aluno específico, use o desempenho.");
        }
        var linhas = new ArrayList<SimuladoFeito>();
        var erradas = new ArrayList<Integer>();
        for (var t : tentativas.todasDoAluno(ident.usuarioId())) {
            var s = t.getSimulado();
            if (SimuladosServico.situacao(s, agora) != Situacao.ENCERRADO) {
                continue;
            }
            var classificacao = simulados.classificar(s, agora);
            var minha = classificacao.stream()
                    .filter(c -> c.tentativa().getAluno().getId().equals(ident.usuarioId())).findFirst();
            if (minha.isEmpty()) {
                continue;
            }
            var certas = t.getRespostas().stream().filter(Resposta::isCorreta).map(Resposta::getQuestaoId).toList();
            s.getQuestoes().stream().map(sq -> sq.getQuestao().getId())
                    .filter(id -> !certas.contains(id)).forEach(erradas::add);
            var total = s.getQuestoes().size();
            linhas.add(new SimuladoFeito(s.getId(), s.getTitulo(), Relogio.iso(s.getFechaEm()),
                    minha.get().acertos(), total, SimuladosServico.percentual(minha.get().acertos(), total),
                    minha.get().posicao(), classificacao.size()));
        }
        var topicos = analytics.recomendarVideos(ident, erradas, 3, agora);
        return new Historico(linhas, topicos.subList(0, Math.min(5, topicos.size())));
    }
}
