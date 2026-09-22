package br.com.plataforma.estrutura;

import br.com.plataforma.catalogo.Turma;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Sem {@code public}: nenhuma borda alcança o banco sem passar pelo {@link EstruturaServico}. */
interface ModuloRepositorio extends JpaRepository<Modulo, Integer> {

    Optional<Modulo> findFirstByTurmaAndNomeIgnoreCase(Turma turma, String nome);

    List<Modulo> findByTurmaOrderByOrdemAsc(Turma turma);

    int countByTurma(Turma turma);

    @Query("select coalesce(max(m.ordem), 0) from Modulo m where m.turma = :turma")
    int maiorOrdem(Turma turma);
}
