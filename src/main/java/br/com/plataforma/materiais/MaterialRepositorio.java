package br.com.plataforma.materiais;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MaterialRepositorio extends JpaRepository<Material, Integer> {

    List<Material> findAllByOrderByCriadoEmDesc();

    List<Material> findAllByOrderByIdAsc();
}

interface MaterialAnotacaoRepositorio extends JpaRepository<MaterialAnotacao, Integer> {

    List<MaterialAnotacao> findByMaterialIdAndUsuarioIdOrderByPaginaAsc(Integer materialId, Integer usuarioId);

    Optional<MaterialAnotacao> findByMaterialIdAndUsuarioIdAndPagina(
            Integer materialId, Integer usuarioId, Integer pagina);

    /** [materialId, quantas páginas] de quem pergunta. */
    @Query("select a.materialId, count(a) from MaterialAnotacao a where a.usuarioId = :usuarioId"
            + " group by a.materialId")
    List<Object[]> paginasPorMaterial(Integer usuarioId);
}
