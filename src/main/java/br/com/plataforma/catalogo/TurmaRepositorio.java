package br.com.plataforma.catalogo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Sem {@code public}: fora de {@code catalogo} só se chega à turma pelo {@link CatalogoServico}. */
interface TurmaRepositorio extends JpaRepository<Turma, Integer> {

    List<Turma> findAllByOrderByNomeAsc();

    List<Turma> findAllByOrderByAnoAscNomeAsc();

    java.util.Optional<Turma> findFirstByNomeIgnoreCase(String nome);

    List<Turma> findByIdInOrderByAnoAscNomeAsc(java.util.Collection<Integer> ids);

    /** Nativa porque a matrícula ainda não tem entidade: ela entra quando as contas forem portadas. */
    @Query(value = "SELECT count(*) FROM enrollments WHERE turma_id = :turmaId", nativeQuery = true)
    int contarAlunos(Integer turmaId);
}
