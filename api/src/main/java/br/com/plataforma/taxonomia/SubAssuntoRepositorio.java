package br.com.plataforma.taxonomia;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubAssuntoRepositorio extends JpaRepository<SubAssunto, Integer> {

    List<SubAssunto> findByAssuntoOrderByNomeAsc(Assunto assunto);

    Optional<SubAssunto> findFirstByAssuntoAndNomeIgnoreCase(Assunto assunto, String nome);
}
