package br.com.plataforma.contas;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TentativaDeLoginRepositorio extends JpaRepository<TentativaDeLogin, Integer> {

    /** Quantas falhas a chave tem na janela, e a mais antiga delas: [count, min(criadoEm)]. */
    @Query("select count(t), min(t.criadoEm) from TentativaDeLogin t"
            + " where t.chave = :chave and t.criadoEm >= :desde")
    List<Object[]> contagem(String chave, Instant desde);

    @Modifying
    @Query("delete from TentativaDeLogin t where t.criadoEm < :antes")
    void apagarAntesDe(Instant antes);

    @Modifying
    @Query("delete from TentativaDeLogin t where t.chave = :chave")
    void apagarChave(String chave);
}
