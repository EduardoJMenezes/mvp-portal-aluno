package br.com.plataforma.comandos;

import br.com.plataforma.contas.ContasServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O que o adaptador precisa <b>antes</b> de ter um operador para se identificar.
 *
 * <p>É o problema do ovo e da galinha da autenticação: para pôr o id do operador no cabeçalho, o
 * adaptador primeiro precisa saber quem é o operador — e isso é uma consulta ao banco, que ele
 * não faz mais.
 *
 * <p>Por isso esta porta é autenticada <b>só pelo token de serviço</b>, e por isso ela faz o
 * mínimo: dada uma credencial que o adaptador já tem em mãos — o login do GitHub ou o token
 * Bearer —, responde quem é o operador correspondente, e nada além. Não lista usuários, não
 * aceita filtro aberto, não devolve senha nem hash — quem tem o token de serviço descobre apenas
 * o que já sabia perguntar.
 */
@RestController
@RequestMapping("/interno")
public class InternoComandos {

    private final ContasServico contas;

    public InternoComandos(ContasServico contas) {
        this.contas = contas;
    }

    public record QuemE(
            @NotEmpty(message = "é obrigatório: ao menos um identificador")
            List<String> identificadores) {}

    public record TokenDoMcp(
            @jakarta.validation.constraints.NotBlank(message = "é obrigatório: o token") String token) {}

    public record Operador(
            Integer usuarioId, String nome, String email, br.com.plataforma.comum.Papel papel) {}

    /**
     * Traduz quem logou no GitHub no operador cadastrado aqui.
     *
     * <p>Devolve vazio quando não corresponde a nenhum — e é assim que o adaptador recusa a
     * sessão antes mesmo de mostrar o catálogo de ferramentas.
     */
    @PostMapping("/operador")
    @Transactional(readOnly = true)
    public Operador operador(@Valid @RequestBody QuemE pedido) {
        return contas.operadorPorEmail(pedido.identificadores())
                .map(InternoComandos::resposta)
                .orElse(null);
    }

    /**
     * A outra credencial: o token Bearer opaco, que o Claude Code põe num header fixo.
     *
     * <p>O token viaja em claro até aqui de propósito. O banco guarda só o SHA-256, e quem calcula
     * é este lado — se o adaptador mandasse o hash pronto, um dump do banco voltaria a ser uma
     * credencial. Também é aqui que {@code ultimo_uso_em} é carimbado, por isso não é read-only.
     */
    @PostMapping("/token")
    @Transactional
    public Operador token(@Valid @RequestBody TokenDoMcp pedido) {
        return contas.operadorPorToken(pedido.token(), java.time.Instant.now())
                .map(InternoComandos::resposta)
                .orElse(null);
    }

    private static Operador resposta(br.com.plataforma.contas.Usuario u) {
        return new Operador(u.getId(), u.getNome(), u.getEmail(), u.getPapel());
    }
}
