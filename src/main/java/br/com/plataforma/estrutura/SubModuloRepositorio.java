package br.com.plataforma.estrutura;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SubModuloRepositorio extends JpaRepository<SubModulo, Integer> {

    Optional<SubModulo> findFirstByModuloAndNomeIgnoreCase(Modulo modulo, String nome);

    List<SubModulo> findByModuloOrderByOrdemAsc(Modulo modulo);

    /** Os sub-módulos de vários módulos numa ida só ao banco: é assim que a árvore do curso os pede. */
    @Query("select s from SubModulo s where s.modulo in :modulos order by s.ordem, s.id")
    List<SubModulo> dosModulos(java.util.Collection<Modulo> modulos);

    @Query("select coalesce(max(s.ordem), 0) from SubModulo s where s.modulo = :modulo")
    int maiorOrdem(Modulo modulo);
}
