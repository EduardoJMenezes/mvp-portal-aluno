package br.com.plataforma.menu;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BotaoDoMenuRepositorio extends JpaRepository<BotaoDoMenu, Integer> {

    List<BotaoDoMenu> findByTurmaIdOrderByOrdemAsc(Integer turmaId);

    List<BotaoDoMenu> findByTurmaIdInOrderByTurmaIdAscOrdemAsc(Collection<Integer> turmaIds);

    @Modifying
    @Query("delete from BotaoDoMenu b where b.turmaId = :turmaId")
    void apagarDaTurma(Integer turmaId);
}
