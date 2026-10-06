package br.com.plataforma.exercicios;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RespostaDeExercicioRepositorio extends JpaRepository<RespostaDeExercicio, Integer> {

    Optional<RespostaDeExercicio> findByItemIdAndAlunoId(Integer itemId, Integer alunoId);

    List<RespostaDeExercicio> findByAlunoIdAndItemIdIn(Integer alunoId, Collection<Integer> itens);
}
