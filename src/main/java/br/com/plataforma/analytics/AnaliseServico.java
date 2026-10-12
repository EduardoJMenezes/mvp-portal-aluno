package br.com.plataforma.analytics;

import br.com.plataforma.analytics.DevolutivaServico.Nivel;
import br.com.plataforma.analytics.DevolutivaServico.Origem;
import br.com.plataforma.analytics.DevolutivaServico.RespostaAvaliada;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Papel;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.simulados.Resposta;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import br.com.plataforma.simulados.Tentativa;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A análise escrita de um aluno sobre um conjunto de respostas — o recorte.
 *
 * <p><b>A plataforma conta; quem escreve é o Claude do professor.</b> Este serviço não chama modelo
 * nenhum. Ele monta o resumo do recorte — o acerto por assunto, os erros com o comentário da
 * alternativa marcada, o que há no curso para revisar —, entrega pelo MCP e guarda o texto que
 * volta. Toda questão tem assunto, sub-assunto e comentário por alternativa, então qualquer
 * conjunto de questões respondidas sustenta uma análise; hoje há dois recortes:
 *
 * <ul>
 *   <li><b>um simulado</b>: escrita para o aluno, que a lê no resultado da prova. O resultado
 *       inteiro (nota, gabarito, gráficos e esta análise) só sai quando o professor libera,
 *       depois de gerar as análises — ver {@link #liberarResultado};
 *   <li><b>geral</b>: tudo o que ele respondeu, escrita para o professor, que a pede no Claude
 *       quando quer conversar com aquele aluno. O aluno não vê.
 * </ul>
 *
 * <p>Recorte novo (um módulo, uma lista, um período) é outro filtro sobre as mesmas respostas.
 */
@Service
public class AnaliseServico {

    /** Os erros mais recentes que vão no resumo, com o comentário de cada um. */
    static final int ERROS_NO_RESUMO = 30;
    /** Em quantos pontos fracos o resumo diz o que há no curso para estudar. */
    static final int PONTOS_NO_RESUMO = 8;
    public static final int LOTE_PADRAO = 5;
    public static final int LOTE_MAXIMO = 10;
    static final int TAMANHO_DO_PONTO = 700;
    static final int TAMANHO_DO_BLOCO = 1200;
    /** Quantas análises anteriores o professor vê ao reconsultar. */
    static final int HISTORICO = 10;

    private final AnaliseDoAlunoRepositorio analises;
    private final DevolutivaServico devolutiva;
    private final ContasServico contas;
    private final SimuladosServico simulados;
    private final PanoramaServico panorama;

    @PersistenceContext
    private EntityManager em;

    public AnaliseServico(AnaliseDoAlunoRepositorio analises, DevolutivaServico devolutiva, ContasServico contas,
            SimuladosServico simulados, PanoramaServico panorama) {
        this.panorama = panorama;
        this.analises = analises;
        this.devolutiva = devolutiva;
        this.contas = contas;
        this.simulados = simulados;
    }

    private static boolean branco(String texto) {
        return texto == null || texto.isBlank();
    }

    // --- o resumo de um recorte ----------------------------------------------------

    public record SubassuntoNoResumo(String nome, int respostas, int acertos, Nivel nivel) {}

    public record AssuntoNoResumo(String nome, int respostas, int acertos, Nivel nivel, List<SubassuntoNoResumo> subassuntos) {}

    /**
     * Um erro do aluno. {@code marcada} nula: deixou em branco. Os comentários são os que o
     * professor aprovou; sem comentário o campo nem sai (o resumo de uma turma é grande, e campo
     * vazio é peso), e aí só se sabe que ele errou.
     */
    public record ErroDoAluno(Integer questaoId, String onde, String assunto, String subassunto, String enunciado,
            Letra marcada, Letra gabarito,
            @JsonInclude(JsonInclude.Include.NON_NULL) String comentarioDaMarcada,
            @JsonInclude(JsonInclude.Include.NON_NULL) String comentarioDaCorreta) {}

    /** O que há no curso do aluno sobre um ponto fraco, pelo nome que ele vê na tela. */
    public record ParaRevisar(String topico, int erros, List<String> assistir, List<String> ler, List<String> praticar,
            List<String> rever) {}

    public record Texto(String pontoPrincipal, String padroes, String melhorou, String proximosPassos, Instant escritaEm) {}

    /** Como ele foi em outra prova: é o que permite dizer se melhorou. */
    public record OutroSimulado(String titulo, int acertos, int total, Instant fechouEm) {}

    /** O material de um aluno para quem escreve: o de uma prova, ou o geral ({@link PanoramaServico.ResumoGeral}). */
    public interface Resumo {}

    public record ResumoDoAluno(Integer alunoId, String aluno, int respostas, int acertos, Double percentual,
            List<AssuntoNoResumo> porAssunto, int erros, List<ErroDoAluno> errosRecentes, List<ParaRevisar> noCurso,
            List<OutroSimulado> outrosSimulados, Texto analiseAnterior) implements Resumo {}

    /**
     * {@code recorte}: {@code SIMULADO} ou {@code GERAL}. Os campos do simulado só vêm nele:
     * {@code participantes} fizeram a prova, {@code naFila} ainda não têm análise dela, e
     * {@code questoesSemComentario} são as questões em que o professor não comentou alternativa
     * nenhuma — nelas a análise só sabe que o aluno errou. No {@code GERAL}, {@code alunos} traz um
     * {@link PanoramaServico.ResumoGeral}: o histórico inteiro não se lê como uma prova.
     */
    public record Pendentes(String recorte, String simulado, Boolean resultadoLiberado, Integer participantes,
            Integer questoes, Integer questoesSemComentario, int naFila, List<? extends Resumo> alunos, int restam) {}

    static Texto texto(AnaliseDoAluno a) {
        return a == null ? null
                : new Texto(a.getPontoPrincipal(), a.getPadroes(), a.getMelhorou(), a.getProximosPassos(), a.getEscritaEm());
    }

    /** O enunciado em uma linha: sem a marca da figura e sem quebras, para caber no resumo. */
    static String emUmaLinha(String enunciado) {
        var limpo = enunciado.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ").replaceAll("\\s+", " ").strip();
        return limpo.length() > 100 ? limpo.substring(0, 100) + "…" : limpo;
    }

    private static List<String> nomes(AnalyticsServico.Recomendacao ponto, String tipo) {
        return ponto.noCurso().stream().filter(l -> tipo.equals(l.tipo()))
                .map(l -> "%s (%s)".formatted(l.nome(), l.modulo())).toList();
    }

    /** Só os pontos mais fracos: é material para até três próximos passos, não o curso inteiro. */
    static List<ParaRevisar> paraRevisar(DevolutivaServico.Devolutiva d) {
        return d.ondeRevisar().stream().limit(PONTOS_NO_RESUMO).map(p -> new ParaRevisar(p.topico(), p.erros(),
                Stream.concat(nomes(p, "VIDEO").stream(),
                        p.videos().stream().filter(v -> !v.bloqueado()).map(v -> v.titulo())).toList(),
                nomes(p, "PDF"), nomes(p, "QUESTAO"), nomes(p, "ERRO"))).toList();
    }

    private static final Comparator<RespostaAvaliada> POR_DATA =
            Comparator.comparing(RespostaAvaliada::quando, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    /**
     * O resumo de um aluno sobre {@code respostas}, que já chegam recortadas. {@code outras} são as
     * respostas de onde sai o desempenho dele nas outras provas; {@code simuladoDoRecorte} fica de
     * fora dessa lista.
     */
    private ResumoDoAluno resumir(Usuario aluno, List<RespostaAvaliada> respostas, List<RespostaAvaliada> outras,
            Integer simuladoDoRecorte, AnaliseDoAluno anterior, Instant agora) {
        var d = devolutiva.comoOAlunoVe(aluno, respostas, agora);
        var etiquetas = devolutiva.etiquetasDas(respostas.stream().map(RespostaAvaliada::questaoId).collect(Collectors.toSet()));

        var porAssunto = d.assuntos().stream().map(a -> new AssuntoNoResumo(a.nome(), a.respostas(), a.acertos(), a.nivel(),
                a.subassuntos().stream().map(s -> new SubassuntoNoResumo(s.nome(), s.respostas(), s.acertos(), s.nivel())).toList()))
                .toList();

        // Os erros, do mais recente para o mais antigo (na prova, na ordem das questões), com o
        // comentário de cada alternativa.
        var errados = respostas.stream().filter(r -> !r.correta())
                .sorted(POR_DATA.reversed().thenComparing(r -> r.numero() == null ? 0 : r.numero()))
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
                    r.marcada() == null ? null : comentarios.get(r.marcada()), comentarios.get(q.getGabarito())));
        }

        var noCurso = paraRevisar(d);

        // As outras provas, da mais antiga para a mais nova.
        var provas = new LinkedHashMap<Integer, int[]>();
        var dasProvas = new HashMap<Integer, RespostaAvaliada>();
        outras.stream().filter(r -> r.origem() == Origem.SIMULADO && !r.simuladoId().equals(simuladoDoRecorte))
                .sorted(POR_DATA)
                .forEach(r -> {
                    var c = provas.computeIfAbsent(r.simuladoId(), k -> new int[2]);
                    c[0] += r.correta() ? 1 : 0;
                    c[1]++;
                    dasProvas.putIfAbsent(r.simuladoId(), r);
                });
        var outrosSimulados = provas.entrySet().stream().map(par -> new OutroSimulado(
                dasProvas.get(par.getKey()).prova(), par.getValue()[0], par.getValue()[1], dasProvas.get(par.getKey()).quando())).toList();

        return new ResumoDoAluno(aluno.getId(), aluno.getNome(), d.respostas(), d.acertos(), d.percentual(), porAssunto,
                errados.size(), erros, noCurso, outrosSimulados, texto(anterior));
    }

    private Simulado exigirEncerrado(String referencia, Instant agora) {
        var s = simulados.resolver(referencia);
        var quando = SimuladosServico.situacao(s, agora);
        if (quando != Situacao.ENCERRADO) {
            throw new RegraDeNegocio(quando == Situacao.RASCUNHO
                    ? "'%s' ainda é rascunho: ninguém fez a prova.".formatted(s.getTitulo())
                    : "'%s' ainda não fechou (fecha em %s). As análises de um simulado são escritas depois do fechamento."
                            .formatted(s.getTitulo(), Relogio.emBrasilia(s.getFechaEm())));
        }
        return s;
    }

    private List<Tentativa> quemFez(Simulado s, Instant agora) {
        return simulados.classificar(s, agora).stream().map(SimuladosServico.Classificado::tentativa).toList();
    }

    private Map<Integer, AnaliseDoAluno> analisesDoSimulado(Simulado s) {
        return analises.ultimasDoSimulado(s.getId()).stream().collect(Collectors.toMap(AnaliseDoAluno::getAlunoId, a -> a));
    }

    /**
     * O material para escrever. Com simulado: os alunos que fizeram a prova e ainda não têm análise
     * dela, em lotes (com {@code alunoRef}, só aquele, tenha ou não). Só com o aluno: o resumo de
     * tudo o que ele respondeu, para a análise geral.
     */
    @Transactional
    public Pendentes pendentes(Identidade ident, String simuladoRef, String alunoRef, Integer limite, Instant agora) {
        ident.exigirOperador();
        if (branco(simuladoRef) && branco(alunoRef)) {
            throw new RegraDeNegocio("Informe o simulado, para as análises dos alunos que fizeram a prova, ou o aluno, "
                    + "para a análise geral dele (que só o professor vê). Simulados: listar_simulados.");
        }
        if (branco(simuladoRef)) {
            var aluno = contas.resolverAluno(alunoRef);
            var anterior = analises.findByAlunoIdAndSimuladoIdIsNullOrderByIdDesc(aluno.getId()).stream().findFirst().orElse(null);
            var geral = panorama.resumoGeral(ident, aluno, anterior, agora);
            if (geral.respostas() == 0) {
                throw new RegraDeNegocio("%s ainda não respondeu nenhuma questão, nem em aula nem em simulado: não há o que analisar."
                        .formatted(aluno.getNome()));
            }
            return new Pendentes("GERAL", null, null, null, null, null, 1, List.of(geral), 0);
        }

        var s = exigirEncerrado(simuladoRef, agora);
        var tentativas = quemFez(s, agora);
        var escritas = analisesDoSimulado(s);
        List<Tentativa> fila;
        if (branco(alunoRef)) {
            fila = tentativas.stream().filter(t -> !escritas.containsKey(t.getAluno().getId()))
                    .sorted(Comparator.comparing(t -> t.getAluno().getNome())).toList();
        } else {
            var aluno = contas.resolverAluno(alunoRef);
            fila = tentativas.stream().filter(t -> t.getAluno().getId().equals(aluno.getId())).toList();
            if (fila.isEmpty()) {
                throw new RegraDeNegocio("%s não fez '%s'.".formatted(aluno.getNome(), s.getTitulo()));
            }
        }
        var quantos = Math.min(limite == null ? LOTE_PADRAO : limite, LOTE_MAXIMO);
        var alunos = fila.stream().limit(quantos).map(t -> {
            var aluno = t.getAluno();
            var daProva = devolutiva.respostasDe(List.of(aluno.getId()), false, agora).stream()
                    .filter(r -> s.getId().equals(r.simuladoId())).toList();
            // As outras provas entram como o aluno as vê: resultado ainda não liberado não aparece no texto dele.
            var visiveis = devolutiva.respostasDe(List.of(aluno.getId()), true, agora);
            return resumir(aluno, daProva, visiveis, s.getId(), escritas.get(aluno.getId()), agora);
        }).toList();
        var semComentario = (int) s.getQuestoes().stream().filter(sq -> sq.getQuestao().comentarios().isEmpty()).count();
        return new Pendentes("SIMULADO", s.getTitulo(), s.resultadoLiberado(), tentativas.size(), s.getQuestoes().size(),
                semComentario, fila.size(), alunos, fila.size() - alunos.size());
    }

    // --- gravar --------------------------------------------------------------------

    public record AnaliseNova(Integer alunoId, String pontoPrincipal, String padroes, String melhorou, String proximosPassos) {}

    public record Recusada(Integer alunoId, String motivo) {}

    /**
     * {@code restam}: no simulado, quantos dos que fizeram a prova ainda não têm análise.
     * {@code resultadoLiberado}: se os alunos já veem o resultado — e, com ele, a análise gravada.
     */
    public record Gravadas(String recorte, String simulado, int gravadas, List<String> alunos, List<Recusada> recusadas,
            Integer restam, Boolean resultadoLiberado) {}

    private static String limpo(String texto) {
        return branco(texto) ? null : texto.strip();
    }

    private static String grande(String campo, String texto, int limite) {
        return texto != null && texto.length() > limite
                ? "%s tem %d caracteres; o limite é %d. Encurte.".formatted(campo, texto.length(), limite) : null;
    }

    /**
     * Guarda as análises escritas. Com simulado, são as daquela prova: o aluno as lê no resultado,
     * quando o professor liberar (ou na hora, se já liberou). Sem simulado, são gerais, e só o
     * professor as vê. Cada uma vale por si: a que não passa volta com o motivo, e as outras entram.
     */
    @Transactional
    public Gravadas gravar(Identidade ident, String simuladoRef, List<AnaliseNova> novas, Instant agora) {
        ident.exigirOperador();
        var s = branco(simuladoRef) ? null : exigirEncerrado(simuladoRef, agora);
        Set<Integer> fizeram = s == null ? Set.of()
                : quemFez(s, agora).stream().map(t -> t.getAluno().getId()).collect(Collectors.toSet());
        var gravadas = new ArrayList<String>();
        var recusadas = new ArrayList<Recusada>();
        var vistos = new HashSet<Integer>();
        for (var n : novas) {
            var aluno = n.alunoId() == null ? null
                    : contas.buscar(n.alunoId()).filter(u -> u.getPapel() == Papel.ALUNO).orElse(null);
            var ponto = limpo(n.pontoPrincipal());
            var padroes = limpo(n.padroes());
            var melhorou = limpo(n.melhorou());
            var passos = limpo(n.proximosPassos());
            String motivo = n.alunoId() == null ? "faltou o aluno_id, que vem em listar_analises_pendentes."
                    : aluno == null ? "não existe aluno com id %d. Use o aluno_id de listar_analises_pendentes.".formatted(n.alunoId())
                    : !vistos.add(aluno.getId()) ? "o aluno veio duas vezes neste envio; fica a primeira."
                    : ponto == null ? "ponto_principal é obrigatório: é a frase que abre a análise."
                    : s != null && !fizeram.contains(aluno.getId())
                            ? "%s não fez '%s': só quem fez a prova recebe a análise dela.".formatted(aluno.getNome(), s.getTitulo())
                    : Stream.of(grande("ponto_principal", ponto, TAMANHO_DO_PONTO), grande("padroes", padroes, TAMANHO_DO_BLOCO),
                            grande("melhorou", melhorou, TAMANHO_DO_BLOCO), grande("proximos_passos", passos, TAMANHO_DO_BLOCO))
                            .filter(Objects::nonNull).findFirst().orElse(null);
            var respostas = 0;
            if (motivo == null) {
                respostas = s != null ? s.getQuestoes().size()
                        : devolutiva.respostasDe(List.of(aluno.getId()), false, agora).size();
                if (respostas == 0) {
                    motivo = "%s ainda não respondeu nenhuma questão; não há o que analisar.".formatted(aluno.getNome());
                }
            }
            if (motivo != null) {
                recusadas.add(new Recusada(n.alunoId(), motivo));
                continue;
            }
            analises.save(new AnaliseDoAluno(aluno.getId(), s == null ? null : s.getId(), ponto, padroes, melhorou, passos,
                    respostas, ident.usuarioId(), agora));
            gravadas.add(aluno.getNome());
        }
        analises.flush();
        if (s == null) {
            return new Gravadas("GERAL", null, gravadas.size(), gravadas, recusadas, null, null);
        }
        var escritas = analisesDoSimulado(s);
        return new Gravadas("SIMULADO", s.getTitulo(), gravadas.size(), gravadas, recusadas,
                (int) fizeram.stream().filter(id -> !escritas.containsKey(id)).count(), s.resultadoLiberado());
    }

    // --- liberar o resultado -------------------------------------------------------

    /** {@code liberadoAgora}: falso quando já estava liberado. {@code semAnalise} veem só os gráficos. */
    public record ResultadoLiberado(String simulado, boolean liberadoAgora, Instant liberadoEm, int participantes,
            int comAnalise, int semAnalise) {}

    /**
     * O professor libera o resultado do simulado: a partir daqui o aluno vê nota, gabarito, gráficos
     * e a análise escrita para ele. É o passo que fecha "gerar as análises do simulado"; antes
     * dele, nada do resultado sai, por mais que a prova já tenha fechado.
     */
    @Transactional
    public ResultadoLiberado liberarResultado(Identidade ident, String simuladoRef, Instant agora) {
        ident.exigirOperador();
        var s = simulados.resolver(simuladoRef);
        var liberadoAgora = simulados.liberarResultado(ident, s, agora);
        var fizeram = quemFez(s, agora);
        var escritas = analisesDoSimulado(s);
        var comAnalise = (int) fizeram.stream().filter(t -> escritas.containsKey(t.getAluno().getId())).count();
        return new ResultadoLiberado(s.getTitulo(), liberadoAgora, s.getResultadoLiberadoEm(), fizeram.size(), comAnalise,
                fizeram.size() - comAnalise);
    }

    // --- o aluno, no resultado do simulado ------------------------------------------

    /** A análise que o aluno lê no resultado; nula se ainda não há, ou se o professor ocultou. */
    @Transactional(readOnly = true)
    public Texto doSimuladoParaOAluno(Integer simuladoId, Integer alunoId) {
        return analises.findByAlunoIdAndSimuladoIdOrderByIdDesc(alunoId, simuladoId).stream().findFirst()
                .filter(a -> !a.oculta()).map(AnaliseServico::texto).orElse(null);
    }

    /** A análise de um aluno num simulado como o professor a vê: a que vale, mesmo oculta. */
    public record AnaliseNoResultado(Texto analise, boolean oculta) {}

    @Transactional(readOnly = true)
    public AnaliseNoResultado doSimuladoParaOProfessor(Integer simuladoId, Integer alunoId) {
        return analises.findByAlunoIdAndSimuladoIdOrderByIdDesc(alunoId, simuladoId).stream().findFirst()
                .map(a -> new AnaliseNoResultado(texto(a), a.oculta())).orElse(null);
    }

    /** {@code mediaDaTurma}: o acerto, naquele assunto, de todos os que fizeram a prova. */
    public record AssuntoNoSimulado(String assunto, int questoes, int acertos, double percentual, double mediaDaTurma) {}

    /**
     * A leitura crua da prova para o gráfico do aluno: o acerto dele em cada assunto, ao lado da
     * média de quem fez. Em branco conta como erro, para ele e para a turma. Do pior para o melhor.
     */
    public List<AssuntoNoSimulado> porAssuntoNoSimulado(Simulado s, Tentativa minha, List<Tentativa> todas) {
        var ids = s.getQuestoes().stream().map(sq -> sq.getQuestao().getId()).toList();
        var etiquetas = devolutiva.etiquetasDas(ids);
        var minhasCertas = certas(minha);
        var certasDaTurma = new HashMap<Integer, Integer>();
        todas.forEach(t -> certas(t).forEach(id -> certasDaTurma.merge(id, 1, Integer::sum)));

        // {questões, acertos dele, acertos somados da turma}
        var conta = new LinkedHashMap<String, int[]>();
        for (var id : ids) {
            var e = etiquetas.get(id);
            var c = conta.computeIfAbsent(e == null ? "Sem assunto" : e.assunto(), k -> new int[3]);
            c[0]++;
            c[1] += minhasCertas.contains(id) ? 1 : 0;
            c[2] += certasDaTurma.getOrDefault(id, 0);
        }
        return conta.entrySet().stream().map(par -> {
            var c = par.getValue();
            return new AssuntoNoSimulado(par.getKey(), c[0], c[1], AnalyticsServico.percentual(c[1], c[0]),
                    AnalyticsServico.percentual(c[2], c[0] * todas.size()));
        }).sorted(Comparator.comparingDouble(AssuntoNoSimulado::percentual).thenComparing(AssuntoNoSimulado::assunto)).toList();
    }

    private static Set<Integer> certas(Tentativa t) {
        return t.getRespostas().stream().filter(Resposta::isCorreta).map(Resposta::getQuestaoId).collect(Collectors.toSet());
    }

    // --- o simulado, para o professor ----------------------------------------------

    /** Uma linha do ranking, com o que o professor precisa para abrir o resultado daquele aluno. */
    public record AlunoNoSimulado(Integer alunoId, String aluno, int posicao, int acertos, int emBranco, double percentual,
            boolean entregueAutomaticamente, boolean temAnalise, boolean analiseOculta) {}

    public record AssuntoDaTurma(String assunto, int questoes, double percentual) {}

    /**
     * Uma questão em que a turma tropeçou. {@code erroMaisComum} é a alternativa errada mais
     * marcada, e {@code comentarioDoErro} o que o professor escreveu para ela: é o engano que mais
     * gente cometeu, dito com as palavras dele.
     */
    public record QuestaoMaisErrada(Integer ordem, Integer questaoId, String enunciado, String assunto, String subassunto,
            Letra gabarito, double percentualAcerto, int emBranco, Letra erroMaisComum, Integer quantosMarcaram,
            String comentarioDoErro) {}

    /**
     * O simulado depois da prova, para o professor: quantos fizeram, como a turma foi por assunto,
     * onde mais errou e o ranking, de onde ele abre o resultado de cada aluno. {@code media} nula:
     * ninguém fez.
     */
    public record VisaoDoSimulado(Integer simuladoId, String titulo, Situacao situacao, Instant resultadoLiberadoEm,
            int matriculados, int participantes, Double media, int comAnalise, int questoes, int questoesSemComentario,
            List<AssuntoDaTurma> porAssunto, List<QuestaoMaisErrada> maisErradas, List<AlunoNoSimulado> alunos) {}

    /** Quantas das mais erradas vão para a tela: é um destaque, a tabela inteira está nas questões. */
    static final int MAIS_ERRADAS = 5;

    @Transactional
    public VisaoDoSimulado visaoDoSimulado(Identidade ident, String simuladoRef, Instant agora) {
        ident.exigirOperador();
        var s = simulados.resolver(simuladoRef);
        var linhas = simulados.classificar(s, agora);
        var total = s.getQuestoes().size();
        var participantes = linhas.size();
        var escritas = analisesDoSimulado(s);
        var matriculados = s.getTurmas().isEmpty() ? 0 : em.createQuery(
                "select count(distinct m.usuarioId) from Matricula m where m.turma in :turmas", Long.class)
                .setParameter("turmas", s.getTurmas()).getSingleResult().intValue();

        var alunos = linhas.stream().map(c -> {
            var t = c.tentativa();
            var analise = escritas.get(t.getAluno().getId());
            return new AlunoNoSimulado(t.getAluno().getId(), t.getAluno().getNome(), c.posicao(), c.acertos(),
                    total - t.getRespostas().size(), AnalyticsServico.percentual(c.acertos(), total),
                    t.isEntregueAutomaticamente(), analise != null, analise != null && analise.oculta());
        }).toList();

        // De cada questão: quantos acertaram e o que marcaram os que erraram.
        var ids = s.getQuestoes().stream().map(sq -> sq.getQuestao().getId()).toList();
        var etiquetas = devolutiva.etiquetasDas(ids);
        var certas = new HashMap<Integer, Integer>();
        var marcadasErradas = new HashMap<Integer, Map<Letra, Integer>>();
        var respondidas = new HashMap<Integer, Integer>();
        for (var c : linhas) {
            for (var r : c.tentativa().getRespostas()) {
                respondidas.merge(r.getQuestaoId(), 1, Integer::sum);
                if (r.isCorreta()) {
                    certas.merge(r.getQuestaoId(), 1, Integer::sum);
                } else {
                    marcadasErradas.computeIfAbsent(r.getQuestaoId(), k -> new java.util.TreeMap<>())
                            .merge(r.getAlternativaMarcada(), 1, Integer::sum);
                }
            }
        }

        var porAssunto = new LinkedHashMap<String, int[]>();
        var maisErradas = new ArrayList<QuestaoMaisErrada>();
        for (var sq : s.getQuestoes()) {
            var q = sq.getQuestao();
            var e = etiquetas.get(q.getId());
            var acertaram = certas.getOrDefault(q.getId(), 0);
            var c = porAssunto.computeIfAbsent(e == null ? "Sem assunto" : e.assunto(), k -> new int[2]);
            c[0]++;
            c[1] += acertaram;
            if (participantes == 0 || acertaram == participantes) {
                continue;
            }
            var erro = marcadasErradas.getOrDefault(q.getId(), Map.of()).entrySet().stream()
                    .max(Map.Entry.<Letra, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .orElse(null);
            maisErradas.add(new QuestaoMaisErrada(sq.getOrdem(), q.getId(), emUmaLinha(q.getEnunciado()),
                    e == null ? null : e.assunto(), e == null ? null : e.subassunto(), q.getGabarito(),
                    AnalyticsServico.percentual(acertaram, participantes), participantes - respondidas.getOrDefault(q.getId(), 0),
                    erro == null ? null : erro.getKey(), erro == null ? null : erro.getValue(),
                    erro == null ? null : q.comentarios().get(erro.getKey())));
        }
        maisErradas.sort(Comparator.comparingDouble(QuestaoMaisErrada::percentualAcerto).thenComparing(QuestaoMaisErrada::ordem));

        return new VisaoDoSimulado(s.getId(), s.getTitulo(), SimuladosServico.situacao(s, agora), s.getResultadoLiberadoEm(),
                matriculados, participantes,
                participantes == 0 ? null : AnalyticsServico.arredondar(alunos.stream().mapToDouble(AlunoNoSimulado::percentual).sum() / participantes),
                (int) alunos.stream().filter(AlunoNoSimulado::temAnalise).count(), total,
                (int) s.getQuestoes().stream().filter(sq -> sq.getQuestao().comentarios().isEmpty()).count(),
                porAssunto.entrySet().stream().map(par -> new AssuntoDaTurma(par.getKey(), par.getValue()[0],
                        AnalyticsServico.percentual(par.getValue()[1], par.getValue()[0] * Math.max(participantes, 1))))
                        .sorted(Comparator.comparingDouble(AssuntoDaTurma::percentual).thenComparing(AssuntoDaTurma::assunto)).toList(),
                maisErradas.stream().limit(MAIS_ERRADAS).toList(), alunos);
    }

    // --- o professor ---------------------------------------------------------------

    /**
     * A análise como o professor a vê: a que vale, mesmo oculta, e as anteriores do mesmo recorte.
     * {@code simulado} nulo: é a geral, que só ele vê; {@code oculta} só faz sentido na do simulado.
     */
    public record DoAluno(Integer alunoId, String aluno, String simulado, Texto analise, boolean oculta, String escritaPor,
            List<Texto> anteriores) {}

    private DoAluno descrever(Usuario aluno, Simulado s) {
        var todas = s == null ? analises.findByAlunoIdAndSimuladoIdIsNullOrderByIdDesc(aluno.getId())
                : analises.findByAlunoIdAndSimuladoIdOrderByIdDesc(aluno.getId(), s.getId());
        var atual = todas.isEmpty() ? null : todas.getFirst();
        return new DoAluno(aluno.getId(), aluno.getNome(), s == null ? null : s.getTitulo(), texto(atual),
                atual != null && atual.oculta(),
                atual == null ? null : contas.buscar(atual.getEscritaPorId()).map(Usuario::getNome).orElse(null),
                todas.stream().skip(1).limit(HISTORICO).map(AnaliseServico::texto).toList());
    }

    /** A análise de um aluno: a geral, ou a de um simulado. */
    @Transactional(readOnly = true)
    public DoAluno doAluno(Identidade ident, String alunoRef, String simuladoRef) {
        ident.exigirOperador();
        return descrever(contas.resolverAluno(alunoRef), branco(simuladoRef) ? null : simulados.resolver(simuladoRef));
    }

    /** O professor tira a análise de um simulado da frente do aluno, ou devolve. */
    @Transactional
    public DoAluno ocultar(Identidade ident, String alunoRef, String simuladoRef, boolean oculta, Instant agora) {
        ident.exigirOperador();
        if (branco(simuladoRef)) {
            throw new RegraDeNegocio("Só a análise de um simulado é mostrada ao aluno. A geral já é só do professor.");
        }
        var aluno = contas.resolverAluno(alunoRef);
        var s = simulados.resolver(simuladoRef);
        var atual = analises.findByAlunoIdAndSimuladoIdOrderByIdDesc(aluno.getId(), s.getId()).stream().findFirst()
                .orElseThrow(() -> new RegraDeNegocio("%s ainda não tem análise de '%s'.".formatted(aluno.getNome(), s.getTitulo())));
        atual.ocultar(oculta, ident.usuarioId(), agora);
        return descrever(aluno, s);
    }

    /** {@code analiseGeralEm}: quando a análise geral dele foi escrita; nulo se ainda não há. */
    public record AlunoNaBusca(Integer id, String nome, String email, List<String> turmas, Instant analiseGeralEm) {}

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Os alunos, para o professor achar um pelo nome ou pelo e-mail e abrir o acompanhamento dele.
     * Três consultas para a lista inteira: os alunos, as turmas de cada um e quem já tem análise.
     */
    @Transactional(readOnly = true)
    public List<AlunoNaBusca> alunos(Identidade ident, String busca) {
        ident.exigirOperador();
        var turmas = new HashMap<Integer, List<String>>();
        em.createQuery("select m.usuarioId, t.nome from Matricula m join m.turma t order by t.nome", Object[].class)
                .getResultList().forEach(l -> turmas.computeIfAbsent((Integer) l[0], k -> new ArrayList<>()).add((String) l[1]));
        var gerais = analises.ultimasGerais().stream().collect(Collectors.toMap(AnaliseDoAluno::getAlunoId, AnaliseDoAluno::getEscritaEm));
        var termo = branco(busca) ? null : semAcento(busca.strip());
        return em.createQuery("select u from Usuario u where u.papel = :papel order by u.nome", Usuario.class)
                .setParameter("papel", Papel.ALUNO).getResultList().stream()
                .filter(u -> termo == null || semAcento(u.getNome()).contains(termo) || semAcento(u.getEmail()).contains(termo))
                .map(u -> new AlunoNaBusca(u.getId(), u.getNome(), u.getEmail(), turmas.getOrDefault(u.getId(), List.of()), gerais.get(u.getId())))
                .toList();
    }
}
