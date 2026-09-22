package br.com.plataforma.contas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * O token Bearer opaco do MCP — o que o Claude Code põe num header fixo.
 *
 * <p>A tabela guarda só o SHA-256: o valor em claro aparece uma vez, quando é emitido, e nunca
 * mais. Por isso quem confere é este lado — o adaptador manda o token como recebeu, e um dump do
 * banco não devolve credencial usável.
 */
@Entity
@Table(name = "api_tokens")
class TokenDeAcesso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private String nome;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "ultimo_uso_em")
    private Instant ultimoUsoEm;

    @Column(nullable = false)
    private boolean revogado;

    protected TokenDeAcesso() {}

    TokenDeAcesso(Usuario usuario, String nome, String tokenHash) {
        this.usuario = usuario;
        this.nome = nome;
        this.tokenHash = tokenHash;
    }

    Integer getId() {
        return id;
    }

    Usuario getUsuario() {
        return usuario;
    }

    String getNome() {
        return nome;
    }

    Instant getCriadoEm() {
        return criadoEm;
    }

    Instant getUltimoUsoEm() {
        return ultimoUsoEm;
    }

    boolean isRevogado() {
        return revogado;
    }

    void usadoAgora(Instant agora) {
        this.ultimoUsoEm = agora;
    }

    void revogar() {
        this.revogado = true;
    }
}
