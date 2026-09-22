package br.com.plataforma.estrutura;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ItemRepositorio extends JpaRepository<Item, Integer> {

    List<Item> findBySubmoduloOrderByOrdemAsc(SubModulo submodulo);

    List<Item> findBySubmoduloAndStatusOrderByOrdemAsc(SubModulo submodulo, Status status);

    Optional<Item> findFirstBySubmoduloAndVideo(SubModulo submodulo, Video video);

    int countBySubmoduloAndStatus(SubModulo submodulo, Status status);

    List<Item> findByRascunhoIdOrderByOrdemAsc(Integer rascunhoId);

    List<Item> findByRascunhoIdAndStatusOrderByOrdemAsc(Integer rascunhoId, Status status);

    @Query("select coalesce(max(i.ordem), 0) from Item i where i.submodulo = :submodulo")
    int maiorOrdem(SubModulo submodulo);

    @Query("select count(i) from Item i where i.submodulo.modulo = :modulo and i.status = :status")
    int contarNoModulo(Modulo modulo, Status status);

    @Query("select count(i) from Item i where i.submodulo.modulo.turma = :turma and i.status = :status")
    int contarNaTurma(Turma turma, Status status);
}
