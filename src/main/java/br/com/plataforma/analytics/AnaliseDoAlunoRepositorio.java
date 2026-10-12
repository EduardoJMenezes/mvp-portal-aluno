package br.com.plataforma.analytics;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AnaliseDoAlunoRepositorio extends JpaRepository<AnaliseDoAluno, Integer> {

    Optional<AnaliseDoAluno> findFirstByAlunoIdOrderByIdDesc(Integer alunoId);

    /** A mais recente de cada aluno: é a única que vale. */
    @Query("select a from AnaliseDoAluno a where a.id in (select max(b.id) from AnaliseDoAluno b group by b.alunoId)")
    List<AnaliseDoAluno> ultimas();

    @Query("""
            select a from AnaliseDoAluno a
             where a.id in (select max(b.id) from AnaliseDoAluno b where b.alunoId in :alunos group by b.alunoId)""")
    List<AnaliseDoAluno> ultimasDe(Collection<Integer> alunos);
}
