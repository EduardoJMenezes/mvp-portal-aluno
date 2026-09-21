package br.com.plataforma.acervo;

import br.com.plataforma.comum.Identidade;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quem pode assistir o quê — e o que o backend conta sobre o que não pode.
 *
 * <p>Duas responsabilidades que andam juntas. <b>A decisão</b>: um só lugar responde "esta pessoa
 * pode assistir este vídeo?". Hoje de duas fontes — a matrícula, para o vídeo do curso, e a prova
 * feita, para a resolução de um simulado já fechado.
 *
 * <p><b>O que sai no lugar</b>: vídeo bloqueado devolve o nome e mais nada. Isso não é detalhe de
 * tela: {@code videos.embed_url} guarda a URL como o Vimeo devolve, <b>com o hash de
 * privacidade</b> — é ela que faz um vídeo unlisted tocar. Esconder o player no frontend
 * transformaria o bloqueio em decoração, com o devtools liberando o acervo. Não havendo nada no
 * payload, não há o que vazar.
 */
@Service
public class AcessoServico {

    public static final String MOTIVO_BLOQUEIO = "Não incluído no seu plano";

    @PersistenceContext
    private EntityManager em;

    /**
     * Quais destes vídeos esta pessoa pode assistir.
     *
     * <p>Em lote de propósito: a tela do aluno pergunta por dezenas de uma vez, e uma consulta por
     * vídeo viraria dezenas de idas ao banco.
     */
    @Transactional(readOnly = true)
    public Set<Integer> videosLiberados(Identidade ident, Collection<Integer> videoIds, Instant agora) {
        var ids = new LinkedHashSet<>(videoIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        // Operador enxerga o acervo inteiro — é ele quem monta o curso.
        if (ident.eOperador()) {
            return ids;
        }

        var doCurso = em.createQuery("""
                select i.video.id from Item i
                 where i.video.id in :ids
                   and i.status = br.com.plataforma.comum.Status.PUBLICADO
                   and exists (select 1 from Matricula m
                                where m.turma = i.submodulo.modulo.turma
                                  and m.usuarioId = :usuario)""", Integer.class)
                .setParameter("ids", ids)
                .setParameter("usuario", ident.usuarioId())
                .getResultList();

        // A resolução vem com o resultado: para quem fez a prova, depois que ela fecha. Antes
        // disso, o vídeo seria o gabarito com narração.
        var deProvaFeita = em.createQuery("""
                select sq.questao.video.id from SimuladoQuestao sq
                 where sq.questao.video.id in :ids
                   and sq.simulado.status = br.com.plataforma.comum.Status.PUBLICADO
                   and sq.simulado.fechaEm <= :agora
                   and exists (select 1 from Tentativa t
                                where t.simulado = sq.simulado and t.aluno.id = :usuario)""",
                Integer.class)
                .setParameter("ids", ids)
                .setParameter("usuario", ident.usuarioId())
                .setParameter("agora", agora)
                .getResultList();

        var liberados = new LinkedHashSet<Integer>(doCurso);
        liberados.addAll(deProvaFeita);
        return liberados;
    }

    /** O vídeo como ele sai do backend. Bloqueado: nome e aviso, nada que permita assistir. */
    public record VideoDescrito(
            Integer id, String titulo, boolean bloqueado, String motivo, String vimeoId,
            String embedUrl, String thumbnailUrl, Integer duracaoSegundos) {}

    public static VideoDescrito descrever(Video video, boolean liberado) {
        if (video == null) {
            return null;
        }
        if (!liberado) {
            return new VideoDescrito(video.getId(), video.getTitulo(), true, MOTIVO_BLOQUEIO,
                    null, null, null, null);
        }
        return new VideoDescrito(video.getId(), video.getTitulo(), false, null,
                video.getVimeoId(), video.getEmbedUrl(), video.getThumbnailUrl(),
                video.getDuracaoSegundos());
    }
}
