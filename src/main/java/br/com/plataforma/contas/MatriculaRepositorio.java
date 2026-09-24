package br.com.plataforma.contas;

import br.com.plataforma.catalogo.Turma;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MatriculaRepositorio extends JpaRepository<Matricula, Integer> {

    @Query("select m.turma.id from Matricula m where m.usuarioId = :usuarioId")
    List<Integer> turmasDe(Integer usuarioId);

    /** Turma removida não aparece no perfil: o filtro é explícito porque o join não herda o dela. */
    @Query("select t.nome from Matricula m join m.turma t"
            + " where m.usuarioId = :usuarioId and t.removidoEm is null order by t.nome")
    List<String> nomesDasTurmasDe(Integer usuarioId);

    Optional<Matricula> findByUsuarioIdAndTurma(Integer usuarioId, Turma turma);

    @Modifying
    @Query("delete from Matricula m where m.pedidoId = :pedidoId")
    int apagarDoPedido(Integer pedidoId);

    @Query("select u from Usuario u, Matricula m"
            + " where m.usuarioId = u.id and m.turma = :turma order by u.nome")
    List<Usuario> alunosDa(Turma turma);
}
