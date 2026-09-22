package br.com.plataforma.contas;

import br.com.plataforma.comum.Papel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Sem {@code public}: fora de {@code contas} só se chega ao usuário pelo {@link ContasServico}. */
interface UsuarioRepositorio extends JpaRepository<Usuario, Integer> {

    Optional<Usuario> findFirstByEmailIgnoreCase(String email);

    List<Usuario> findByPapelOrderByNomeAsc(Papel papel);

    List<Usuario> findByEmailEndingWith(String sufixo);
}
