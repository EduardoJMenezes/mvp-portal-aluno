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

/**
 * Só o que a autorização precisa.
 *
 * <p>{@code senha_hash} fica de fora de propósito: a entidade que a segurança carrega a cada
 * comando não tem como vazar o hash, porque ele não existe nela. Entra quando o login for portado.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Papel papel;

    protected Usuario() {}

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
}
