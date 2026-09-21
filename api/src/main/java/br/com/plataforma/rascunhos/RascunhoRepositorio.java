package br.com.plataforma.rascunhos;

import br.com.plataforma.comum.Status;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RascunhoRepositorio extends JpaRepository<Rascunho, Integer> {

    List<Rascunho> findAllByOrderByCriadoEmDesc();

    List<Rascunho> findByStatusOrderByCriadoEmDesc(Status status);
}
