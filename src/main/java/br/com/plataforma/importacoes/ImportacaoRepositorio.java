package br.com.plataforma.importacoes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ImportacaoRepositorio extends JpaRepository<Importacao, Integer> {

    Optional<Importacao> findFirstByTokenHash(String tokenHash);
}
