package br.com.plataforma.contas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ContasServico {

    private final UsuarioRepositorio usuarios;
    private final TokenRepositorio tokens;

    public ContasServico(UsuarioRepositorio usuarios, TokenRepositorio tokens) {
        this.usuarios = usuarios;
        this.tokens = tokens;
    }

    /** Lido a cada comando: o papel vem do banco, nunca de um cabeçalho. */
    public Optional<Usuario> buscar(Integer id) {
        return usuarios.findById(id);
    }

    /**
     * O primeiro e-mail da lista que corresponde a um <b>operador</b> cadastrado.
     *
     * <p>Sem entrada aqui, o login do GitHub não abre sessão nenhuma: o GitHub diz quem entrou, e
     * é esta tabela que diz se essa pessoa opera.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<Usuario> operadorPorEmail(java.util.List<String> emails) {
        for (var email : emails) {
            var achado = usuarios.findFirstByEmailIgnoreCase(email.strip());
            if (achado.isPresent() && achado.get().getPapel().eOperador()) {
                return achado;
            }
        }
        return Optional.empty();
    }

    /**
     * O operador por trás de um token Bearer opaco do MCP.
     *
     * <p>Recebe o token <b>em claro</b> e guarda só o hash: é o que mantém a promessa de que um
     * dump do banco não devolve credencial usável. Aluno com token válido não passa — barrar aqui
     * impede que a credencial dele sequer abra sessão no MCP.
     */
    @org.springframework.transaction.annotation.Transactional
    public Optional<Usuario> operadorPorToken(String token, Instant agora) {
        return tokens.findByTokenHashAndRevogadoFalse(sha256(token))
                .filter(t -> t.getUsuario().getPapel().eOperador())
                .map(t -> {
                    t.usadoAgora(agora);
                    return t.getUsuario();
                });
    }

    /**
     * SHA-256, não bcrypt: o token é aleatório de 256 bits (não há dicionário para atacar) e é
     * conferido a cada chamada de tool, então precisa ser barato e determinístico para virar
     * índice. Mesmo cálculo do {@code hash_token} do Python — mudar aqui invalida todo token vivo.
     */
    private static String sha256(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM sem SHA-256", e);
        }
    }
}
