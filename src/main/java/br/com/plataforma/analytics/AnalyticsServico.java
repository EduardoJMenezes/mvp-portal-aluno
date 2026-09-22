package br.com.plataforma.analytics;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.Papel;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladoQuestao;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import br.com.plataforma.simulados.Tentativa;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Como o aluno foi, e como a turma foi — a leitura que o professor pede ao Claude. */
@Service
public class AnalyticsServico {

    private final SimuladosServico simulados;
    private final TaxonomiaServico taxonomia;
    private final AcessoServico acesso;
    private final br.com.plataforma.contas.ContasServico contas;

    @PersistenceContext
    private EntityManager em;

    public AnalyticsServico(SimuladosServico simulados, TaxonomiaServico taxonomia,
            AcessoServico acesso, br.com.plataforma.contas.ContasServico contas) {
        this.simulados = simulados;
        this.taxonomia = taxonomia;
        this.acesso = acesso;
        this.contas = contas;
    }

    // --- resolver o aluno ----------------------------------------------------

    @Transactional(readOnly = true)
    public Usuario resolverAluno(String referencia) {
        return contas.resolverAluno(referencia);
    }

    // --- recomendação --------------------------------------------------------

    public record Recomendacao(String topico, int erros, List<AcessoServico.VideoDescrito> videos) {}

    /**
     * O elo que faltava: do erro do aluno para o vídeo que explica aquilo.
     *
     * <p>Vale o acervo inteiro, não só a turma dele. O vídeo de outro curso aparece bloqueado —
     * nome e aviso —, porque esconder o material que responde exatamente à dúvida seria pior do
     * que mostrar que ele existe.
     */
    @Transactional(readOnly = true)
    public List<Recomendacao> recomendarVideos(
            Identidade ident, List<Integer> questoesErradas, int limite, Instant agora) {
        var porEtiqueta = new LinkedHashMap<String, Integer>();
        var alvos = new LinkedHashMap<String, int[]>();

        for (var questaoId : questoesErradas) {
            var etiqueta = etiquetaDaQuestao(questaoId);
            if (etiqueta == null) {
                continue;
            }
            var chave = etiqueta[0] + ":" + (etiqueta[1] == null ? "" : etiqueta[1]);
            porEtiqueta.merge(chave, 1, Integer::sum);
            alvos.putIfAbsent(chave, new int[] {etiqueta[0], etiqueta[1] == null ? -1 : etiqueta[1]});
        }

        return porEtiqueta.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(par -> {
                    var alvo = alvos.get(par.getKey());
                    var subassuntoId = alvo[1] < 0 ? null : alvo[1];
                    var videos = taxonomia.videosQueExplicam(alvo[0], subassuntoId, limite);
                    var liberados = acesso.videosLiberados(ident,
                            videos.stream().map(Video::getId).toList(), agora);
                    return new Recomendacao(taxonomia.rotuloDa(alvo[0], subassuntoId), par.getValue(),
                            videos.stream()
                                    .map(v -> AcessoServico.descrever(v, liberados.contains(v.getId())))
                                    .toList());
                })
                .toList();
    }

    /**
     * {assuntoId, subassuntoId} da questão, ou nulo quando ela não tem etiqueta.
     *
     * <p>O {@code left join} é obrigatório, não estilo: {@code qa.subassunto} carrega
     * {@code @NotFound(IGNORE)}, e com ele o Hibernate deixa de confiar na chave estrangeira e
     * passa a juntar a tabela para conferir se a linha existe. Escrito como navegação implícita
     * ({@code qa.subassunto.id}), essa junção sai <b>interna</b> — e some com toda questão
     * etiquetada só por assunto, que é a maioria.
     */
    private Integer[] etiquetaDaQuestao(Integer questaoId) {
        return em.createQuery("""
                select qa.assunto.id, sa.id from QuestaoAssunto qa
                 left join qa.subassunto sa
                 where qa.questao.id = :id""", Object[].class)
                .setParameter("id", questaoId).setMaxResults(1).getResultList().stream()
                .map(linha -> new Integer[] {(Integer) linha[0], (Integer) linha[1]})
                .findFirst().orElse(null);
    }

    private String topicoDaQuestao(Integer questaoId) {
        var etiqueta = etiquetaDaQuestao(questaoId);
        return etiqueta == null ? null : taxonomia.rotuloDa(etiqueta[0], etiqueta[1]);
    }

    // --- desempenho de um aluno ----------------------------------------------

    public record QuestaoDoAluno(
            Integer ordem, Integer questaoId, String enunciado, String topico, Letra marcada,
            Letra gabarito, boolean correta) {}

    /** Serializa como {@code ["tópico", n]}: é o par que o portal sempre recebeu do Python. */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.ARRAY)
    public record ErrosPorTopico(String topico, int erros) {}

    public record DesempenhoDoAluno(
            String aluno, boolean encontrouDados, String mensagem, String simulado,
            Integer simuladoId, List<String> turmas, Situacao situacao, Boolean entregue,
            Boolean entregueAutomaticamente, Integer acertos, Integer emBranco,
            Integer totalQuestoes, Double percentual, List<QuestaoDoAluno> questoes,
            List<ErrosPorTopico> errosPorTopico, List<Recomendacao> recomendacoes) {}

    /**
     * Como um aluno foi — no último simulado que começou, ou num específico.
     *
     * <p>É a visão do professor, e vale a qualquer momento. O aluno vê o próprio resultado pelo
     * portal, que só abre quando o simulado fecha.
     */
    @Transactional
    public DesempenhoDoAluno desempenhoDoAluno(
            Identidade ident, String aluno, String simulado, Instant agora) {
        ident.exigirOperador();
        var alvo = resolverAluno(aluno);

        var jpql = new StringBuilder(
                "select t from Tentativa t where t.aluno = :aluno");
        if (simulado != null && !simulado.isBlank()) {
            jpql.append(" and t.simulado = :simulado");
        }
        jpql.append(" order by t.iniciadoEm desc");

        var consulta = em.createQuery(jpql.toString(), Tentativa.class).setParameter("aluno", alvo);
        if (simulado != null && !simulado.isBlank()) {
            consulta.setParameter("simulado", simulados.resolver(simulado));
        }
        var tentativa = consulta.setMaxResults(1).getResultList().stream().findFirst().orElse(null);

        if (tentativa == null) {
            return new DesempenhoDoAluno(alvo.getNome(), false,
                    "%s ainda não fez nenhum simulado.".formatted(alvo.getNome()),
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        var s = tentativa.getSimulado();
        var entregue = tentativa.consolidar(agora);
        var marcadas = new LinkedHashMap<Integer, br.com.plataforma.simulados.Resposta>();
        tentativa.getRespostas().forEach(r -> marcadas.put(r.getQuestaoId(), r));

        var questoes = new ArrayList<QuestaoDoAluno>();
        for (var sq : s.getQuestoes()) {
            var r = marcadas.get(sq.getQuestao().getId());
            questoes.add(new QuestaoDoAluno(sq.getOrdem(), sq.getQuestao().getId(),
                    sq.getQuestao().getEnunciado(), topicoDaQuestao(sq.getQuestao().getId()),
                    r == null ? null : r.getAlternativaMarcada(), sq.getQuestao().getGabarito(),
                    r != null && r.isCorreta()));
        }

        var erradas = questoes.stream().filter(q -> !q.correta()).toList();
        var porTopico = new LinkedHashMap<String, Integer>();
        erradas.stream().filter(q -> q.topico() != null)
                .forEach(q -> porTopico.merge(q.topico(), 1, Integer::sum));

        var total = questoes.size();
        var acertos = total - erradas.size();
        return new DesempenhoDoAluno(alvo.getNome(), true, null, s.getTitulo(), s.getId(),
                s.getTurmas().stream().map(Turma::getNome).toList(),
                SimuladosServico.situacao(s, agora), entregue, tentativa.isEntregueAutomaticamente(),
                acertos, (int) questoes.stream().filter(q -> q.marcada() == null).count(), total,
                percentual(acertos, total), questoes,
                porTopico.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .map(e -> new ErrosPorTopico(e.getKey(), e.getValue())).toList(),
                recomendarVideos(ident, erradas.stream().map(QuestaoDoAluno::questaoId).toList(), 5,
                        agora));
    }

    // --- estatísticas da prova -----------------------------------------------

    public record EstatisticaDaQuestao(
            Integer ordem, Integer questaoId, String enunciado, String topico, Letra gabarito,
            int acertos, int emBranco, double percentualAcerto, Map<Letra, Integer> distribuicao) {}

    public record DesempenhoNaProva(
            String aluno, int acertos, int total, double percentual, boolean entregue) {}

    public record MaiorDificuldade(
            Integer questaoId, String topico, String enunciado, double percentualAcerto) {}

    public record EstatisticasDoSimulado(
            String simulado, Integer simuladoId, List<String> turmas, Situacao situacao,
            boolean parcial, int alunosMatriculados, int alunosResponderam, boolean encontrouDados,
            String mensagem, Double mediaPercentual, List<EstatisticaDaQuestao> porQuestao,
            List<DesempenhoNaProva> porAluno, MaiorDificuldade maiorDificuldade) {}

    /** Desempenho de quem fez o simulado, questão a questão. Antes do fechamento, parcial. */
    @Transactional
    public EstatisticasDoSimulado estatisticasDoSimulado(
            Identidade ident, String simulado, Instant agora) {
        ident.exigirOperador();
        var s = simulados.resolver(simulado);
        var quando = SimuladosServico.situacao(s, agora);
        var turmas = s.getTurmas().stream().map(Turma::getNome).toList();

        var matriculados = s.getTurmas().isEmpty() ? 0 : em.createQuery(
                "select count(distinct m.usuarioId) from Matricula m where m.turma in :turmas",
                Long.class).setParameter("turmas", s.getTurmas()).getSingleResult().intValue();

        var tentativas = em.createQuery("""
                select distinct t from Tentativa t join fetch t.aluno left join fetch t.respostas
                 where t.simulado = :s""", Tentativa.class).setParameter("s", s).getResultList();
        tentativas.forEach(t -> t.consolidar(agora));

        if (tentativas.isEmpty()) {
            return new EstatisticasDoSimulado(s.getTitulo(), s.getId(), turmas, quando,
                    quando != Situacao.ENCERRADO, matriculados, 0, false,
                    "Nenhum aluno começou este simulado ainda.", null, null, null, null);
        }

        var participantes = tentativas.size();
        var total = s.getQuestoes().size();
        var respostas = tentativas.stream().flatMap(t -> t.getRespostas().stream()).toList();

        var porQuestao = new ArrayList<EstatisticaDaQuestao>();
        for (var sq : s.getQuestoes()) {
            var doItem = respostas.stream()
                    .filter(r -> r.getQuestaoId().equals(sq.getQuestao().getId())).toList();
            var acertos = (int) doItem.stream().filter(r -> r.isCorreta()).count();
            var distribuicao = new LinkedHashMap<Letra, Integer>();
            doItem.forEach(r -> distribuicao.merge(r.getAlternativaMarcada(), 1, Integer::sum));

            porQuestao.add(new EstatisticaDaQuestao(sq.getOrdem(), sq.getQuestao().getId(),
                    sq.getQuestao().getEnunciado(), topicoDaQuestao(sq.getQuestao().getId()),
                    sq.getQuestao().getGabarito(), acertos, participantes - doItem.size(),
                    // A base é quem fez a prova: em branco conta como erro.
                    percentual(acertos, participantes), distribuicao));
        }

        var porAluno = tentativas.stream()
                .map(t -> new DesempenhoNaProva(t.getAluno().getNome(), t.acertos(), total,
                        percentual(t.acertos(), total), t.getFinalizadoEm() != null))
                .sorted((a, b) -> Double.compare(b.percentual(), a.percentual()))
                .toList();

        var media = arredondar(porAluno.stream().mapToDouble(DesempenhoNaProva::percentual).sum()
                / participantes);
        var pior = porQuestao.stream()
                .min((a, b) -> Double.compare(a.percentualAcerto(), b.percentualAcerto()))
                .orElse(null);

        return new EstatisticasDoSimulado(s.getTitulo(), s.getId(), turmas, quando,
                quando != Situacao.ENCERRADO, matriculados, participantes, true, null, media,
                porQuestao, porAluno,
                pior == null ? null : new MaiorDificuldade(pior.questaoId(), pior.topico(),
                        pior.enunciado(), pior.percentualAcerto()));
    }

    static double percentual(int parte, int total) {
        return total == 0 ? 0.0 : arredondar(100.0 * parte / total);
    }

    static double arredondar(double valor) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.1f", valor));
    }
}
