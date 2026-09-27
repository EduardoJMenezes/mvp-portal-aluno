package br.com.plataforma.estrutura;

import br.com.plataforma.catalogo.Turma;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Sem {@code public}: nenhuma borda alcança o banco sem passar pelo {@link EstruturaServico}. */
interface ModuloRepositorio extends JpaRepository<Modulo, Integer> {

    /** Os módulos que a turma recebe, e os que têm aula só dela (decisão 0011). */
    @Query("""
            select m from Modulo m
             where :turma member of m.turmas
                or exists (select 1 from Item i where i.submodulo.modulo = m and :turma member of i.turmas)
             order by m.ordem, m.id""")
    List<Modulo> daTurma(Turma turma);

    List<Modulo> findAllByOrderByOrdemAscIdAsc();

    @Query("select coalesce(max(m.ordem), 0) from Modulo m")
    int maiorOrdem();
}
