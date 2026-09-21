package br.com.plataforma.taxonomia;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssuntoRepositorio extends JpaRepository<Assunto, Integer> {

    List<Assunto> findAllByOrderByNomeAsc();

    Optional<Assunto> findFirstByNomeIgnoreCase(String nome);
}
