package br.com.plataforma.analytics;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.exercicios.ExerciciosServico;
import br.com.plataforma.exercicios.RespostaDeExercicio;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import br.com.plataforma.simulados.Tentativa;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A devolutiva: onde o aluno (ou a turma) está indo bem e onde não, por assunto e sub-assunto.
 *
 * <p><b>Tudo é questão.</b> A resposta dada na aula e a dada no simulado entram na mesma conta, e
 * outra fonte que apareça entra também: basta virar uma {@link RespostaAvaliada}. O que organiza a
 * leitura é a etiqueta da questão, não o lugar onde ela foi respondida.
 *
 * <p><b>Sem corte fixo.</b> Não existe "a partir de N respostas é ponto fraco". Cada assunto leva
 * uma nota <i>ajustada</i>, que parte do desempenho geral de quem respondeu e vai se afastando dele
 * conforme chegam respostas ({@link #ajustar}): um erro em uma questão quase não mexe, seis erros
 * em dez mexem muito. A tela mostra quantas respostas há por trás de cada nota.
 */
@Service
public class DevolutivaServico {

    /** Quanto o desempenho geral pesa na nota de um assunto: vale por este tanto de respostas. */
    static final int PESO_DO_GERAL = 4;

    private final TaxonomiaServico taxonomia;
    private final AcessoServico acesso;
    private final ContasServico contas;
    private final EstruturaServico estrutura;
    private final ExerciciosServico exercicios;

    @PersistenceContext
    private EntityManager em;

    public DevolutivaServico(TaxonomiaServico taxonomia, AcessoServico acesso, ContasServico contas,
            EstruturaServico estrutura, ExerciciosServico exercicios) {
        this.taxonomia = taxonomia;
        this.acesso = acesso;
        this.contas = contas;
        this.estrutura = estrutura;
        this.exercicios = exercicios;
    }

    // --- o que entra na conta ------------------------------------------------------

    public enum Origem { AULA, SIMULADO }

    /** Uma questão respondida (ou deixada em branco numa prova entregue), venha de onde vier. */
    record RespostaAvaliada(Integer alunoId, Integer questaoId, boolean correta, Origem origem) {}

    /**
     * As respostas destes alunos, de todas as fontes.
     *
     * <p>{@code soEncerrados}: é a visão do aluno, que só vê o resultado do simulado depois que ele
     * fecha. O professor vê a prova entregue a qualquer momento. Questão em branco numa prova
     * entregue conta como erro; prova ainda em andamento conta só o que já foi marcado.
     */
    private List<RespostaAvaliada> respostasDe(Collection<Integer> alunos, boolean soEncerrados, Instant agora) {
        var saida = new ArrayList<RespostaAvaliada>();
        if (alunos.isEmpty()) {
            return saida;
        }
        em.createQuery("select r from RespostaDeExercicio r where r.alunoId in :alunos", RespostaDeExercicio.class)
                .setParameter("alunos", alunos).getResultList()
                .forEach(r -> saida.add(new RespostaAvaliada(r.getAlunoId(), r.getQuestaoId(), r.isCorreta(), Origem.AULA)));

        var tentativas = em.createQuery("""
                select distinct t from Tentativa t left join fetch t.respostas
                 where t.aluno.id in :alunos and t.simulado.removidoEm is null""", Tentativa.class)
                .setParameter("alunos", alunos).getResultList();
        for (var t : tentativas) {
            Simulado s = t.getSimulado();
            var encerrado = SimuladosServico.situacao(s, agora) == Situacao.ENCERRADO;
            if (soEncerrados && !encerrado) {
                continue;
            }
            var entregue = t.consolidar(agora) || encerrado;
            var certas = new HashSet<Integer>();
            var marcadas = new HashSet<Integer>();
            t.getRespostas().forEach(r -> {
                marcadas.add(r.getQuestaoId());
                if (r.isCorreta()) {
                    certas.add(r.getQuestaoId());
                }
            });
            for (var sq : s.getQuestoes()) {
                var id = sq.getQuestao().getId();
                if (entregue || marcadas.contains(id)) {
                    saida.add(new RespostaAvaliada(t.getAluno().getId(), id, certas.contains(id), Origem.SIMULADO));
                }
            }
        }
        return saida;
    }

    // --- a leitura por assunto -----------------------------------------------------

    public enum Nivel { ATENCAO, DESENVOLVENDO, BEM }

    /**
     * {@code percentual}: o acerto cru. {@code ajustado}: o mesmo, puxado para o desempenho geral
     * na proporção de quão poucas respostas existem — é ele que ordena e que define o {@code nivel}.
     */
    public record Linha(
            Integer id, String nome, int respostas, int acertos, double percentual, double ajustado,
            Nivel nivel, int daAula, int deSimulado) {}

    public record AssuntoNaDevolutiva(
            Integer id, String nome, int respostas, int acertos, double percentual, double ajustado,
            Nivel nivel, int daAula, int deSimulado, List<Linha> subassuntos) {}

    /**
     * {@code semAssunto}: respostas em questões sem etiqueta, que não entram em assunto nenhum.
     * {@code ondeRevisar}: os pontos mais fracos com os vídeos que os explicam; só para um aluno.
     */
    public record Devolutiva(
            int respostas, int acertos, Double percentual, int semAssunto, int questoesSemAssunto,
            List<AssuntoNaDevolutiva> assuntos, List<AnalyticsServico.Recomendacao> ondeRevisar) {}

    /** O acerto de {@code n} respostas, misturado com {@link #PESO_DO_GERAL} respostas no ritmo geral. */
    static double ajustar(int acertos, int n, double geral) {
        return AnalyticsServico.arredondar(100.0 * (acertos + PESO_DO_GERAL * geral) / (n + PESO_DO_GERAL));
    }

    static Nivel nivelDe(double ajustado) {
        return ajustado < 50 ? Nivel.ATENCAO : ajustado < 75 ? Nivel.DESENVOLVENDO : Nivel.BEM;
    }

    private static final class Conta {
        int respostas;
        int acertos;
        int daAula;
        int deSimulado;

        void somar(RespostaAvaliada r) {
            respostas++;
            acertos += r.correta() ? 1 : 0;
            if (r.origem() == Origem.AULA) {
                daAula++;
            } else {
                deSimulado++;
            }
        }
    }

    private record Etiqueta(Integer assuntoId, String assunto, Integer subassuntoId, String subassunto) {}

    /** A primeira etiqueta de cada questão. O {@code left join} é obrigatório: ver {@code AnalyticsServico}. */
    private Map<Integer, Etiqueta> etiquetasDas(Collection<Integer> questoes) {
        var saida = new HashMap<Integer, Etiqueta>();
        if (questoes.isEmpty()) {
            return saida;
        }
        em.createQuery("""
                select qa.questao.id, a.id, a.nome, sa.id, sa.nome from QuestaoAssunto qa
                  join qa.assunto a left join qa.subassunto sa
                 where qa.questao.id in :ids order by qa.id""", Object[].class)
                .setParameter("ids", questoes).getResultList()
                .forEach(l -> saida.putIfAbsent((Integer) l[0],
                        new Etiqueta((Integer) l[1], (String) l[2], (Integer) l[3], (String) l[4])));
        return saida;
    }

    private Devolutiva montar(List<RespostaAvaliada> respostas, Identidade paraQuem, Instant agora) {
        var etiquetas = etiquetasDas(respostas.stream().map(RespostaAvaliada::questaoId).collect(java.util.stream.Collectors.toSet()));
        var total = respostas.size();
        var acertos = (int) respostas.stream().filter(RespostaAvaliada::correta).count();
        // Sem resposta nenhuma não há ritmo geral: fica o meio do caminho.
        var geral = total == 0 ? 0.5 : (double) acertos / total;

        var porAssunto = new LinkedHashMap<Integer, Conta>();
        var porSub = new LinkedHashMap<Integer, Map<Integer, Conta>>();
        var nomes = new HashMap<Integer, String>();
        var nomesDosSubs = new HashMap<Integer, String>();
        var semAssunto = 0;
        var questoesSemAssunto = new HashSet<Integer>();
        for (var r : respostas) {
            var e = etiquetas.get(r.questaoId());
            if (e == null) {
                semAssunto++;
                questoesSemAssunto.add(r.questaoId());
                continue;
            }
            nomes.put(e.assuntoId(), e.assunto());
            porAssunto.computeIfAbsent(e.assuntoId(), k -> new Conta()).somar(r);
            if (e.subassuntoId() != null) {
                nomesDosSubs.put(e.subassuntoId(), e.subassunto());
                porSub.computeIfAbsent(e.assuntoId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.subassuntoId(), k -> new Conta()).somar(r);
            }
        }

        var assuntos = new ArrayList<AssuntoNaDevolutiva>();
        porAssunto.forEach((id, c) -> {
            var subs = porSub.getOrDefault(id, Map.of()).entrySet().stream()
                    .map(par -> linha(par.getKey(), nomesDosSubs.get(par.getKey()), par.getValue(), geral))
                    .sorted(Comparator.comparingDouble(Linha::ajustado)).toList();
            var ajustado = ajustar(c.acertos, c.respostas, geral);
            assuntos.add(new AssuntoNaDevolutiva(id, nomes.get(id), c.respostas, c.acertos,
                    AnalyticsServico.percentual(c.acertos, c.respostas), ajustado, nivelDe(ajustado), c.daAula,
                    c.deSimulado, subs));
        });
        assuntos.sort(Comparator.comparingDouble(AssuntoNaDevolutiva::ajustado));

        return new Devolutiva(total, acertos, total == 0 ? null : AnalyticsServico.percentual(acertos, total),
                semAssunto, questoesSemAssunto.size(), assuntos,
                paraQuem == null ? List.of() : ondeRevisar(paraQuem, assuntos, agora));
    }

    private static Linha linha(Integer id, String nome, Conta c, double geral) {
        var ajustado = ajustar(c.acertos, c.respostas, geral);
        return new Linha(id, nome, c.respostas, c.acertos, AnalyticsServico.percentual(c.acertos, c.respostas),
                ajustado, nivelDe(ajustado), c.daAula, c.deSimulado);
    }

    /**
     * Os pontos mais fracos, do pior para o melhor, com os vídeos que explicam cada um. Vai no
     * sub-assunto quando o assunto tem; senão, no assunto. O que está indo bem não entra.
     */
    private List<AnalyticsServico.Recomendacao> ondeRevisar(Identidade ident, List<AssuntoNaDevolutiva> assuntos, Instant agora) {
        record Ponto(Integer assuntoId, Integer subassuntoId, String rotulo, int erros, double ajustado, Nivel nivel) {}
        var pontos = new ArrayList<Ponto>();
        for (var a : assuntos) {
            if (a.subassuntos().isEmpty()) {
                pontos.add(new Ponto(a.id(), null, a.nome(), a.respostas() - a.acertos(), a.ajustado(), a.nivel()));
            }
            for (var s : a.subassuntos()) {
                pontos.add(new Ponto(a.id(), s.id(), s.nome(), s.respostas() - s.acertos(), s.ajustado(), s.nivel()));
            }
        }
        return pontos.stream()
                .filter(p -> p.nivel() != Nivel.BEM && p.erros() > 0)
                .sorted(Comparator.comparingDouble(Ponto::ajustado))
                .limit(4)
                .map(p -> {
                    var videos = taxonomia.videosQueExplicam(p.assuntoId(), p.subassuntoId(), 3);
                    var liberados = acesso.videosLiberados(ident, videos.stream().map(Video::getId).toList(), agora);
                    return new AnalyticsServico.Recomendacao(p.rotulo(), p.erros(),
                            videos.stream().map(v -> AcessoServico.descrever(v, liberados.contains(v.getId()))).toList());
                })
                .toList();
    }

    // --- as três visões ------------------------------------------------------------

    /** O aluno olhando para si: simulado só entra depois de fechar, como o resultado dele. */
    @Transactional
    public Devolutiva minha(Identidade ident, Instant agora) {
        if (!ident.eAluno()) {
            throw new RegraDeNegocio("Esta é a visão do aluno. Para um aluno específico, use o acompanhamento dele.");
        }
        return montar(respostasDe(List.of(ident.usuarioId()), true, agora), ident, agora);
    }

    /** O professor olhando para um aluno: vale a prova entregue, mesmo com o simulado aberto. */
    @Transactional
    public Devolutiva doAluno(Identidade ident, String alunoRef, Instant agora) {
        ident.exigirOperador();
        var aluno = contas.resolverAluno(alunoRef);
        return montar(respostasDe(List.of(aluno.getId()), false, agora), null, agora);
    }

    /** Uma questão da aula como a turma respondeu: {@code distribuicao} diz quantos marcaram cada letra. */
    public record QuestaoDaAula(
            Integer itemId, String item, String modulo, Integer questaoId, String resumo, String topico,
            Letra gabarito, int respostas, int acertos, double percentual, Map<Letra, Integer> distribuicao) {}

    /** {@code alunos}: quantos estão na turma; {@code responderam}: quantos têm ao menos uma resposta. */
    public record DevolutivaDaTurma(
            Integer turmaId, String turma, int alunos, int responderam, Devolutiva geral,
            List<QuestaoDaAula> questoesDaAula) {}

    /** A turma inteira: por assunto, e cada questão da aula com a alternativa que mais enganou. */
    @Transactional
    public DevolutivaDaTurma daTurma(Identidade ident, Turma turma, Instant agora) {
        ident.exigirOperador();
        Set<Integer> alunos = new HashSet<>();
        contas.alunosDaTurma(ident, turma).alunos().forEach(a -> alunos.add(a.id()));
        var respostas = respostasDe(alunos, false, agora);
        var responderam = (int) respostas.stream().map(RespostaAvaliada::alunoId).distinct().count();

        // As questões que a turma vê nas aulas, com o que os alunos dela marcaram.
        record Lugar(EstruturaServico.ItemNaArvore item, String modulo) {}
        var lugares = new ArrayList<Lugar>();
        for (var m : estrutura.arvoreDaTurma(turma, true, false, agora)) {
            for (var s : m.submodulos()) {
                s.itens().stream().filter(i -> i.questao() != null).forEach(i -> lugares.add(new Lugar(i, m.nome())));
            }
        }
        var marcadas = new HashMap<Integer, List<ExerciciosServico.Resposta>>();
        exercicios.respostasNosItens(lugares.stream().map(l -> l.item().id()).toList()).stream()
                .filter(r -> alunos.contains(r.alunoId()))
                .forEach(r -> marcadas.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r));
        var etiquetas = etiquetasDas(lugares.stream().map(l -> l.item().questao().questaoId()).collect(java.util.stream.Collectors.toSet()));

        var questoes = new ArrayList<QuestaoDaAula>();
        for (var l : lugares) {
            var doItem = marcadas.getOrDefault(l.item().id(), List.of());
            var acertos = (int) doItem.stream().filter(ExerciciosServico.Resposta::correta).count();
            var distribuicao = new java.util.TreeMap<Letra, Integer>();
            doItem.forEach(r -> distribuicao.merge(r.marcada(), 1, Integer::sum));
            var q = em.find(Questao.class, l.item().questao().questaoId());
            var e = etiquetas.get(l.item().questao().questaoId());
            questoes.add(new QuestaoDaAula(l.item().id(), l.item().nome(), l.modulo(), l.item().questao().questaoId(),
                    l.item().questao().resumo(), e == null ? null : (e.subassunto() != null ? e.subassunto() : e.assunto()),
                    q == null ? null : q.getGabarito(), doItem.size(), acertos,
                    AnalyticsServico.percentual(acertos, doItem.size()), distribuicao));
        }
        // As mais erradas primeiro; as que ninguém respondeu ainda vão para o fim.
        questoes.sort(Comparator.comparing((QuestaoDaAula q) -> q.respostas() == 0)
                .thenComparingDouble(QuestaoDaAula::percentual));

        return new DevolutivaDaTurma(turma.getId(), turma.getNome(), alunos.size(), responderam,
                montar(respostas, null, agora), questoes);
    }
}
