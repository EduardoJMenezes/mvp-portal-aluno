package br.com.plataforma.taxonomia;

import br.com.plataforma.materiais.Material;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MaterialAssuntoRepositorio extends JpaRepository<MaterialAssunto, Integer> {

    List<MaterialAssunto> findByMaterial(Material material);
}
