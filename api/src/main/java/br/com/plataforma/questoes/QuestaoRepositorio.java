package br.com.plataforma.questoes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuestaoRepositorio extends JpaRepository<Questao, Integer> {

    Optional<Questao> findById(Integer id);
}
