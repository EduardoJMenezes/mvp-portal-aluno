package br.com.plataforma.simulados;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SimuladoRepositorio extends JpaRepository<Simulado, Integer> {

    List<Simulado> findAllByOrderByCriadoEmDesc();
}
