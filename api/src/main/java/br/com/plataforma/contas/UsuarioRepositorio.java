package br.com.plataforma.contas;

import org.springframework.data.jpa.repository.JpaRepository;

/** Sem {@code public}: fora de {@code contas} só se chega ao usuário pelo {@link ContasServico}. */
interface UsuarioRepositorio extends JpaRepository<Usuario, Integer> {

    java.util.Optional<Usuario> findFirstByEmailIgnoreCase(String email);
}
