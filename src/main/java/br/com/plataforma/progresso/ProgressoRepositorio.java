package br.com.plataforma.progresso;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ProgressoRepositorio extends JpaRepository<Progresso, Integer> {

    Optional<Progresso> findByItemIdAndAlunoId(Integer itemId, Integer alunoId);

    List<Progresso> findByAlunoIdAndItemIdIn(Integer alunoId, Collection<Integer> itens);

    List<Progresso> findByItemIdIn(Collection<Integer> itens);

    /**
     * Onde o aluno está no vídeo. Conclui quando a posição <b>cruza</b> o limiar: quem desmarcou
     * a aula já perto do fim não a vê marcada de novo no aviso seguinte do player.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO item_progress (item_id, aluno_id, posicao_segundos, duracao_segundos, concluido_em, visto_em)
            VALUES (:item, :aluno, :posicao, :duracao, CASE WHEN :posicao >= :limiar THEN CAST(:agora AS timestamptz) END, :agora)
            ON CONFLICT (item_id, aluno_id) DO UPDATE SET
                concluido_em = COALESCE(item_progress.concluido_em,
                    CASE WHEN EXCLUDED.posicao_segundos >= :limiar AND item_progress.posicao_segundos < :limiar
                         THEN EXCLUDED.visto_em END),
                posicao_segundos = EXCLUDED.posicao_segundos,
                duracao_segundos = EXCLUDED.duracao_segundos,
                visto_em = EXCLUDED.visto_em""", nativeQuery = true)
    void registrar(Integer item, Integer aluno, int posicao, int duracao, int limiar, Instant agora);

    /** O aluno marcou: a data da primeira conclusão fica. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO item_progress (item_id, aluno_id, concluido_em, visto_em)
            VALUES (:item, :aluno, :agora, :agora)
            ON CONFLICT (item_id, aluno_id) DO UPDATE SET
                concluido_em = COALESCE(item_progress.concluido_em, EXCLUDED.concluido_em),
                visto_em = EXCLUDED.visto_em""", nativeQuery = true)
    void concluir(Integer item, Integer aluno, Instant agora);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE item_progress SET concluido_em = NULL, visto_em = :agora WHERE item_id = :item AND aluno_id = :aluno",
            nativeQuery = true)
    void desmarcar(Integer item, Integer aluno, Instant agora);
}
