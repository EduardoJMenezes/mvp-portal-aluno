package br.com.plataforma.exercicios;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A análise que o aluno vê quando termina as questões de uma seção: a grade de acertos e erros,
 * o acerto por assunto ao lado da média da turma, e duas frases curtas — onde foi bem, onde
 * errou e o que estudar.
 *
 * <p>A turma entra pela dificuldade de cada questão: errar uma que quase todos os colegas
 * acertaram pede atenção primeiro; errar uma que poucos acertaram é normal. Os erros vêm nessa
 * ordem, e os pontos a estudar são no máximo três, os primeiros dela. A posição dele aparece só do
 * lado bom ("entre os 25% melhores", "acima da metade"): o aluno nunca pode se sentir atrasado.
 *
 * <p>É rasa de propósito, e sem IA: estatística e "errou isto, estude isto". A leitura profunda
 * (padrões de erro, por que errou, plano de estudo) é do professor, que a gera pelo Claude dele e
 * decide mostrar — o aluno não pode levar daqui um diagnóstico que valha uma consultoria.
 *
 * <p>Com gabarito no fim, nada sai antes de ele responder a última: só {@code faltam}.
 */
@Service
public class AnaliseDaSecaoServico {

    private final ExerciciosServico exercicios;
    private final EstruturaServico estrutura;
    private final RespostaDeExercicioRepositorio respostas;

    @PersistenceContext
    private EntityManager em;

    public AnaliseDaSecaoServico(ExerciciosServico exercicios, EstruturaServico estrutura,
            RespostaDeExercicioRepositorio respostas) {
        this.exercicios = exercicios;
        this.estrutura = estrutura;
        this.respostas = respostas;
    }

    /**
     * Uma questão da grade. {@code assunto}/{@code subassunto}: a etiqueta dela, se tiver.
     * {@code turmaAcertou}: quantos % dos colegas que a responderam acertaram (ele fora), ou nulo
     * quando nenhum colega respondeu; {@code colegas} é quantos foram.
     */
    public record QuestaoDaAnalise(Integer itemId, String nome, boolean correta, String assunto, String subassunto,
            Integer turmaAcertou, int colegas) {}

    /** {@code mediaDaTurma}: o acerto dos colegas de turma (ele incluído) nas mesmas questões, em %. */
    public record AssuntoDaAnalise(String assunto, int acertos, int questoes, int percentual,
            int mediaDaTurma, int alunosNaMedia) {}

    /** Um ponto a estudar: as questões que ele errou ali e os vídeos do capítulo marcados com ele. */
    public record ParaEstudar(String ponto, List<Ligacao> questoes, List<Ligacao> videos) {}

    public record Ligacao(Integer itemId, Integer secaoId, String nome) {}

    /**
     * {@code pronta}: ele respondeu todas e a correção saiu; até lá, só {@code faltam} vem
     * preenchido.
     */
    /**
     * {@code erros}: as que ele errou, da que a turma mais acertou para a que menos acertou.
     * {@code posicao}: a frase do lado bom, ou nulo — abaixo da metade, ou sem colega que tenha
     * terminado, ela não aparece.
     */
    public record AnaliseDaSecao(Integer moduloId, String modulo, Integer secaoId, String secao,
            boolean pronta, int faltam, int acertos, int total, int percentual,
            List<QuestaoDaAnalise> questoes, List<AssuntoDaAnalise> porAssunto,
            String sobreOsAcertos, String sobreOsErros, List<QuestaoDaAnalise> erros,
            String posicao, List<ParaEstudar> paraEstudar) {}

    /** Uma resposta de colega numa questão da seção. */
    private record DoColega(int alunoId, int itemId, boolean correta) {}

    @Transactional(readOnly = true)
    public AnaliseDaSecao abrir(Identidade ident, Integer secaoId, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("A análise da seção é do aluno: o professor acompanha pelo desempenho dele.");
        }
        var sub = estrutura.submodulo(secaoId)
                .orElseThrow(() -> new NaoEncontrado("Esta seção não está no seu curso."));
        var linhas = exercicios.linhasVisiveis(ident, sub, agora);
        if (linhas.isEmpty()) {
            throw new RegraDeNegocio("'%s' não tem questões para analisar.".formatted(sub.getNome()));
        }
        var modulo = sub.getModulo();
        var ids = linhas.stream().map(Item::getId).toList();
        var minhas = respostas.findByAlunoIdAndItemIdIn(ident.usuarioId(), ids).stream()
                .collect(Collectors.toMap(RespostaDeExercicio::getItemId, r -> r));
        var faltam = (int) ids.stream().filter(id -> !minhas.containsKey(id)).count();
        if (faltam > 0) {
            return new AnaliseDaSecao(modulo.getId(), modulo.getNome(), sub.getId(), sub.getNome(), false, faltam,
                    0, ids.size(), 0, List.of(), List.of(), null, null, List.of(), null, List.of());
        }

        var colegas = respostasDosColegas(ident, ids);
        var porItem = colegas.stream().collect(Collectors.groupingBy(DoColega::itemId));
        var etiquetas = etiquetasDasQuestoes(linhas.stream().map(i -> i.getQuestao().getId()).toList());
        var questoes = linhas.stream().map(i -> {
            var e = etiquetas.get(i.getQuestao().getId());
            var deles = porItem.getOrDefault(i.getId(), List.of());
            var certas = (int) deles.stream().filter(DoColega::correta).count();
            return new QuestaoDaAnalise(i.getId(), i.getNome(), minhas.get(i.getId()).isCorreta(),
                    e == null ? null : e.assunto(), e == null ? null : e.subassunto(),
                    deles.isEmpty() ? null : percentual(certas, deles.size()), deles.size());
        }).toList();
        var acertos = (int) questoes.stream().filter(QuestaoDaAnalise::correta).count();

        var erros = porPrioridade(questoes);
        var porAssunto = porAssunto(ident, questoes, colegas);
        return new AnaliseDaSecao(modulo.getId(), modulo.getNome(), sub.getId(), sub.getNome(), true, 0,
                acertos, questoes.size(), percentual(acertos, questoes.size()), questoes, porAssunto,
                sobreOsAcertos(acertos, questoes.size(), porAssunto), sobreOsErros(erros), erros,
                posicao(acertos, ids.size(), colegas),
                paraEstudar(modulo.getId(), sub.getId(), erros));
    }

    // --- a estatística -------------------------------------------------------------

    private record Etiqueta(String assunto, String subassunto, Integer assuntoId, Integer subassuntoId) {}

    @SuppressWarnings("unchecked")
    private Map<Integer, Etiqueta> etiquetasDasQuestoes(Collection<Integer> questoes) {
        var saida = new HashMap<Integer, Etiqueta>();
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT qs.questao_id, s.nome, t.nome, s.id, t.id
                  FROM question_subjects qs
                  JOIN subjects s ON s.id = qs.assunto_id AND s.removido_em IS NULL
                  LEFT JOIN subtopics t ON t.id = qs.subassunto_id AND t.removido_em IS NULL
                 WHERE qs.questao_id IN (:questoes)
                 ORDER BY qs.id""").setParameter("questoes", questoes).getResultList();
        for (var l : linhas) {
            saida.putIfAbsent(((Number) l[0]).intValue(), new Etiqueta((String) l[1], (String) l[2],
                    ((Number) l[3]).intValue(), l[4] == null ? null : ((Number) l[4]).intValue()));
        }
        return saida;
    }

    /** O que os colegas de turma dele (ele fora) responderam nas questões da seção. */
    @SuppressWarnings("unchecked")
    private List<DoColega> respostasDosColegas(Identidade ident, List<Integer> itens) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT r.aluno_id, r.item_id, r.correta FROM item_answers r
                 WHERE r.item_id IN (:itens) AND r.aluno_id <> :aluno
                   AND r.aluno_id IN (
                        SELECT e2.usuario_id FROM enrollments e1
                          JOIN enrollments e2 ON e2.turma_id = e1.turma_id
                         WHERE e1.usuario_id = :aluno)""")
                .setParameter("itens", itens)
                .setParameter("aluno", ident.usuarioId())
                .getResultList();
        return linhas.stream().map(l -> new DoColega(((Number) l[0]).intValue(), ((Number) l[1]).intValue(),
                Boolean.TRUE.equals(l[2]))).toList();
    }

    /**
     * O acerto dele por assunto e o da turma nas mesmas questões. A turma são os colegas que
     * dividem turma com ele e responderam essas questões — ele incluído, então a média existe
     * desde o primeiro aluno.
     */
    private List<AssuntoDaAnalise> porAssunto(Identidade ident, List<QuestaoDaAnalise> questoes, List<DoColega> colegas) {
        var assuntoDoItem = new HashMap<Integer, String>();
        questoes.forEach(q -> assuntoDoItem.put(q.itemId(), q.assunto() == null ? "Sem assunto" : q.assunto()));
        var daTurma = new ArrayList<>(colegas);
        questoes.forEach(q -> daTurma.add(new DoColega(ident.usuarioId(), q.itemId(), q.correta())));

        record Conta(int certas, int todas, java.util.Set<Integer> alunos) {}
        var turma = new HashMap<String, Conta>();
        for (var r : daTurma) {
            var assunto = assuntoDoItem.get(r.itemId());
            var c = turma.getOrDefault(assunto, new Conta(0, 0, new java.util.HashSet<>()));
            c.alunos().add(r.alunoId());
            turma.put(assunto, new Conta(c.certas() + (r.correta() ? 1 : 0), c.todas() + 1, c.alunos()));
        }

        var dele = new LinkedHashMap<String, int[]>();
        questoes.forEach(q -> {
            var a = dele.computeIfAbsent(assuntoDoItem.get(q.itemId()), k -> new int[2]);
            a[0] += q.correta() ? 1 : 0;
            a[1]++;
        });
        var saida = new ArrayList<AssuntoDaAnalise>();
        dele.forEach((assunto, a) -> {
            var t = turma.getOrDefault(assunto, new Conta(a[0], a[1], java.util.Set.of(ident.usuarioId())));
            saida.add(new AssuntoDaAnalise(assunto, a[0], a[1], percentual(a[0], a[1]),
                    percentual(t.certas(), t.todas()), t.alunos().size()));
        });
        return saida;
    }

    private static int percentual(int parte, int todo) {
        return todo == 0 ? 0 : Math.round(100f * parte / todo);
    }

    /**
     * As erradas, da que a turma mais acertou para a que menos acertou; as que nenhum colega
     * respondeu vão por último, na ordem da seção.
     */
    private static List<QuestaoDaAnalise> porPrioridade(List<QuestaoDaAnalise> questoes) {
        return questoes.stream().filter(q -> !q.correta())
                .sorted(java.util.Comparator.comparing(QuestaoDaAnalise::turmaAcertou,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Onde ele fica entre os colegas que terminaram a seção, só do lado bom: se ficou acima de
     * três quartos deles, "entre os 25% melhores"; acima da metade, "acima da metade". Fica acima
     * de quem acertou menos que ele — empate não conta.
     */
    private static String posicao(int acertos, int questoes, List<DoColega> colegas) {
        var porAluno = colegas.stream().collect(Collectors.groupingBy(DoColega::alunoId));
        var terminaram = porAluno.values().stream().filter(r -> r.size() == questoes)
                .map(r -> (int) r.stream().filter(DoColega::correta).count())
                .toList();
        if (terminaram.isEmpty()) {
            return null;
        }
        var abaixo = terminaram.stream().filter(c -> c < acertos).count();
        var fracao = (double) abaixo / terminaram.size();
        if (fracao >= 0.75) {
            return "Neste bloco, você ficou entre os 25% melhores da turma.";
        }
        if (fracao >= 0.5) {
            return "Neste bloco, você ficou acima da metade da turma.";
        }
        return null;
    }

    // --- as duas frases ---------------------------------------------------------------

    private static String sobreOsAcertos(int acertos, int total, List<AssuntoDaAnalise> porAssunto) {
        if (acertos == 0) {
            return "Desta vez nenhuma questão saiu certa. Comece pelos pontos de estudo abaixo.";
        }
        if (acertos == total) {
            return "Você acertou todas as %d questões. Excelente!".formatted(total);
        }
        var bons = porAssunto.stream().filter(a -> a.acertos() > 0 && !"Sem assunto".equals(a.assunto()))
                .sorted((x, y) -> Integer.compare(y.percentual(), x.percentual()))
                .limit(3)
                .map(a -> "%s (%d de %d)".formatted(a.assunto(), a.acertos(), a.questoes()))
                .toList();
        var frase = "Você acertou %d de %d questões.".formatted(acertos, total);
        return bons.isEmpty() ? frase : frase + " Foi melhor em " + juntar(bons) + ".";
    }

    /** {@code erradas}: em ordem de prioridade, que é a ordem em que os pontos aparecem. */
    private static String sobreOsErros(List<QuestaoDaAnalise> erradas) {
        if (erradas.isEmpty()) {
            return "Nenhum erro neste bloco.";
        }
        var pontos = new LinkedHashMap<String, List<String>>();
        erradas.forEach(q -> pontos.computeIfAbsent(ponto(q), k -> new ArrayList<>()).add(q.nome()));
        var lista = pontos.entrySet().stream().limit(4)
                .map(e -> "%s (%s)".formatted(e.getKey(), String.join(", ", e.getValue()))).toList();
        var resto = pontos.size() > 4 ? " e em mais %d ponto(s)".formatted(pontos.size() - 4) : "";
        return "Você errou em %s%s. Estude esses pontos e reveja a resolução dessas questões."
                .formatted(juntar(lista), resto);
    }

    /** O ponto a estudar de uma questão: o sub-assunto, ou o assunto quando ela não tem. */
    private static String ponto(QuestaoDaAnalise q) {
        return q.subassunto() != null ? q.subassunto() : q.assunto() != null ? q.assunto() : "Questões sem assunto";
    }

    private static String juntar(List<String> partes) {
        if (partes.size() <= 1) {
            return String.join("", partes);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " e " + partes.getLast();
    }

    // --- o que estudar -------------------------------------------------------------------

    /**
     * Os próximos passos, três no máximo: os primeiros pontos dos erros em ordem de prioridade,
     * cada um com as questões erradas ali (abrem com a resolução) e os vídeos das outras seções do
     * capítulo marcados com o mesmo sub-assunto — ou com o assunto, quando a questão não tem
     * sub-assunto.
     */
    @SuppressWarnings("unchecked")
    private List<ParaEstudar> paraEstudar(Integer moduloId, Integer secaoId, List<QuestaoDaAnalise> erradas) {
        if (erradas.isEmpty()) {
            return List.of();
        }
        List<Object[]> videos = em.createNativeQuery("""
                SELECT i.id, i.submodulo_id, i.nome, s.nome, t.nome
                  FROM items i
                  JOIN submodules sm ON sm.id = i.submodulo_id AND sm.removido_em IS NULL
                  JOIN video_subjects vs ON vs.video_id = i.video_id
                  JOIN subjects s ON s.id = vs.assunto_id AND s.removido_em IS NULL
                  LEFT JOIN subtopics t ON t.id = vs.subassunto_id AND t.removido_em IS NULL
                 WHERE sm.modulo_id = :modulo AND i.status = 'PUBLICADO' AND i.removido_em IS NULL
                 ORDER BY sm.ordem, i.ordem""").setParameter("modulo", moduloId).getResultList();

        var pontos = new LinkedHashMap<String, ParaEstudar>();
        for (var q : erradas) {
            var nome = ponto(q);
            if (!pontos.containsKey(nome) && pontos.size() == 3) {
                continue;
            }
            var atual = pontos.computeIfAbsent(nome, k -> new ParaEstudar(k, new ArrayList<>(), new ArrayList<>()));
            atual.questoes().add(new Ligacao(q.itemId(), secaoId, q.nome()));
            if (atual.videos().isEmpty()) {
                for (var v : videos) {
                    var assunto = (String) v[3];
                    var subassunto = (String) v[4];
                    var casa = q.subassunto() != null ? q.subassunto().equals(subassunto)
                            : q.assunto() != null && q.assunto().equals(assunto) && subassunto == null;
                    // Três no máximo: é um "estude isto", não um plano de estudo.
                    if (casa && atual.videos().size() < 3) {
                        atual.videos().add(new Ligacao(((Number) v[0]).intValue(), ((Number) v[1]).intValue(), (String) v[2]));
                    }
                }
            }
        }
        return List.copyOf(pontos.values());
    }
}
