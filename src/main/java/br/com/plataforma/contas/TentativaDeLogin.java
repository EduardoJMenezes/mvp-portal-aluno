package br.com.plataforma.contas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Falha de login recente, para a trava valer entre processos.
 *
 * <p>O contador mora no Postgres, não na memória: com vários processos servindo o portal, cada um
 * contaria sozinho e o limite de cinco viraria vinte. A chave é o e-mail tentado ou o IP.
 */
@Entity
@Table(name = "login_attempts")
class TentativaDeLogin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String chave;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected TentativaDeLogin() {}

    TentativaDeLogin(String chave, Instant criadoEm) {
        this.chave = chave;
        this.criadoEm = criadoEm;
    }
}
