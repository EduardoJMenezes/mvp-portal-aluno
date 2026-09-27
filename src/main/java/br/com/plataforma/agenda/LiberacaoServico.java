package br.com.plataforma.agenda;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quando a aula ou o módulo sai para cada turma, segundo a agenda (decisão 0012).
 *
 * <p>Fica à parte do {@link AgendaServico} e sem depender de nada além do banco: quem monta o
 * curso e quem libera o vídeo perguntam aqui, e a agenda depende deles para mostrar os nomes.
 */
@Service
public class LiberacaoServico {

    @PersistenceContext
    private EntityManager em;

    /** Chave "turma:conteúdo" → a hora do primeiro evento que liga aquele conteúdo àquela turma. */
    public record Liberacoes(Map<String, Instant> porItem, Map<String, Instant> porModulo) {

        public static final Liberacoes NENHUMA = new Liberacoes(Map.of(), Map.of());

        /**
         * O evento da aula vale mais que o do módulo (o mais específico). Conteúdo sem evento para
         * a turma está liberado: a agenda não é o reflexo do curso.
         */
        public boolean liberado(Integer turmaId, Integer moduloId, Integer itemId, Instant agora) {
            var daAula = porItem.get(turmaId + ":" + itemId);
            if (daAula != null) {
                return !agora.isBefore(daAula);
            }
            var doModulo = moduloId == null ? null : porModulo.get(turmaId + ":" + moduloId);
            return doModulo == null || !agora.isBefore(doModulo);
        }
    }

    @Transactional(readOnly = true)
    public Liberacoes das(Collection<Integer> turmaIds) {
        if (turmaIds.isEmpty()) {
            return Liberacoes.NENHUMA;
        }
        @SuppressWarnings("unchecked")
        var linhas = (java.util.List<Object[]>) em.createNativeQuery("""
                SELECT ec.turma_id, e.modulo_id, e.item_id, min(e.inicio_em)
                  FROM agenda_events e
                  JOIN agenda_event_classes ec ON ec.evento_id = e.id
                 WHERE e.removido_em IS NULL
                   AND (e.modulo_id IS NOT NULL OR e.item_id IS NOT NULL)
                   AND ec.turma_id IN (:turmas)
                 GROUP BY ec.turma_id, e.modulo_id, e.item_id""")
                .setParameter("turmas", turmaIds)
                .getResultList();
        var porItem = new HashMap<String, Instant>();
        var porModulo = new HashMap<String, Instant>();
        for (var l : linhas) {
            var turma = ((Number) l[0]).intValue();
            var quando = instante(l[3]);
            if (l[2] != null) {
                porItem.put(turma + ":" + ((Number) l[2]).intValue(), quando);
            } else {
                porModulo.put(turma + ":" + ((Number) l[1]).intValue(), quando);
            }
        }
        return new Liberacoes(porItem, porModulo);
    }

    private static Instant instante(Object valor) {
        return switch (valor) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Data inesperada do banco: " + valor.getClass());
        };
    }
}
