package br.com.plataforma.contas;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Sem {@code public}: quem confere token é o {@link ContasServico}, e só ele. */
interface TokenRepositorio extends JpaRepository<TokenDeAcesso, Integer> {

    Optional<TokenDeAcesso> findByTokenHashAndRevogadoFalse(String tokenHash);
}
