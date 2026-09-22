package br.com.plataforma.simulados;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TentativaRepositorio extends JpaRepository<Tentativa, Integer> {

    int countBySimulado(Simulado simulado);

    @Query("select t from Tentativa t left join fetch t.respostas"
            + " where t.simulado = :simulado and t.aluno.id = :alunoId")
    java.util.Optional<Tentativa> doAluno(Simulado simulado, Integer alunoId);

    /** Simulado removido fica de fora: o filtro é explícito porque o join não herda o dele. */
    @Query("select t from Tentativa t left join fetch t.respostas"
            + " where t.aluno.id = :alunoId and t.simulado.removidoEm is null order by t.iniciadoEm")
    List<Tentativa> todasDoAluno(Integer alunoId);

    @Query("select distinct t from Tentativa t join fetch t.aluno left join fetch t.respostas"
            + " where t.simulado = :simulado")
    List<Tentativa> findBySimulado(Simulado simulado);

    /** Nativa porque a matrícula ainda não tem entidade: entra quando as contas forem portadas. */
    @Query(value = """
            SELECT c.nome FROM enrollments e
              JOIN classes c ON c.id = e.turma_id
             WHERE e.usuario_id = :alunoId AND e.turma_id IN (:turmaIds)
             ORDER BY c.nome""", nativeQuery = true)
    List<String> turmasDoAluno(Integer alunoId, List<Integer> turmaIds);
}
