package br.com.plataforma.simulados;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.Referencias;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A prova.
 *
 * <p>O relógio entra como parâmetro: não há job de entrega automática, a situação é derivada na
 * consulta. É o que permite testar prova aberta e fechada sem esperar.
 */
@Service
public class SimuladosServico {

    private final SimuladoRepositorio simulados;
    private final SimuladoQuestaoRepositorio vinculos;
    private final TentativaRepositorio tentativas;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public SimuladosServico(SimuladoRepositorio simulados, SimuladoQuestaoRepositorio vinculos,
            TentativaRepositorio tentativas) {
        this.simulados = simulados;
        this.vinculos = vinculos;
        this.tentativas = tentativas;
    }

    // --- tempo ---------------------------------------------------------------

    /**
     * "2026-10-10T14:00", no horário de Brasília.
     *
     * <p>Sem fuso explícito, vale Brasília: é o que o professor digita e o que o Claude repete.
     * Com fuso ({@code -03:00}, {@code Z}), vale o informado.
     */
    public static Instant lerDataHora(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        var texto = valor.strip();
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeParseException semFuso) {
            try {
                return LocalDateTime.parse(texto).atZone(Relogio.BRASILIA).toInstant();
            } catch (DateTimeParseException e) {
                throw new RegraDeNegocio(
                        ("Data e hora '%s' inválida. Use o formato 2026-10-10T14:00, no horário de "
                                + "Brasília.").formatted(valor));
            }
        }
    }

    public static Situacao situacao(Simulado simulado, Instant agora) {
        if (simulado.getStatus() != Status.PUBLICADO) {
            return Situacao.RASCUNHO;
        }
        if (simulado.getAbreEm() != null && agora.isBefore(simulado.getAbreEm())) {
            return Situacao.AGENDADO;
        }
        if (simulado.getFechaEm() != null && !agora.isBefore(simulado.getFechaEm())) {
            return Situacao.ENCERRADO;
        }
        return Situacao.ABERTO;
    }

    // --- resolução -----------------------------------------------------------

    @Transactional(readOnly = true)
    public Simulado resolver(String referencia) {
        var todos = simulados.findAllByOrderByCriadoEmDesc();
        var busca = Referencias.buscar(todos, referencia);
        if (busca.achou()) {
            return busca.achado();
        }
        if (busca.ambiguos().size() > 1) {
            throw new NaoEncontrado("'%s' corresponde a mais de um simulado: %s."
                    .formatted(referencia, comId(busca.ambiguos())));
        }
        var disponiveis = todos.isEmpty() ? "(nenhum)" : comId(todos);
        throw new NaoEncontrado(
                "Simulado '%s' não existe. Simulados: %s.".formatted(referencia, disponiveis));
    }

    private static String comId(List<Simulado> lista) {
        return lista.stream().map(s -> "#%d %s".formatted(s.getId(), s.getTitulo())).collect(joining(", "));
    }

    // --- travas --------------------------------------------------------------

    /** Questões, gabarito, turmas e tempo de prova travam quando o simulado abre. */
    public void exigirEditavel(Simulado s, Instant agora) {
        var quando = situacao(s, agora);
        if (quando == Situacao.ABERTO || quando == Situacao.ENCERRADO) {
            throw new RegraDeNegocio(
                    ("'%s' já abriu em %s: questões, gabarito, turmas e tempo de prova travaram. "
                            + "Só dá para estender o fechamento.")
                            .formatted(s.getTitulo(), Relogio.emBrasilia(s.getAbreEm())));
        }
    }

    @Transactional(readOnly = true)
    public List<Simulado> simuladosDa(Questao questao) {
        return vinculos.simuladosDa(questao);
    }

    /**
     * Enunciado, alternativas, gabarito e imagem travam quando a prova já abriu.
     *
     * <p>Classificação, dificuldade e vídeo de resolução continuam livres: mudar a etiqueta não
     * muda o que o aluno respondeu.
     */
    @Transactional(readOnly = true)
    public void exigirProvaFechadaParaMudancas(Questao questao, Instant agora) {
        var abertos = simuladosDa(questao).stream()
                .filter(s -> situacao(s, agora) == Situacao.ABERTO
                        || situacao(s, agora) == Situacao.ENCERRADO)
                .toList();
        if (abertos.isEmpty()) {
            return;
        }
        var nomes = abertos.stream()
                .map(s -> "'%s' (abriu em %s)".formatted(s.getTitulo(), Relogio.emBrasilia(s.getAbreEm())))
                .collect(joining(", "));
        throw new RegraDeNegocio(
                ("A questão %d está em simulado que já abriu: %s. Enunciado, alternativas, gabarito e "
                        + "imagem travaram; classificação, dificuldade e vídeo de resolução ainda mudam.")
                        .formatted(questao.getId(), nomes));
    }

    /** Remover a questão do acervo só depois que toda prova dela terminou. */
    @Transactional(readOnly = true)
    public void exigirNenhumaProvaPendente(Questao questao, Instant agora) {
        var pendentes = simuladosDa(questao).stream()
                .filter(s -> situacao(s, agora) != Situacao.ENCERRADO)
                .toList();
        if (pendentes.isEmpty()) {
            return;
        }
        var nomes = pendentes.stream().map(s -> "'" + s.getTitulo() + "'").collect(joining(", "));
        throw new RegraDeNegocio(
                ("A questão %d está em simulado que ainda não terminou: %s. Tire-a da prova com "
                        + "editar_simulado antes de remover.").formatted(questao.getId(), nomes));
    }

    /** O que impede o simulado de ir ao ar. Vazio quando está pronto. */
    public List<String> pendenciasParaPublicar(Simulado s, Instant agora) {
        var pendencias = new ArrayList<String>();
        if (s.getTurmas().isEmpty()) {
            pendencias.add("nenhuma turma");
        }
        if (s.getQuestoes().isEmpty()) {
            pendencias.add("nenhuma questão");
        }
        if (s.getAbreEm() == null || s.getFechaEm() == null) {
            pendencias.add("abertura e fechamento não definidos");
        } else if (!s.getAbreEm().isBefore(s.getFechaEm())) {
            pendencias.add("o fechamento vem antes da abertura");
        } else if (!s.getFechaEm().isAfter(agora)) {
            pendencias.add("o fechamento (%s) já passou".formatted(Relogio.emBrasilia(s.getFechaEm())));
        }
        if (s.getDuracaoMinutos() == null || s.getDuracaoMinutos() <= 0) {
            pendencias.add("tempo de prova não definido");
        }

        var incompletas = s.getQuestoes().stream()
                .filter(sq -> sq.getQuestao().getAlternativas().size() < Letra.values().length)
                .map(SimuladoQuestao::getOrdem).toList();
        if (!incompletas.isEmpty()) {
            pendencias.add("questões sem as alternativas A–E: " + incompletas);
        }
        var semImagem = s.getQuestoes().stream()
                .filter(sq -> sq.getQuestao().isImagemPendente())
                .map(SimuladoQuestao::getOrdem).toList();
        if (!semImagem.isEmpty()) {
            pendencias.add("questões com imagem pendente: " + semImagem);
        }
        return pendencias;
    }

    // --- leitura -------------------------------------------------------------

    public record ResumoDoSimulado(
            Integer simuladoId, String titulo, Status status, Situacao situacao, List<String> turmas,
            String abreEm, String fechaEm, Integer duracaoMinutos, int totalQuestoes,
            Integer tentativas) {}

    public ResumoDoSimulado resumo(Simulado s, Instant agora, Integer quantasTentativas) {
        return new ResumoDoSimulado(s.getId(), s.getTitulo(), s.getStatus(), situacao(s, agora),
                s.getTurmas().stream().map(Turma::getNome).toList(),
                Relogio.iso(s.getAbreEm()), Relogio.iso(s.getFechaEm()), s.getDuracaoMinutos(),
                s.getQuestoes().size(), quantasTentativas);
    }

    @Transactional(readOnly = true)
    public List<ResumoDoSimulado> listar(Identidade ident, Turma turma, Instant agora) {
        return simulados.findAllByOrderByCriadoEmDesc().stream()
                .filter(s -> turma == null
                        || s.getTurmas().stream().anyMatch(t -> t.getId().equals(turma.getId())))
                .sorted((a, b) -> {
                    // abre_em desc com nulos por último; empate desempata por criado_em desc
                    var x = a.getAbreEm();
                    var y = b.getAbreEm();
                    if (x == null && y == null) {
                        return b.getCriadoEm().compareTo(a.getCriadoEm());
                    }
                    if (x == null) {
                        return 1;
                    }
                    if (y == null) {
                        return -1;
                    }
                    return y.compareTo(x);
                })
                .map(s -> resumo(s, agora, tentativas.countBySimulado(s)))
                .toList();
    }

    public record QuestaoDaProva(
            Integer ordem, Integer questaoId, String enunciado,
            java.util.Map<Letra, String> alternativas, Letra gabarito, Boolean imagemPendente) {}

    public record SimuladoDetalhado(
            Integer simuladoId, String titulo, Status status, Situacao situacao, List<String> turmas,
            String abreEm, String fechaEm, Integer duracaoMinutos, int totalQuestoes,
            Integer tentativas, List<QuestaoDaProva> questoes, List<String> pendenciasParaPublicar) {}

    @Transactional(readOnly = true)
    public SimuladoDetalhado detalhar(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        var s = resolver(referencia);
        var base = resumo(s, agora, tentativas.countBySimulado(s));

        var questoes = s.getQuestoes().stream().map(sq -> {
            var q = sq.getQuestao();
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            return new QuestaoDaProva(sq.getOrdem(), q.getId(), q.getEnunciado(), alternativas,
                    q.getGabarito(), q.isImagemPendente());
        }).toList();

        return new SimuladoDetalhado(base.simuladoId(), base.titulo(), base.status(), base.situacao(),
                base.turmas(), base.abreEm(), base.fechaEm(), base.duracaoMinutos(),
                base.totalQuestoes(), base.tentativas(), questoes, pendenciasParaPublicar(s, agora));
    }

    // --- escrita -------------------------------------------------------------

    @Transactional
    public Simulado criar(Identidade ident, String titulo, List<Turma> turmas, Instant abreEm,
            Instant fechaEm, Integer duracaoMinutos, Integer rascunhoId) {
        ident.exigirOperador();
        var limpo = titulo == null ? "" : titulo.strip();
        if (limpo.isEmpty()) {
            throw new RegraDeNegocio("O simulado precisa de um título.");
        }
        if (duracaoMinutos != null && duracaoMinutos <= 0) {
            throw new RegraDeNegocio("O tempo de prova precisa ser maior que zero.");
        }
        if (abreEm != null && fechaEm != null && !abreEm.isBefore(fechaEm)) {
            throw new RegraDeNegocio("O fechamento precisa vir depois da abertura.");
        }

        var s = new Simulado(limpo, abreEm, fechaEm, duracaoMinutos, Status.RASCUNHO, rascunhoId,
                ident.usuarioId());
        s.getTurmas().addAll(turmas);
        s.tocar(ident);
        return simulados.save(s);
    }

    /**
     * Troca a prova inteira pela lista dada, na ordem.
     *
     * <p>A questão nova que saiu da prova sai do rascunho junto: sem isso, ela entraria no acervo
     * na publicação sem ninguém a ter revisado.
     */
    @Transactional
    public void trocarQuestoes(Identidade ident, Simulado s, List<Questao> prova) {
        if (prova.isEmpty()) {
            throw new RegraDeNegocio("O simulado precisa de ao menos uma questão.");
        }
        var anteriores = s.getQuestoes().stream().map(SimuladoQuestao::getQuestao).toList();

        for (var q : anteriores) {
            var nasceuAqui = s.getRascunhoId() != null && s.getRascunhoId().equals(q.getRascunhoId());
            var saiu = prova.stream().noneMatch(x -> x.getId().equals(q.getId()));
            if (nasceuAqui && saiu) {
                q.remover(ident);
            }
        }

        s.getQuestoes().clear();
        // O flush aqui não é enfeite: sem ele o Hibernate insere os vínculos novos antes de
        // apagar os velhos, e o unique (simulado_id, questao_id) estoura na questão que ficou.
        em.flush();
        for (int i = 0; i < prova.size(); i++) {
            s.getQuestoes().add(new SimuladoQuestao(s, prova.get(i), i + 1));
        }
    }

    /**
     * As travas por situação, antes de qualquer mudança.
     *
     * <p>Separada de {@link #aplicar} para a borda poder conferir <b>antes</b> de montar a prova:
     * montar cria questão nova no banco, e não faz sentido criar para depois recusar.
     */
    public void exigirMudancaPermitida(
            Simulado s, Instant agora, boolean mexeNaProva, Instant novoFechamento) {
        var antes = situacao(s, agora);

        if (antes == Situacao.ENCERRADO) {
            if (mexeNaProva || novoFechamento != null) {
                throw new RegraDeNegocio(
                        ("'%s' fechou em %s e o resultado já saiu: só o título muda. Reabrir "
                                + "devolveria a prova a quem já viu o gabarito.")
                                .formatted(s.getTitulo(), Relogio.emBrasilia(s.getFechaEm())));
            }
        } else if (antes == Situacao.ABERTO) {
            if (mexeNaProva) {
                exigirEditavel(s, agora);
            }
            if (novoFechamento != null && novoFechamento.isBefore(s.getFechaEm())) {
                throw new RegraDeNegocio(
                        "'%s' já abriu: o fechamento só pode ser estendido, não antecipado."
                                .formatted(s.getTitulo()));
            }
        }
    }

    /** Aplica o que foi permitido. Recebe a prova já montada: montar é orquestração da borda. */
    @Transactional
    public ResumoDoSimulado aplicar(Identidade ident, Simulado s, String titulo, Instant abreEm,
            Integer duracaoMinutos, List<Turma> turmas, List<Questao> prova, Instant novoFechamento,
            Instant agora) {
        ident.exigirOperador();
        var antes = situacao(s, agora);

        if (antes != Situacao.ENCERRADO && antes != Situacao.ABERTO) {
            if (abreEm != null) {
                s.mudarJanela(abreEm, s.getFechaEm());
            }
            if (duracaoMinutos != null) {
                if (duracaoMinutos <= 0) {
                    throw new RegraDeNegocio("O tempo de prova precisa ser maior que zero.");
                }
                s.mudarDuracao(duracaoMinutos);
            }
            if (turmas != null) {
                trocarTurmas(s, turmas);
            }
            if (prova != null) {
                trocarQuestoes(ident, s, prova);
            }
        }

        if (titulo != null) {
            if (titulo.isBlank()) {
                throw new RegraDeNegocio("O simulado precisa de um título.");
            }
            s.mudarTitulo(titulo.strip());
        }
        if (novoFechamento != null) {
            s.mudarJanela(s.getAbreEm(), novoFechamento);
        }
        if (s.getAbreEm() != null && s.getFechaEm() != null && !s.getAbreEm().isBefore(s.getFechaEm())) {
            throw new RegraDeNegocio("O fechamento precisa vir depois da abertura.");
        }
        if (antes == Situacao.AGENDADO) {
            var pendencias = pendenciasParaPublicar(s, agora);
            if (!pendencias.isEmpty()) {
                throw new RegraDeNegocio(
                        "'%s' já está publicado, e assim não teria como ir ao ar: %s."
                                .formatted(s.getTitulo(), String.join("; ", pendencias)));
            }
        }

        s.tocar(ident);
        simulados.save(s);
        return resumo(s, agora, null);
    }

    public void trocarTurmas(Simulado s, List<Turma> turmas) {
        if (turmas.isEmpty()) {
            throw new RegraDeNegocio("O simulado precisa de ao menos uma turma.");
        }
        s.getTurmas().clear();
        s.getTurmas().addAll(turmas);
    }

    /** Publica. Chamado pela publicação do rascunho, que é quem confere a aprovação humana. */
    @Transactional
    public void publicar(Simulado s, Instant quando) {
        s.publicar(quando);
        simulados.save(s);
    }

    public record SimuladoRemovido(
            String simulado, Situacao situacao, int questoesNovasRemovidas, boolean reversivel) {}

    /**
     * Remoção lógica. Com a prova aberta, não: tiraria a prova da mão de quem faz.
     *
     * <p>Simulado em rascunho leva junto as questões novas que nasceram com ele — eram parte da
     * mesma proposta.
     */
    @Transactional
    public SimuladoRemovido remover(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        var s = resolver(referencia);
        var quando = situacao(s, agora);
        if (quando == Situacao.ABERTO) {
            throw new RegraDeNegocio(
                    "'%s' está aberto agora, com alunos fazendo a prova. Espere fechar."
                            .formatted(s.getTitulo()));
        }

        var novas = new ArrayList<Questao>();
        if (s.getStatus() == Status.RASCUNHO && s.getRascunhoId() != null) {
            for (var sq : s.getQuestoes()) {
                if (s.getRascunhoId().equals(sq.getQuestao().getRascunhoId())) {
                    novas.add(sq.getQuestao());
                }
            }
        }
        s.remover(ident);
        novas.forEach(q -> q.remover(ident));
        simulados.save(s);

        return new SimuladoRemovido(s.getTitulo(), quando, novas.size(), true);
    }

    // --- ranking -------------------------------------------------------------

    public record LinhaDoRanking(
            int posicao, String aluno, List<String> turmas, int acertos, int total,
            double percentual, boolean entregueAutomaticamente) {}

    public record Ranking(
            Integer simuladoId, String titulo, Situacao situacao, boolean parcial, int participantes,
            List<LinhaDoRanking> ranking) {}

    /** O ranking completo, que é só do professor. Antes de fechar sai marcado como parcial. */
    @Transactional
    public Ranking ranking(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        var s = resolver(referencia);
        var quando = situacao(s, agora);
        var total = s.getQuestoes().size();

        var linhas = new ArrayList<Tentativa>(tentativas.findBySimulado(s));
        linhas.forEach(t -> t.consolidar(agora));
        linhas.sort((a, b) -> {
            var porAcertos = Integer.compare(b.acertos(), a.acertos());
            return porAcertos != 0 ? porAcertos : a.getAluno().getNome().compareTo(b.getAluno().getNome());
        });

        var turmasDoSimulado = s.getTurmas().stream().map(Turma::getId).toList();
        var ranking = new ArrayList<LinhaDoRanking>();
        var posicao = 0;
        for (int i = 0; i < linhas.size(); i++) {
            var t = linhas.get(i);
            // Empate divide a posição, e a seguinte pula: 1º, 2º, 2º, 4º.
            if (i == 0 || t.acertos() != linhas.get(i - 1).acertos()) {
                posicao = i + 1;
            }
            var turmas = turmasDoSimulado.isEmpty()
                    ? List.<String>of()
                    : tentativas.turmasDoAluno(t.getAluno().getId(), turmasDoSimulado);
            ranking.add(new LinhaDoRanking(posicao, t.getAluno().getNome(), turmas, t.acertos(),
                    total, percentual(t.acertos(), total), t.isEntregueAutomaticamente()));
        }

        return new Ranking(s.getId(), s.getTitulo(), quando, quando != Situacao.ENCERRADO,
                linhas.size(), ranking);
    }

    static double percentual(int acertos, int total) {
        return total == 0
                ? 0.0
                : Double.parseDouble(String.format(Locale.ROOT, "%.1f", 100.0 * acertos / total));
    }
}
