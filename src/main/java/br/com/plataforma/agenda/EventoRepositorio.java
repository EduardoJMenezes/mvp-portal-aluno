package br.com.plataforma.agenda;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface EventoRepositorio extends JpaRepository<Evento, Integer> {

    List<Evento> findAllByOrderByInicioEmAscIdAsc();

    /** Os eventos das turmas dadas que já começaram até {@code ate}. */
    @Query("""
            select distinct e from Evento e join e.turmas t
             where t.id in :turmas and e.inicioEm < :ate
             order by e.inicioEm, e.id""")
    List<Evento> dasTurmasAte(Collection<Integer> turmas, Instant ate);
}
