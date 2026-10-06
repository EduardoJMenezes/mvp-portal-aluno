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
 * pode assistir este vídeo?". Hoje de três fontes — a matrícula, para o vídeo do curso; a prova
 * feita, para a resolução de um simulado já fechado; e a questão da aula já respondida, para a
 * resolução dela.
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

    private final br.com.plataforma.agenda.LiberacaoServico liberacao;

    public AcessoServico(br.com.plataforma.agenda.LiberacaoServico liberacao) {
        this.liberacao = liberacao;
    }

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

        var doCurso = doCurso(ident, "i.video.id", ids, agora).stream().map(i -> i.getVideo().getId()).toList();

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

        // Na aula o gabarito sai na hora, e a resolução vai junto: basta ter respondido.
        var deQuestaoRespondida = em.createQuery("""
                select q.video.id from Questao q
                 where q.video.id in :ids
                   and exists (select 1 from RespostaDeExercicio r
                                where r.questaoId = q.id and r.alunoId = :usuario)""",
                Integer.class)
                .setParameter("ids", ids)
                .setParameter("usuario", ident.usuarioId())
                .getResultList();

        var liberados = new LinkedHashSet<Integer>(doCurso);
        liberados.addAll(deProvaFeita);
        liberados.addAll(deQuestaoRespondida);
        return liberados;
    }

    /** Quais destes itens do curso o aluno vê — é por eles que chega o PDF da aula (decisão 0013). */
    @Transactional(readOnly = true)
    public Set<Integer> itensLiberados(Identidade ident, Collection<Integer> itemIds, Instant agora) {
        var ids = new LinkedHashSet<>(itemIds);
        if (ids.isEmpty() || ident.eOperador()) {
            return ids;
        }
        return doCurso(ident, "i.id", ids, agora).stream().map(br.com.plataforma.estrutura.Item::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Os itens publicados que a turma do aluno vê (decisão 0011) e a agenda já liberou (0012). */
    private java.util.List<br.com.plataforma.estrutura.Item> doCurso(Identidade ident, String campo,
            Collection<Integer> ids, Instant agora) {
        var turmas = em.createQuery("select m.turma from Matricula m where m.usuarioId = :usuario",
                br.com.plataforma.catalogo.Turma.class).setParameter("usuario", ident.usuarioId()).getResultList();
        var agenda = liberacao.das(turmas.stream().map(br.com.plataforma.catalogo.Turma::getId).toList());
        return em.createQuery("""
                select i from Item i
                 where %s in :ids
                   and i.status = br.com.plataforma.comum.Status.PUBLICADO
                   and exists (select 1 from Matricula m
                                where m.usuarioId = :usuario
                                  and (m.turma member of i.turmas
                                       or (i.turmas is empty
                                           and m.turma member of i.submodulo.modulo.turmas)))""".formatted(campo),
                        br.com.plataforma.estrutura.Item.class)
                .setParameter("ids", ids)
                .setParameter("usuario", ident.usuarioId())
                .getResultList().stream()
                .filter(i -> turmas.stream().anyMatch(t -> i.visivelPara(t) && agenda.liberado(t.getId(),
                        i.getSubmodulo().getModulo() == null ? null : i.getSubmodulo().getModulo().getId(), i.getId(), agora)))
                .toList();
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
