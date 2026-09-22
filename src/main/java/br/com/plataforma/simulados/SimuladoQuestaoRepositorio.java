package br.com.plataforma.simulados;

import br.com.plataforma.questoes.Questao;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SimuladoQuestaoRepositorio extends JpaRepository<SimuladoQuestao, Integer> {

    @Query("""
            select sq.simulado from SimuladoQuestao sq
             where sq.questao = :questao
             order by sq.simulado.id""")
    List<Simulado> simuladosDa(Questao questao);
}
