package br.com.plataforma.analytics;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AnaliseDoAlunoRepositorio extends JpaRepository<AnaliseDoAluno, Integer> {

    /** As de um aluno num simulado, da mais nova para a mais velha: a primeira é a que vale. */
    List<AnaliseDoAluno> findByAlunoIdAndSimuladoIdOrderByIdDesc(Integer alunoId, Integer simuladoId);

    /** As gerais de um aluno, da mais nova para a mais velha. */
    List<AnaliseDoAluno> findByAlunoIdAndSimuladoIdIsNullOrderByIdDesc(Integer alunoId);

    /** A que vale para cada aluno naquele simulado. */
    @Query("""
            select a from AnaliseDoAluno a
             where a.id in (select max(b.id) from AnaliseDoAluno b where b.simuladoId = :simulado group by b.alunoId)""")
    List<AnaliseDoAluno> ultimasDoSimulado(Integer simulado);

    /** A geral que vale para cada aluno. */
    @Query("""
            select a from AnaliseDoAluno a
             where a.id in (select max(b.id) from AnaliseDoAluno b where b.simuladoId is null group by b.alunoId)""")
    List<AnaliseDoAluno> ultimasGerais();
}
