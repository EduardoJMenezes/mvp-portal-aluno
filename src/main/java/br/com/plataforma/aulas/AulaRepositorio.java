package br.com.plataforma.aulas;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AulaRepositorio extends JpaRepository<Aula, Integer> {

    List<Aula> findAllByOrderByInicioEmDesc();
}

interface AulaPresencaRepositorio extends JpaRepository<AulaPresenca, Integer> {

    Optional<AulaPresenca> findByAulaIdAndUsuarioId(Integer aulaId, Integer usuarioId);

    @Modifying
    @Query("delete from AulaPresenca p where p.aulaId = :aulaId")
    void apagarDaAula(Integer aulaId);
}
