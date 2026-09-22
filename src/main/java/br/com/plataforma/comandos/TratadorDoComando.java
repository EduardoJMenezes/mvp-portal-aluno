package br.com.plataforma.comandos;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.comum.AprovacaoNecessaria;
import br.com.plataforma.comum.ErroDominio;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erro de domínio em resposta HTTP, para quem chama os comandos.
 *
 * <p>Do outro lado está um modelo, não um aluno: a mensagem vai inteira, porque é ela que ele lê
 * para se corrigir. O tratador do portal, quando existir, é outro — e esconde.
 */
@RestControllerAdvice(basePackageClasses = TratadorDoComando.class)
class TratadorDoComando {

    @ExceptionHandler(ErroDominio.class)
    ProblemDetail dominio(ErroDominio e) {
        // Sem default: ErroDominio é sealed, e um tipo novo não compila até ganhar status aqui.
        var status = switch (e) {
            case NaoEncontrado _ -> HttpStatus.NOT_FOUND;
            case NaoAutorizado _ -> HttpStatus.FORBIDDEN;
            case RegraDeNegocio _ -> HttpStatus.BAD_REQUEST;
            case br.com.plataforma.comum.CredenciaisInvalidas _ -> HttpStatus.UNAUTHORIZED;
            case br.com.plataforma.comum.MuitasTentativas _ -> HttpStatus.TOO_MANY_REQUESTS;
        };
        var problema = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        if (e instanceof AprovacaoNecessaria) {
            // O adaptador MCP reconhece por aqui que deve pedir a confirmação ao professor,
            // em vez de só repassar o erro. O status continua 400: a forma do pedido está certa.
            problema.setType(java.net.URI.create(TIPO_APROVACAO_NECESSARIA));
        }
        return problema;
    }

    public static final String TIPO_APROVACAO_NECESSARIA = "urn:plataforma:aprovacao-necessaria";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalido(MethodArgumentNotValidException e) {
        var campos = e.getBindingResult().getFieldErrors().stream()
                .map(f -> "'%s' %s".formatted(f.getField(), f.getDefaultMessage()))
                .collect(joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Pedido incompleto: " + campos + ".");
    }
}
