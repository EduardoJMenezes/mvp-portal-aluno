package br.com.plataforma.contas;

import br.com.plataforma.comum.Papel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Quem usa a plataforma.
 *
 * <p>O hash da senha fica sem getter público de propósito: só {@link ContasServico} o confere, e
 * nenhum record de resposta tem como carregá-lo por engano.
 */
@Entity
@Table(name = "users")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private String email;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Papel papel;

    /**
     * Senha que outra pessoa definiu (o professor cadastrando ou redefinindo): até o dono trocar,
     * a sessão só serve para trocar a senha.
     */
    @Column(name = "senha_temporaria", nullable = false)
    private boolean senhaTemporaria;

    /** Sessão emitida antes deste instante deixa de valer: trocar a senha derruba as antigas. */
    @Column(name = "senha_alterada_em")
    private Instant senhaAlteradaEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    protected Usuario() {}

    Usuario(String nome, String email, String senhaHash, Papel papel, boolean senhaTemporaria) {
        this.nome = nome;
        this.email = email;
        this.senhaHash = senhaHash;
        this.papel = papel;
        this.senhaTemporaria = senhaTemporaria;
    }

    public Integer getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public Papel getPapel() {
        return papel;
    }

    public boolean isSenhaTemporaria() {
        return senhaTemporaria;
    }

    public Instant getSenhaAlteradaEm() {
        return senhaAlteradaEm;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    String getSenhaHash() {
        return senhaHash;
    }

    void definirSenha(String senhaHash, boolean temporaria, Instant agora) {
        this.senhaHash = senhaHash;
        this.senhaTemporaria = temporaria;
        this.senhaAlteradaEm = agora;
    }
}
