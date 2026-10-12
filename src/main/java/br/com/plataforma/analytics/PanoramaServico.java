package br.com.plataforma.analytics;

import br.com.plataforma.analytics.DevolutivaServico.Etiqueta;
import br.com.plataforma.analytics.DevolutivaServico.Nivel;
import br.com.plataforma.analytics.DevolutivaServico.Origem;
import br.com.plataforma.analytics.DevolutivaServico.RespostaAvaliada;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.progresso.ProgressoServico;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import br.com.plataforma.simulados.Tentativa;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O panorama de um aluno: tudo o que ele respondeu, lido de uma vez. É o chão da análise geral, que
 * é do professor.
 *
 * <p>Sai daqui por duas portas, com os mesmos números: a tela do professor ({@link #panorama}), que
 * desenha os gráficos, e o material que o Claude dele lê para escrever ({@link #resumoGeral}). O
 * resumo de um simulado lista os erros um a um, porque são vinte questões; o de um histórico
 * inteiro não cabe assim, então aqui os erros vêm <b>agrupados por ponto</b>, a questão em branco é
 * contada à parte (dela não se sabe o porquê) e a questão respondida mais de uma vez vira um fato
 * só: errou de novo, ou corrigiu.
 */
@Service
public class PanoramaServico {

    /** Quantos erros comentados vão de exemplo em cada ponto. */
    static final int EXEMPLOS_POR_PONTO = 4;
    /** O teto de exemplos no resumo inteiro: os pontos com mais erro levam primeiro. */
    static final int EXEMPLOS_NO_RESUMO = 40;
    static final int REPETIDAS_NO_RESUMO = 15;

    private final DevolutivaServico devolutiva;
    private final ContasServico contas;
    private final ProgressoServico progresso;
    private final AnaliseDoAlunoRepositorio analises;

    @PersistenceContext
    private EntityManager em;

    public PanoramaServico(DevolutivaServico devolutiva, ContasServico contas, ProgressoServico progresso,
            AnaliseDoAlunoRepositorio analises) {
        this.devolutiva = devolutiva;
        this.contas = contas;
        this.progresso = progresso;
        this.analises = analises;
    }

    // --- os números ----------------------------------------------------------------

    /**
     * {@code respondidas}: as que ele marcou. {@code acertoNoQueRespondeu}: o acerto só sobre elas,
     * que é a leitura do que ele sabe; nulo se não marcou nenhuma. {@code percentual} conta a em
     * branco como erro, como na nota da prova.
     */
    public record SubassuntoNoPanorama(Integer id, String nome, int respostas, int respondidas, int acertos, int emBranco,
            double percentual, Double acertoNoQueRespondeu, Nivel nivel) {}

    /**
     * {@code colegas}: o acerto dos colegas de turma <i>nas mesmas questões</i> daquele assunto,
     * sobre o que eles responderam; nulo quando nenhum colega respondeu nenhuma delas.
     * {@code nivel} sai só do que ele respondeu, e é nulo no assunto em que não marcou nenhuma.
     */
    public record AssuntoNoPanorama(Integer id, String nome, int respostas, int respondidas, int acertos, int emBranco,
            double percentual, Double acertoNoQueRespondeu, Nivel nivel, Double colegas, List<SubassuntoNoPanorama> subassuntos) {}

    /**
     * Uma prova entregue. {@code media} é o acerto médio de quem fez; {@code encerrado} falso: a
     * prova ainda está aberta, e média e posição são parciais.
     */
    public record SimuladoNoPanorama(Integer simuladoId, String titulo, Instant fechouEm, boolean encerrado, int acertos,
            int erros, int emBranco, int total, double percentual, double media, int posicao, int participantes) {}

    /** O que ele já fez do curso das turmas dele. {@code ultimaAtividade}: no curso, não em prova. */
    public record CursoNoPanorama(int concluidos, int total, int videosVistos, int videos, int materiaisLidos, int materiais,
            int questoesRespondidas, int questoes, int questoesCertas, String ultimaAtividade) {}

    /**
     * {@code erros}: marcou a alternativa errada. {@code emBranco}: não marcou nenhuma, numa prova
     * entregue. {@code colegas}: quantos colegas de turma entram na comparação por assunto.
     * {@code respostasNovas}: quantas respostas chegaram depois da análise geral que está valendo.
     */
    public record Panorama(Integer alunoId, String aluno, List<String> turmas, int respostas, int acertos, int erros,
            int emBranco, Double percentual, Double acertoNoQueRespondeu, int daAula, int deSimulado, int colegas, List<AssuntoNoPanorama> porAssunto,
            List<SimuladoNoPanorama> simulados, CursoNoPanorama curso, Instant analiseGeralEm, Integer respostasNovas) {}

    private static Double noQueRespondeu(int acertos, int respondidas) {
        return respondidas == 0 ? null : AnalyticsServico.percentual(acertos, respondidas);
    }

    private static boolean emBranco(RespostaAvaliada r) {
        return r.marcada() == null && !r.correta();
    }

    /** O que as duas portas têm em comum. */
    private record Base(Usuario aluno, List<String> turmas, List<RespostaAvaliada> respostas, Map<Integer, Etiqueta> etiquetas,
            int acertos, int emBranco, int daAula, int colegas, List<AssuntoNoPanorama> porAssunto,
            List<SimuladoNoPanorama> simulados, CursoNoPanorama curso) {}

    private Base base(Identidade ident, Usuario aluno, Instant agora) {
        // A visão do professor: a prova entregue vale mesmo antes de o resultado sair.
        var respostas = devolutiva.respostasDe(List.of(aluno.getId()), false, agora);
        var questoes = respostas.stream().map(RespostaAvaliada::questaoId).collect(Collectors.toSet());
        var etiquetas = devolutiva.etiquetasDas(questoes);

        var turmas = em.createQuery(
                        "select t.nome from Matricula m join m.turma t where m.usuarioId = :aluno and t.removidoEm is null order by t.nome",
                        String.class)
                .setParameter("aluno", aluno.getId()).getResultList();

        // Os colegas, nas mesmas questões: {respostas, acertos} por assunto.
        var dosColegas = new HashMap<Integer, int[]>();
        var quantosColegas = new HashSet<Integer>();
        var idsDasTurmas = contas.turmasDoAluno(aluno.getId());
        if (!idsDasTurmas.isEmpty() && !questoes.isEmpty()) {
            var colegas = em.createQuery(
                            "select distinct m.usuarioId from Matricula m where m.turma.id in :turmas and m.usuarioId <> :aluno",
                            Integer.class)
                    .setParameter("turmas", idsDasTurmas).setParameter("aluno", aluno.getId()).getResultList();
            for (var r : devolutiva.respostasDe(colegas, false, agora)) {
                var e = etiquetas.get(r.questaoId());
                // Como na leitura dele: conta o que o colega respondeu, não o que deixou em branco.
                if (e == null || !questoes.contains(r.questaoId()) || emBranco(r)) {
                    continue;
                }
                quantosColegas.add(r.alunoId());
                var c = dosColegas.computeIfAbsent(e.assuntoId(), k -> new int[2]);
                c[0]++;
                c[1] += r.correta() ? 1 : 0;
            }
        }

        var brancosPorAssunto = new HashMap<Integer, Integer>();
        var brancosPorSub = new HashMap<Integer, Integer>();
        for (var r : respostas) {
            var e = etiquetas.get(r.questaoId());
            if (e == null || !emBranco(r)) {
                continue;
            }
            brancosPorAssunto.merge(e.assuntoId(), 1, Integer::sum);
            if (e.subassuntoId() != null) {
                brancosPorSub.merge(e.subassuntoId(), 1, Integer::sum);
            }
        }
        // A leitura de cada assunto é sobre o que ele respondeu: o nível e a ordem saem só das
        // questões em que marcou alguma coisa. O que ficou em branco vem contado ao lado, e o assunto
        // em que ele não marcou nenhuma entra no fim, sem nível.
        var nomesDosAssuntos = new LinkedHashMap<Integer, String>();
        var subsDoAssunto = new HashMap<Integer, Map<Integer, String>>();
        for (var e : etiquetas.values()) {
            nomesDosAssuntos.putIfAbsent(e.assuntoId(), e.assunto());
            if (e.subassuntoId() != null) {
                subsDoAssunto.computeIfAbsent(e.assuntoId(), k -> new LinkedHashMap<>()).putIfAbsent(e.subassuntoId(), e.subassunto());
            }
        }
        java.util.function.BiFunction<Integer, java.util.Set<Integer>, List<SubassuntoNoPanorama>> soEmBranco = (assuntoId, jaLidos) ->
                subsDoAssunto.getOrDefault(assuntoId, Map.of()).entrySet().stream()
                        .filter(par -> !jaLidos.contains(par.getKey()) && brancosPorSub.getOrDefault(par.getKey(), 0) > 0)
                        .map(par -> {
                            var brancos = brancosPorSub.get(par.getKey());
                            return new SubassuntoNoPanorama(par.getKey(), par.getValue(), brancos, 0, 0, brancos, 0.0, null, null);
                        }).toList();
        var respondidas = respostas.stream().filter(r -> !emBranco(r)).toList();
        var porAssunto = new ArrayList<AssuntoNoPanorama>();
        var lidos = new HashSet<Integer>();
        for (var a : devolutiva.semMaterial(respondidas, agora).assuntos()) {
            lidos.add(a.id());
            var c = dosColegas.get(a.id());
            var brancos = brancosPorAssunto.getOrDefault(a.id(), 0);
            var subs = new ArrayList<SubassuntoNoPanorama>();
            var subsLidos = new HashSet<Integer>();
            for (var sub : a.subassuntos()) {
                subsLidos.add(sub.id());
                var doSub = brancosPorSub.getOrDefault(sub.id(), 0);
                subs.add(new SubassuntoNoPanorama(sub.id(), sub.nome(), sub.respostas() + doSub, sub.respostas(), sub.acertos(), doSub,
                        AnalyticsServico.percentual(sub.acertos(), sub.respostas() + doSub), sub.percentual(), sub.nivel()));
            }
            subs.addAll(soEmBranco.apply(a.id(), subsLidos));
            porAssunto.add(new AssuntoNoPanorama(a.id(), a.nome(), a.respostas() + brancos, a.respostas(), a.acertos(), brancos,
                    AnalyticsServico.percentual(a.acertos(), a.respostas() + brancos), a.percentual(), a.nivel(),
                    c == null ? null : AnalyticsServico.percentual(c[1], c[0]), subs));
        }
        nomesDosAssuntos.forEach((id, nome) -> {
            var brancos = brancosPorAssunto.getOrDefault(id, 0);
            if (lidos.contains(id) || brancos == 0) {
                return;
            }
            var c = dosColegas.get(id);
            porAssunto.add(new AssuntoNoPanorama(id, nome, brancos, 0, 0, brancos, 0.0, null, null,
                    c == null ? null : AnalyticsServico.percentual(c[1], c[0]), soEmBranco.apply(id, java.util.Set.of())));
        });

        return new Base(aluno, turmas, respostas, etiquetas,
                (int) respostas.stream().filter(RespostaAvaliada::correta).count(),
                (int) respostas.stream().filter(PanoramaServico::emBranco).count(),
                (int) respostas.stream().filter(r -> r.origem() == Origem.AULA).count(),
                quantosColegas.size(), porAssunto, simulados(aluno, agora), curso(ident, aluno, agora));
    }

    /**
     * As provas que ele entregou, da mais antiga para a mais nova, cada uma com a média e a posição.
     * Os acertos de todos os participantes saem de uma consulta só, para a conta não crescer com o
     * número de provas.
     */
    private List<SimuladoNoPanorama> simulados(Usuario aluno, Instant agora) {
        var entregues = new ArrayList<Tentativa>();
        for (var t : em.createQuery("select t from Tentativa t where t.aluno.id = :aluno and t.simulado.removidoEm is null",
                Tentativa.class).setParameter("aluno", aluno.getId()).getResultList()) {
            var encerrado = SimuladosServico.situacao(t.getSimulado(), agora) == Situacao.ENCERRADO;
            if ((t.consolidar(agora) || encerrado) && !t.getSimulado().getQuestoes().isEmpty()) {
                entregues.add(t);
            }
        }
        if (entregues.isEmpty()) {
            return List.of();
        }
        var acertosDeTodos = new HashMap<Integer, List<Integer>>();
        em.createQuery("""
                select t.simulado.id, t.id, sum(case when r.correta = true then 1 else 0 end)
                  from Tentativa t left join t.respostas r
                 where t.simulado.id in :simulados group by t.simulado.id, t.id""", Object[].class)
                .setParameter("simulados", entregues.stream().map(t -> t.getSimulado().getId()).toList()).getResultList()
                .forEach(l -> acertosDeTodos.computeIfAbsent((Integer) l[0], k -> new ArrayList<>())
                        .add(l[2] == null ? 0 : ((Number) l[2]).intValue()));

        return entregues.stream()
                .sorted(Comparator.comparing((Tentativa t) -> t.getSimulado().getFechaEm(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(t -> t.getSimulado().getId()))
                .map(t -> {
                    Simulado s = t.getSimulado();
                    var total = s.getQuestoes().size();
                    var acertos = t.acertos();
                    var brancos = total - t.getRespostas().size();
                    var todos = acertosDeTodos.getOrDefault(s.getId(), List.of(acertos));
                    // Empate divide a posição, como no ranking da prova.
                    var posicao = 1 + (int) todos.stream().filter(a -> a > acertos).count();
                    var media = todos.stream().mapToDouble(a -> 100.0 * a / total).average().orElse(0);
                    return new SimuladoNoPanorama(s.getId(), s.getTitulo(), s.getFechaEm(),
                            SimuladosServico.situacao(s, agora) == Situacao.ENCERRADO, acertos, total - acertos - brancos, brancos,
                            total, AnalyticsServico.percentual(acertos, total), AnalyticsServico.arredondar(media), posicao, todos.size());
                }).toList();
    }

    /** O curso das turmas dele, somado. A mesma linha em duas turmas conta uma vez. */
    private CursoNoPanorama curso(Identidade ident, Usuario aluno, Instant agora) {
        var doAluno = progresso.doAluno(ident, String.valueOf(aluno.getId()), agora);
        var itens = new LinkedHashMap<Integer, ProgressoServico.ItemDoAluno>();
        doAluno.turmas().forEach(t -> t.modulos().forEach(m -> m.submodulos().forEach(s -> s.itens().forEach(i ->
                itens.merge(i.id(), i, (a, b) -> a.concluido() ? a : b)))));
        var conta = new HashMap<String, int[]>();
        var certas = 0;
        for (var i : itens.values()) {
            var c = conta.computeIfAbsent(i.tipo(), k -> new int[2]);
            c[0]++;
            c[1] += i.concluido() ? 1 : 0;
            certas += Boolean.TRUE.equals(i.correta()) ? 1 : 0;
        }
        var videos = conta.getOrDefault("VIDEO", new int[2]);
        var materiais = conta.getOrDefault("PDF", new int[2]);
        var questoes = conta.getOrDefault("QUESTAO", new int[2]);
        return new CursoNoPanorama(videos[1] + materiais[1] + questoes[1], itens.size(), videos[1], videos[0], materiais[1],
                materiais[0], questoes[1], questoes[0], certas, doAluno.ultimaAtividade());
    }

    private static Integer novasDesde(AnaliseDoAluno analise, List<RespostaAvaliada> respostas) {
        return analise == null ? null : Math.max(0, respostas.size() - analise.getRespostas());
    }

    /** Os números do aluno para a tela do professor. */
    @Transactional
    public Panorama panorama(Identidade ident, String alunoRef, Instant agora) {
        ident.exigirOperador();
        var b = base(ident, contas.resolverAluno(alunoRef), agora);
        var geral = analises.findByAlunoIdAndSimuladoIdIsNullOrderByIdDesc(b.aluno().getId()).stream().findFirst().orElse(null);
        var total = b.respostas().size();
        return new Panorama(b.aluno().getId(), b.aluno().getNome(), b.turmas(), total, b.acertos(),
                total - b.acertos() - b.emBranco(), b.emBranco(), total == 0 ? null : AnalyticsServico.percentual(b.acertos(), total),
                noQueRespondeu(b.acertos(), total - b.emBranco()), b.daAula(), total - b.daAula(), b.colegas(), b.porAssunto(), b.simulados(), b.curso(),
                geral == null ? null : geral.getEscritaEm(), novasDesde(geral, b.respostas()));
    }

    // --- o material da análise geral -----------------------------------------------

    /** Um erro em que ele marcou uma alternativa, com o que o professor escreveu para ela. */
    public record ErroNoPonto(Integer questaoId, String onde, String enunciado, Letra marcada, Letra gabarito,
            @JsonInclude(JsonInclude.Include.NON_NULL) String comentarioDaMarcada,
            @JsonInclude(JsonInclude.Include.NON_NULL) String comentarioDaCorreta) {}

    /**
     * Um ponto (sub-assunto, ou o assunto que não tem) em que ele errou. {@code marcouErrado} e
     * {@code emBranco} somam os erros; os {@code exemplos} são só dos que ele marcou, os comentados
     * primeiro. {@code emBrancoEm} diz onde ficaram as que ele não marcou ("Simulado 03: questões
     * 7 e 17"). {@code subassunto} nulo: a questão só tem o assunto.
     */
    public record PontoDeErro(String assunto, String subassunto, int respostas, int respondidas, int acertos, int marcouErrado,
            int emBranco, List<String> emBrancoEm, List<ErroNoPonto> exemplos) {}

    /** Uma das vezes em que ele respondeu a mesma questão. {@code marcada} nula: deixou em branco. */
    public record Vez(String onde, Letra marcada, boolean acertou) {}

    /**
     * A mesma questão respondida mais de uma vez, da primeira para a última. {@code leitura}:
     * {@code ERROU_DE_NOVO}, {@code CORRIGIU} (errava e passou a acertar) ou {@code VOLTOU_A_ERRAR}.
     */
    public record Repetida(Integer questaoId, String assunto, String subassunto, String enunciado, List<Vez> vezes,
            String leitura) {}

    /**
     * O resumo de tudo o que o aluno respondeu, para quem vai escrever a análise geral dele.
     *
     * <p>{@code marcouErradoSemComentario}: dos erros marcados, em quantos o professor não comentou a
     * alternativa — neles só se sabe que errou. {@code repetidas} traz as questões respondidas mais
     * de uma vez em que ele marcou alguma coisa; as que ficaram em branco todas as vezes são só
     * contadas, em {@code repetidasEmBranco}, e {@code repetidasForaDoResumo} diz quantas não
     * couberam na lista. {@code noCurso} só tem os pontos em que há material: ponto de fora não tem
     * nada no curso do aluno.
     */
    public record ResumoGeral(Integer alunoId, String aluno, List<String> turmas, int respostas, int respondidas, int acertos,
            int marcouErrado, int marcouErradoSemComentario, int emBranco, Double percentual, Double acertoNoQueRespondeu,
            CursoNoPanorama curso,
            List<SimuladoNoPanorama> simulados, int colegas, List<AssuntoNoPanorama> porAssunto, List<PontoDeErro> ondeErra,
            List<Repetida> repetidas, int repetidasEmBranco, int repetidasForaDoResumo,
            List<AnaliseServico.ParaRevisar> noCurso, AnaliseServico.Texto analiseAnterior, Integer respostasNovas)
            implements AnaliseServico.Resumo {}

    private static final Comparator<RespostaAvaliada> POR_DATA =
            Comparator.comparing(RespostaAvaliada::quando, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private record Ponto(Integer assuntoId, Integer subassuntoId) {}

    ResumoGeral resumoGeral(Identidade ident, Usuario aluno, AnaliseDoAluno anterior, Instant agora) {
        var b = base(ident, aluno, agora);
        var respostas = b.respostas();
        var etiquetas = b.etiquetas();

        // Onde cada resposta foi dada, pelo nome que o aluno vê.
        var linhas = new HashMap<Integer, String>();
        var idsDasLinhas = respostas.stream().map(RespostaAvaliada::itemId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!idsDasLinhas.isEmpty()) {
            em.createQuery("select i.id, i.nome from Item i where i.id in :ids", Object[].class)
                    .setParameter("ids", idsDasLinhas).getResultList().forEach(l -> linhas.put((Integer) l[0], (String) l[1]));
        }
        java.util.function.Function<RespostaAvaliada, String> onde = r ->
                r.origem() == Origem.SIMULADO ? "%s, questão %d".formatted(r.prova(), r.numero())
                        : linhas.containsKey(r.itemId()) ? "aula: " + linhas.get(r.itemId()) : "aula";

        // As vezes de cada questão, da primeira para a última.
        var porQuestao = new LinkedHashMap<Integer, List<RespostaAvaliada>>();
        respostas.stream().sorted(POR_DATA.thenComparing(r -> r.simuladoId() == null ? 0 : r.simuladoId()))
                .forEach(r -> porQuestao.computeIfAbsent(r.questaoId(), k -> new ArrayList<>()).add(r));

        // {respostas, acertos, marcou errado, em branco} de cada ponto, e o último erro marcado em cada questão.
        var conta = new LinkedHashMap<Ponto, int[]>();
        var marcados = new LinkedHashMap<Ponto, Map<Integer, RespostaAvaliada>>();
        // As que ficaram em branco em cada ponto, por prova: {prova -> números das questões}.
        var brancos = new HashMap<Ponto, Map<String, List<Integer>>>();
        for (var r : respostas) {
            var e = etiquetas.get(r.questaoId());
            var ponto = e == null ? new Ponto(null, null) : new Ponto(e.assuntoId(), e.subassuntoId());
            var c = conta.computeIfAbsent(ponto, k -> new int[4]);
            c[0]++;
            c[1] += r.correta() ? 1 : 0;
            c[3] += emBranco(r) ? 1 : 0;
            if (emBranco(r) && r.prova() != null) {
                brancos.computeIfAbsent(ponto, k -> new LinkedHashMap<>()).computeIfAbsent(r.prova(), k -> new ArrayList<>())
                        .add(r.numero());
            }
            if (!r.correta() && r.marcada() != null) {
                c[2]++;
                marcados.computeIfAbsent(ponto, k -> new HashMap<>())
                        .merge(r.questaoId(), r, (a, outra) -> POR_DATA.compare(outra, a) >= 0 ? outra : a);
            }
        }

        // Respondida mais de uma vez e errada em alguma. A que ficou em branco todas as vezes não diz
        // nada além de "em branco de novo": é só contada.
        var maisDeUmaVez = porQuestao.entrySet().stream()
                .filter(par -> par.getValue().size() > 1 && par.getValue().stream().anyMatch(r -> !r.correta())).toList();
        var repetidas = maisDeUmaVez.stream().filter(par -> par.getValue().stream().anyMatch(r -> r.marcada() != null)).toList();
        var repetidasEmBranco = maisDeUmaVez.size() - repetidas.size();
        var questoes = new HashMap<Integer, Questao>();
        var idsDasQuestoes = new HashSet<Integer>();
        marcados.values().forEach(m -> idsDasQuestoes.addAll(m.keySet()));
        repetidas.forEach(par -> idsDasQuestoes.add(par.getKey()));
        if (!idsDasQuestoes.isEmpty()) {
            em.createQuery("select q from Questao q where q.id in :ids", Questao.class)
                    .setParameter("ids", idsDasQuestoes).getResultList().forEach(q -> questoes.put(q.getId(), q));
        }

        // Os pontos em que ele mais marcou errado primeiro: são eles que levam os exemplos enquanto
        // houver espaço. O ponto que só tem questão em branco fica no fim.
        var ondeErra = new ArrayList<PontoDeErro>();
        var espaco = new int[] {EXEMPLOS_NO_RESUMO};
        conta.entrySet().stream().filter(par -> par.getValue()[2] + par.getValue()[3] > 0)
                .sorted(Comparator.comparingInt((Map.Entry<Ponto, int[]> par) -> -par.getValue()[2])
                        .thenComparingInt(par -> -par.getValue()[3]))
                .forEach(par -> {
                    var c = par.getValue();
                    var exemplos = marcados.getOrDefault(par.getKey(), Map.of()).values().stream()
                            // A questão que saiu do acervo continua na conta, mas não há o que dizer dela.
                            .filter(r -> questoes.containsKey(r.questaoId()))
                            .sorted(Comparator.comparing((RespostaAvaliada r) -> !questoes.get(r.questaoId()).comentarios().containsKey(r.marcada()))
                                    .thenComparing(POR_DATA.reversed()))
                            .limit(Math.min(EXEMPLOS_POR_PONTO, espaco[0]))
                            .map(r -> {
                                var q = questoes.get(r.questaoId());
                                return new ErroNoPonto(q.getId(), onde.apply(r), AnaliseServico.emUmaLinha(q.getEnunciado()), r.marcada(),
                                        q.getGabarito(), q.comentarios().get(r.marcada()), q.comentarios().get(q.getGabarito()));
                            }).toList();
                    espaco[0] -= exemplos.size();
                    var e = etiquetas.values().stream()
                            .filter(x -> Objects.equals(x.assuntoId(), par.getKey().assuntoId())
                                    && Objects.equals(x.subassuntoId(), par.getKey().subassuntoId()))
                            .findFirst().orElse(null);
                    var emBrancoEm = brancos.getOrDefault(par.getKey(), Map.of()).entrySet().stream()
                            .map(b2 -> "%s: %s %s".formatted(b2.getKey(), b2.getValue().size() == 1 ? "questão" : "questões",
                                    b2.getValue().stream().sorted().map(String::valueOf).collect(Collectors.joining(", "))))
                            .toList();
                    ondeErra.add(new PontoDeErro(e == null ? "Sem assunto" : e.assunto(), e == null ? null : e.subassunto(),
                            c[0], c[0] - c[3], c[1], c[2], c[3], emBrancoEm, exemplos));
                });

        var comQuestao = repetidas.stream().filter(par -> questoes.containsKey(par.getKey())).toList();
        var semComentario = (int) respostas.stream().filter(r -> !r.correta() && r.marcada() != null)
                .filter(r -> !questoes.containsKey(r.questaoId()) || !questoes.get(r.questaoId()).comentarios().containsKey(r.marcada()))
                .count();
        var lidas = comQuestao.stream().map(par -> {
            var vezes = par.getValue();
            var e = etiquetas.get(par.getKey());
            var ultima = vezes.getLast().correta();
            var leitura = ultima ? "CORRIGIU" : vezes.stream().noneMatch(RespostaAvaliada::correta) ? "ERROU_DE_NOVO" : "VOLTOU_A_ERRAR";
            return new Repetida(par.getKey(), e == null ? null : e.assunto(), e == null ? null : e.subassunto(),
                    AnaliseServico.emUmaLinha(questoes.get(par.getKey()).getEnunciado()),
                    vezes.stream().map(r -> new Vez(onde.apply(r), r.marcada(), r.correta())).toList(), leitura);
        }).limit(REPETIDAS_NO_RESUMO).toList();

        var total = respostas.size();
        return new ResumoGeral(aluno.getId(), aluno.getNome(), b.turmas(), total, total - b.emBranco(), b.acertos(),
                total - b.acertos() - b.emBranco(), semComentario, b.emBranco(),
                total == 0 ? null : AnalyticsServico.percentual(b.acertos(), total),
                noQueRespondeu(b.acertos(), total - b.emBranco()),
                b.curso(), b.simulados(), b.colegas(), b.porAssunto(), ondeErra, lidas, repetidasEmBranco,
                comQuestao.size() - lidas.size(),
                // O que indicar do curso segue os erros que ele marcou: em branco não é ponto fraco.
                AnaliseServico.paraRevisar(devolutiva.comoOAlunoVe(aluno, respostas.stream().filter(r -> !emBranco(r)).toList(), agora),
                        DevolutivaServico.PONTOS_PARA_REVISAR, true),
                AnaliseServico.texto(anterior), novasDesde(anterior, respostas));
    }
}
