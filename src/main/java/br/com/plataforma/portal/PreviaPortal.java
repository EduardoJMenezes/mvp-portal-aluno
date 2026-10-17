package br.com.plataforma.portal;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A prévia do aluno: o professor vê o portal como um aluno de uma turma, enquanto monta o curso,
 * sem conta de aluno.
 *
 * <p>A turma escolhida vai num cookie próprio, e é o {@link FiltroDaSessao} que o lê: nas rotas
 * do aluno ({@code /api/aluno/**}) a identidade do operador vira a de um aluno daquela turma
 * ({@link Identidade#emPreviaDa}), e os serviços filtram como filtram para qualquer aluno. Cookie, e
 * não cabeçalho, porque a figura da questão, a foto do módulo e o PDF chegam por {@code <img>} e
 * pelo leitor, que não mandam cabeçalho nenhum. O valor não precisa de assinatura: só vale para
 * ADMIN e GERENCIADOR, que já veem todas as turmas.
 */
@RestController
@RequestMapping("/api/admin/previa")
public class PreviaPortal {

    public static final String COOKIE = "previa";

    public record PreviaIn(@NotBlank @Size(max = 120) String turma) {}

    public record Previa(Integer turmaId, String turma) {}

    private final CatalogoServico catalogo;
    private final ConfigDoPortal config;

    public PreviaPortal(CatalogoServico catalogo, ConfigDoPortal config) {
        this.catalogo = catalogo;
        this.config = config;
    }

    @PostMapping
    @Transactional(readOnly = true)
    public Previa entrar(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody PreviaIn dados,
            HttpServletResponse resposta) {
        ident.exigirOperador();
        var turma = catalogo.resolverTurma(dados.turma());
        resposta.addHeader(HttpHeaders.SET_COOKIE, cookie(String.valueOf(turma.getId()), -1).toString());
        return new Previa(turma.getId(), turma.getNome());
    }

    @DeleteMapping
    public Previa sair(HttpServletResponse resposta) {
        apagar(resposta, config);
        return new Previa(null, null);
    }

    /** Sair da conta, ou entrar com outra, encerra a prévia: ela não passa de uma sessão para outra. */
    static void apagar(HttpServletResponse resposta, ConfigDoPortal config) {
        resposta.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0, config).toString());
    }

    private ResponseCookie cookie(String valor, long maxAge) {
        return cookie(valor, maxAge, config);
    }

    private static ResponseCookie cookie(String valor, long maxAge, ConfigDoPortal config) {
        return ResponseCookie.from(COOKIE, valor)
                .httpOnly(true).secure(config.cookieSeguro()).sameSite("Strict").path("/api").maxAge(maxAge)
                .build();
    }

    /** A turma do cookie, se houver um válido. Quem decide se ele vale para a pessoa é o filtro. */
    static Optional<Integer> turmaDoCookie(HttpServletRequest pedido) {
        if (pedido.getCookies() == null) {
            return Optional.empty();
        }
        for (var c : pedido.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().matches("\\d{1,9}")) {
                return Optional.of(Integer.parseInt(c.getValue()));
            }
        }
        return Optional.empty();
    }

    /** A prévia ligada para esta pessoa, para a tela mostrar a faixa: só operador, e só turma viva. */
    @Transactional(readOnly = true)
    public Optional<Previa> ativa(Identidade ident, HttpServletRequest pedido) {
        if (!ident.eOperador()) {
            return Optional.empty();
        }
        return turmaDoCookie(pedido).flatMap(catalogo::turma).map(t -> new Previa(t.getId(), t.getNome()));
    }
}
