package br.com.plataforma.questoes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface FiguraRepositorio extends JpaRepository<Figura, Integer> {

    List<Figura> findByQuestaoOrderByIdAsc(Questao questao);
}
