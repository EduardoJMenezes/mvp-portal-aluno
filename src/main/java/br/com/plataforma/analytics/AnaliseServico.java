package br.com.plataforma.analytics;

import br.com.plataforma.analytics.DevolutivaServico.Nivel;
import br.com.plataforma.analytics.DevolutivaServico.Origem;
import br.com.plataforma.analytics.DevolutivaServico.RespostaAvaliada;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Papel;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A análise do aluno: o retrato dele em texto, que junta aula e simulado e se renova com o tempo.
 *
 * <p><b>A plataforma conta; quem escreve é o Claude do professor.</b> Este serviço não chama modelo
 * nenhum. Ele monta o resumo de cada aluno — o acerto por assunto, os erros com o comentário da
 * alternativa marcada, o que há no curso para revisar e a análise anterior —, entrega pelo MCP e
 * guarda o texto que volta. O aluno abre o que está guardado.
 *
 * <p><b>É a exceção escrita à regra da aprovação.</b> O texto vai ao aluno sem um professor aprovar
 * um por um, porque sai todo dia e para a turma inteira. Em troca: quem escreve só recebe material
 * que o professor já aprovou (os comentários das alternativas), o aluno lê que foi escrito por IA,
 * e o professor vê a análise de qualquer aluno e pode ocultar ou pedir outra. Conteúdo do curso
 * continua passando por rascunho; nada daqui publica questão, aula ou prova.
 *
 * <p><b>Só o que o aluno já pode ver.</b> O resumo é a devolutiva como ele a vê: simulado aberto não
 * entra, senão a análise contaria o resultado antes da hora.
 */
@Service
public class AnaliseServico {

    /** Menos que isso não sustenta um retrato: o aluno fica fora da fila. */
    static final int MINIMO_DE_RESPOSTAS = 5;
    /** Quantas respostas novas fazem a análise ser reescrita. Simulado que fecha reescreve sempre. */
    static final int NOVIDADE = 5;
    /** Os erros mais recentes que vão no resumo, com o comentário de cada um. */
    static final int ERROS_NO_RESUMO = 20;
    public static final int LOTE_PADRAO = 5;
    public static final int LOTE_MAXIMO = 10;
    static final int TAMANHO_DO_PONTO = 700;
    static final int TAMANHO_DO_BLOCO = 1200;
    static final int TAMANHO_DA_ORIENTACAO = 500;

    private final AnaliseDoAlunoRepositorio analises;
    private final DevolutivaServico devolutiva;
    private final ContasServico contas;

    @PersistenceContext
    private EntityManager em;

    public AnaliseServico(AnaliseDoAlunoRepositorio analises, DevolutivaServico devolutiva, ContasServico contas) {
        this.analises = analises;
        this.devolutiva = devolutiva;
        this.contas = contas;
    }

    // --- quem está na fila ---------------------------------------------------------

    /** O que o aluno tem respondido, e quanto disso chegou depois da última análise. */
    private record Movimento(int total, int novas, String simuladoQueFechou) {
        static final Movimento NADA = new Movimento(0, 0, null);
    }

    private static boolean depois(AnaliseDoAluno ultima, Instant quando) {
        return ultima == null || (quando != null && quando.isAfter(ultima.getEscritaEm()));
    }

    /**
     * A conta de cada aluno em duas consultas, sem carregar resposta por resposta: a fila é
     * conferida para a turma inteira, e o resumo completo só é montado para quem sai dela.
     * Segue a regra de {@link DevolutivaServico#respostasDe}: no simulado encerrado, toda questão
     * conta (em branco é erro), e a resposta passa a valer quando ele fecha.
     */
    private Map<Integer, Movimento> movimentos(Collection<Integer> alunos, Map<Integer, AnaliseDoAluno> ultimas, Instant agora) {
        var conta = new HashMap<Integer, int[]>();
        var fechou = new HashMap<Integer, String>();
        if (alunos.isEmpty()) {
            return Map.of();
        }
        em.createQuery("select r.alunoId, r.respondidoEm from RespostaDeExercicio r where r.alunoId in :alunos", Object[].class)
                .setParameter("alunos", alunos).getResultList().forEach(l -> {
                    var c = conta.computeIfAbsent((Integer) l[0], k -> new int[2]);
                    c[0]++;
                    if (depois(ultimas.get((Integer) l[0]), (Instant) l[1])) {
                        c[1]++;
                    }
                });
        em.createQuery("""
                select t.aluno.id, s from Tentativa t join t.simulado s
                 where t.aluno.id in :alunos and s.removidoEm is null""", Object[].class)
                .setParameter("alunos", alunos).getResultList().forEach(l -> {
                    var s = (Simulado) l[1];
                    if (SimuladosServico.situacao(s, agora) != Situacao.ENCERRADO) {
                        return;
                    }
                    var id = (Integer) l[0];
                    var c = conta.computeIfAbsent(id, k -> new int[2]);
                    var questoes = s.getQuestoes().size();
                    c[0] += questoes;
                    if (depois(ultimas.get(id), s.getFechaEm())) {
                        c[1] += questoes;
                        fechou.putIfAbsent(id, s.getTitulo());
                    }
                });
        var saida = new HashMap<Integer, Movimento>();
        conta.forEach((id, c) -> saida.put(id, new Movimento(c[0], c[1], fechou.get(id))));
        return saida;
    }

    private record NaFila(Usuario aluno, AnaliseDoAluno ultima, Movimento movimento, String motivo, boolean pedida) {}

    /** O aluno na fila, com o motivo; nulo quando não há o que escrever de novo. */
    private static NaFila avaliar(Usuario aluno, AnaliseDoAluno ultima, Movimento m) {
        if (m.total() < MINIMO_DE_RESPOSTAS) {
            return null;
        }
        if (ultima == null) {
            return new NaFila(aluno, null, m, "primeira análise", false);
        }
        if (ultima.getRefazerPedidoEm() != null) {
            return new NaFila(aluno, ultima, m, "o professor pediu outra", true);
        }
        if (m.simuladoQueFechou() != null) {
            return new NaFila(aluno, ultima, m, "fechou o simulado '%s'".formatted(m.simuladoQueFechou()), false);
        }
        if (m.novas() >= NOVIDADE) {
            return new NaFila(aluno, ultima, m, "%d respostas novas".formatted(m.novas()), false);
        }
        return null;
    }

    private static Map<Integer, AnaliseDoAluno> porAluno(List<AnaliseDoAluno> ultimas) {
        return ultimas.stream().collect(Collectors.toMap(AnaliseDoAluno::getAlunoId, a -> a));
    }

    /** Quem tem o que analisar: o que o professor pediu primeiro, depois quem tem mais novidade. */
    private List<NaFila> fila(Instant agora) {
        // Só quem está em alguma turma: aluno que saiu não precisa de análise nova.
        var alunos = em.createQuery("""
                select u from Usuario u
                 where u.papel = :papel and u.id in (select m.usuarioId from Matricula m)""", Usuario.class)
                .setParameter("papel", Papel.ALUNO).getResultList();
        var ids = alunos.stream().map(Usuario::getId).toList();
        var ultimas = porAluno(analises.ultimas());
        var movimentos = movimentos(ids, ultimas, agora);
        return alunos.stream()
                .map(a -> avaliar(a, ultimas.get(a.getId()), movimentos.getOrDefault(a.getId(), Movimento.NADA)))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((NaFila f) -> !f.pedida())
                        .thenComparing(f -> -f.movimento().novas())
                        .thenComparing(f -> f.aluno().getId()))
                .toList();
    }

    // --- o resumo de um aluno ------------------------------------------------------

    public record SubassuntoNoResumo(String nome, int respostas, int acertos, Nivel nivel, int novasRespostas, int novosAcertos) {}

    /** {@code novas*}: o que chegou depois da última análise — é daí que sai "o que melhorou". */
    public record AssuntoNoResumo(String nome, int respostas, int acertos, Nivel nivel, int novasRespostas, int novosAcertos,
            List<SubassuntoNoResumo> subassuntos) {}

    /**
     * Um erro do aluno. {@code marcada} nula: deixou em branco. Os comentários são os que o
     * professor aprovou; nulo é "sem comentário", e aí só se sabe que ele errou.
     */
    public record ErroDoAluno(Integer questaoId, String onde, String assunto, String subassunto, String enunciado,
            Letra marcada, Letra gabarito, String comentarioDaMarcada, String comentarioDaCorreta, boolean novo) {}

    /** O que há no curso do aluno sobre um ponto fraco, pelo nome que ele vê na tela. */
    public record ParaRevisar(String topico, int erros, List<String> assistir, List<String> ler, List<String> praticar,
            List<String> rever) {}

    public record Texto(String pontoPrincipal, String padroes, String melhorou, String proximosPassos, Instant escritaEm) {}

    public record AlunoPendente(Integer alunoId, String aluno, String motivo, int respostas, int acertos, Double percentual,
            int novasRespostas, List<AssuntoNoResumo> porAssunto, int erros, List<ErroDoAluno> errosRecentes,
            List<ParaRevisar> noCurso, Texto analiseAnterior, String orientacaoDoProfessor) {}

    /** {@code naFila}: todos os que esperam análise; {@code restam}: os que não couberam nesta resposta. */
    public record Pendentes(int naFila, List<AlunoPendente> alunos, int restam) {}

    private static Texto texto(AnaliseDoAluno a) {
        return a == null ? null
                : new Texto(a.getPontoPrincipal(), a.getPadroes(), a.getMelhorou(), a.getProximosPassos(), a.getEscritaEm());
    }

    /** O enunciado em uma linha: sem a marca da figura e sem quebras, para caber no resumo. */
    static String emUmaLinha(String enunciado) {
        var limpo = enunciado.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ").replaceAll("\\s+", " ").strip();
        return limpo.length() > 160 ? limpo.substring(0, 160) + "…" : limpo;
    }

    private AlunoPendente resumir(NaFila f, Instant agora) {
        var aluno = f.aluno();
        var respostas = devolutiva.respostasDe(List.of(aluno.getId()), true, agora);
        var d = devolutiva.comoOAlunoVe(aluno, respostas, agora);
        var etiquetas = devolutiva.etiquetasDas(respostas.stream().map(RespostaAvaliada::questaoId).collect(Collectors.toSet()));

        // O que chegou depois da última análise, por assunto e por sub-assunto.
        var novasDoAssunto = new HashMap<Integer, int[]>();
        var novasDoSub = new HashMap<Integer, int[]>();
        for (var r : respostas) {
            var e = etiquetas.get(r.questaoId());
            if (e == null || !depois(f.ultima(), r.quando())) {
                continue;
            }
            var a = novasDoAssunto.computeIfAbsent(e.assuntoId(), k -> new int[2]);
            a[0]++;
            a[1] += r.correta() ? 1 : 0;
            if (e.subassuntoId() != null) {
                var s = novasDoSub.computeIfAbsent(e.subassuntoId(), k -> new int[2]);
                s[0]++;
                s[1] += r.correta() ? 1 : 0;
            }
        }
        var semNada = new int[2];
        var porAssunto = d.assuntos().stream().map(a -> {
            var n = novasDoAssunto.getOrDefault(a.id(), semNada);
            return new AssuntoNoResumo(a.nome(), a.respostas(), a.acertos(), a.nivel(), n[0], n[1],
                    a.subassuntos().stream().map(s -> {
                        var ns = novasDoSub.getOrDefault(s.id(), semNada);
                        return new SubassuntoNoResumo(s.nome(), s.respostas(), s.acertos(), s.nivel(), ns[0], ns[1]);
                    }).toList());
        }).toList();

        // Os erros, do mais recente para o mais antigo, com o comentário de cada alternativa.
        var errados = respostas.stream().filter(r -> !r.correta())
                .sorted(Comparator.comparing(RespostaAvaliada::quando, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed())
                .toList();
        var noResumo = errados.stream().limit(ERROS_NO_RESUMO).toList();
        var questoes = new HashMap<Integer, Questao>();
        var idsDasQuestoes = noResumo.stream().map(RespostaAvaliada::questaoId).collect(Collectors.toSet());
        if (!idsDasQuestoes.isEmpty()) {
            em.createQuery("select q from Questao q where q.id in :ids", Questao.class)
                    .setParameter("ids", idsDasQuestoes).getResultList().forEach(q -> questoes.put(q.getId(), q));
        }
        var linhas = new HashMap<Integer, String>();
        var idsDasLinhas = noResumo.stream().map(RespostaAvaliada::itemId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!idsDasLinhas.isEmpty()) {
            em.createQuery("select i.id, i.nome from Item i where i.id in :ids", Object[].class)
                    .setParameter("ids", idsDasLinhas).getResultList().forEach(l -> linhas.put((Integer) l[0], (String) l[1]));
        }
        var erros = new ArrayList<ErroDoAluno>();
        for (var r : noResumo) {
            var q = questoes.get(r.questaoId());
            if (q == null) {
                // A questão saiu do acervo: o erro continua na conta, mas não há o que dizer dele.
                continue;
            }
            var e = etiquetas.get(r.questaoId());
            var comentarios = q.comentarios();
            var onde = r.origem() == Origem.SIMULADO ? "%s, questão %d".formatted(r.prova(), r.numero())
                    : linhas.containsKey(r.itemId()) ? "aula: " + linhas.get(r.itemId()) : "aula";
            erros.add(new ErroDoAluno(q.getId(), onde, e == null ? null : e.assunto(), e == null ? null : e.subassunto(),
                    emUmaLinha(q.getEnunciado()), r.marcada(), q.getGabarito(),
                    r.marcada() == null ? null : comentarios.get(r.marcada()), comentarios.get(q.getGabarito()),
                    f.ultima() != null && depois(f.ultima(), r.quando())));
        }

        var noCurso = d.ondeRevisar().stream().map(p -> new ParaRevisar(p.topico(), p.erros(),
                java.util.stream.Stream.concat(
                        nomes(p, "VIDEO").stream(),
                        p.videos().stream().filter(v -> !v.bloqueado()).map(v -> v.titulo())).toList(),
                nomes(p, "PDF"), nomes(p, "QUESTAO"), nomes(p, "ERRO"))).toList();

        return new AlunoPendente(aluno.getId(), aluno.getNome(), f.motivo(), d.respostas(), d.acertos(), d.percentual(),
                f.ultima() == null ? 0 : f.movimento().novas(), porAssunto, errados.size(), erros, noCurso, texto(f.ultima()),
                f.ultima() == null ? null : f.ultima().getRefazerOrientacao());
    }

    private static List<String> nomes(AnalyticsServico.Recomendacao ponto, String tipo) {
        return ponto.noCurso().stream().filter(l -> tipo.equals(l.tipo()))
                .map(l -> "%s (%s)".formatted(l.nome(), l.modulo())).toList();
    }

    /**
     * Os próximos alunos da fila, cada um com o resumo que sustenta a análise. Com {@code alunoRef},
     * só aquele aluno, esteja ou não na fila: é o professor pedindo a análise de alguém.
     */
    @Transactional
    public Pendentes pendentes(Identidade ident, Integer limite, String alunoRef, Instant agora) {
        ident.exigirOperador();
        if (alunoRef != null && !alunoRef.isBlank()) {
            var aluno = contas.resolverAluno(alunoRef);
            var ultima = analises.findFirstByAlunoIdOrderByIdDesc(aluno.getId()).orElse(null);
            var ultimas = ultima == null ? Map.<Integer, AnaliseDoAluno>of() : Map.of(aluno.getId(), ultima);
            var m = movimentos(List.of(aluno.getId()), ultimas, agora).getOrDefault(aluno.getId(), Movimento.NADA);
            if (m.total() == 0) {
                throw new RegraDeNegocio(("%s ainda não tem resposta que conte: nem questão de aula, nem simulado "
                        + "já encerrado. Não há o que analisar.").formatted(aluno.getNome()));
            }
            var naFila = avaliar(aluno, ultima, m);
            return new Pendentes(1, List.of(resumir(
                    naFila != null ? naFila : new NaFila(aluno, ultima, m, "pedida pelo professor", true), agora)), 0);
        }
        var fila = fila(agora);
        var quantos = Math.min(limite == null ? LOTE_PADRAO : limite, LOTE_MAXIMO);
        var alunos = fila.stream().limit(quantos).map(f -> resumir(f, agora)).toList();
        return new Pendentes(fila.size(), alunos, fila.size() - alunos.size());
    }

    // --- gravar --------------------------------------------------------------------

    public record AnaliseNova(Integer alunoId, String pontoPrincipal, String padroes, String melhorou, String proximosPassos) {}

    public record Recusada(Integer alunoId, String motivo) {}

    /** {@code restamNaFila}: quantos alunos ainda esperam análise depois deste envio. */
    public record Gravadas(int gravadas, List<String> alunos, List<Recusada> recusadas, int restamNaFila) {}

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private static String grande(String campo, String texto, int limite) {
        return texto != null && texto.length() > limite
                ? "%s tem %d caracteres; o limite é %d. Encurte.".formatted(campo, texto.length(), limite) : null;
    }

    /**
     * Guarda as análises escritas. Cada uma vale por si: a que não passa volta com o motivo, e as
     * outras entram. A análise nova fica visível para o aluno na hora e atende o pedido de outra.
     */
    @Transactional
    public Gravadas gravar(Identidade ident, List<AnaliseNova> novas, Instant agora) {
        ident.exigirOperador();
        var gravadas = new ArrayList<String>();
        var recusadas = new ArrayList<Recusada>();
        var vistos = new HashSet<Integer>();
        var ids = novas.stream().map(AnaliseNova::alunoId).filter(Objects::nonNull).collect(Collectors.toSet());
        var movimentos = movimentos(ids, porAluno(ids.isEmpty() ? List.of() : analises.ultimasDe(ids)), agora);
        for (var n : novas) {
            var aluno = n.alunoId() == null ? null
                    : contas.buscar(n.alunoId()).filter(u -> u.getPapel() == Papel.ALUNO).orElse(null);
            var ponto = limpo(n.pontoPrincipal());
            var padroes = limpo(n.padroes());
            var melhorou = limpo(n.melhorou());
            var passos = limpo(n.proximosPassos());
            var total = aluno == null ? 0 : movimentos.getOrDefault(aluno.getId(), Movimento.NADA).total();
            String motivo = n.alunoId() == null ? "faltou o aluno_id, que vem em listar_analises_pendentes."
                    : aluno == null ? "não existe aluno com id %d. Use o aluno_id de listar_analises_pendentes.".formatted(n.alunoId())
                    : !vistos.add(aluno.getId()) ? "o aluno veio duas vezes neste envio; fica a primeira."
                    : ponto == null ? "ponto_principal é obrigatório: é a frase que abre a análise."
                    : total == 0 ? "%s não tem resposta que conte; não há o que analisar.".formatted(aluno.getNome())
                    : java.util.stream.Stream.of(grande("ponto_principal", ponto, TAMANHO_DO_PONTO),
                            grande("padroes", padroes, TAMANHO_DO_BLOCO), grande("melhorou", melhorou, TAMANHO_DO_BLOCO),
                            grande("proximos_passos", passos, TAMANHO_DO_BLOCO)).filter(Objects::nonNull).findFirst().orElse(null);
            if (motivo != null) {
                recusadas.add(new Recusada(n.alunoId(), motivo));
                continue;
            }
            analises.save(new AnaliseDoAluno(aluno.getId(), ponto, padroes, melhorou, passos, total, ident.usuarioId(), agora));
            gravadas.add(aluno.getNome());
        }
        analises.flush();
        return new Gravadas(gravadas.size(), gravadas, recusadas, fila(agora).size());
    }

    // --- o aluno lê ----------------------------------------------------------------

    /**
     * {@code analise} nula: ainda não há, ou o professor ocultou. {@code temNovidade}: há respostas
     * que esta análise ainda não considera.
     */
    public record MinhaAnalise(Texto analise, boolean temNovidade) {}

    @Transactional
    public MinhaAnalise minha(Identidade ident, Instant agora) {
        if (!ident.eAluno()) {
            throw new RegraDeNegocio("Esta é a visão do aluno. Para um aluno específico, use o acompanhamento dele.");
        }
        var ultima = analises.findFirstByAlunoIdOrderByIdDesc(ident.usuarioId()).orElse(null);
        if (ultima == null || ultima.oculta()) {
            return new MinhaAnalise(null, false);
        }
        var m = movimentos(List.of(ident.usuarioId()), Map.of(ident.usuarioId(), ultima), agora)
                .getOrDefault(ident.usuarioId(), Movimento.NADA);
        return new MinhaAnalise(texto(ultima), m.simuladoQueFechou() != null || m.novas() >= NOVIDADE);
    }

    // --- o professor acompanha -----------------------------------------------------

    /**
     * A análise como o professor a vê: a última, mesmo oculta. {@code naFila}: o aluno espera uma
     * análise nova, e {@code motivoDaFila} diz por quê.
     */
    public record DoAluno(Texto analise, boolean oculta, Instant refazerPedidoEm, String orientacao, String escritaPor,
            boolean naFila, String motivoDaFila) {}

    private DoAluno descrever(Usuario aluno, AnaliseDoAluno ultima, Instant agora) {
        var ultimas = ultima == null ? Map.<Integer, AnaliseDoAluno>of() : Map.of(aluno.getId(), ultima);
        var naFila = avaliar(aluno, ultima, movimentos(List.of(aluno.getId()), ultimas, agora)
                .getOrDefault(aluno.getId(), Movimento.NADA));
        return new DoAluno(texto(ultima), ultima != null && ultima.oculta(),
                ultima == null ? null : ultima.getRefazerPedidoEm(), ultima == null ? null : ultima.getRefazerOrientacao(),
                ultima == null ? null : contas.buscar(ultima.getEscritaPorId()).map(Usuario::getNome).orElse(null),
                naFila != null, naFila == null ? null : naFila.motivo());
    }

    @Transactional
    public DoAluno doAluno(Identidade ident, String alunoRef, Instant agora) {
        ident.exigirOperador();
        var aluno = contas.resolverAluno(alunoRef);
        return descrever(aluno, analises.findFirstByAlunoIdOrderByIdDesc(aluno.getId()).orElse(null), agora);
    }

    private AnaliseDoAluno exigirAnalise(Usuario aluno) {
        return analises.findFirstByAlunoIdOrderByIdDesc(aluno.getId()).orElseThrow(() -> new RegraDeNegocio(
                "%s ainda não tem análise escrita.".formatted(aluno.getNome())));
    }

    /** O professor tira a análise da frente do aluno, ou devolve. */
    @Transactional
    public DoAluno ocultar(Identidade ident, String alunoRef, boolean oculta, Instant agora) {
        ident.exigirOperador();
        var aluno = contas.resolverAluno(alunoRef);
        var ultima = exigirAnalise(aluno);
        ultima.ocultar(oculta, ident.usuarioId(), agora);
        return descrever(aluno, ultima, agora);
    }

    /** O professor pede outra: esta sai da frente do aluno e ele volta para o começo da fila. */
    @Transactional
    public DoAluno pedirOutra(Identidade ident, String alunoRef, String orientacao, Instant agora) {
        ident.exigirOperador();
        var aluno = contas.resolverAluno(alunoRef);
        var ultima = exigirAnalise(aluno);
        var texto = limpo(orientacao);
        if (texto != null && texto.length() > TAMANHO_DA_ORIENTACAO) {
            throw new RegraDeNegocio("A orientação tem %d caracteres; o limite é %d.".formatted(texto.length(), TAMANHO_DA_ORIENTACAO));
        }
        ultima.pedirOutra(texto, ident.usuarioId(), agora);
        return descrever(aluno, ultima, agora);
    }
}
