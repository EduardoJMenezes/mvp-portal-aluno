package br.com.plataforma.portal;

import br.com.plataforma.comum.AprovacaoNecessaria;
import br.com.plataforma.comum.CredenciaisInvalidas;
import br.com.plataforma.comum.ErroDominio;
import br.com.plataforma.comum.MuitasTentativas;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.ServicoExterno;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Erro em resposta HTTP para quem usa o portal: sempre {@code {"detail": "..."}}, que é o que a
 * tela mostra. Diferente do tratador dos comandos, aqui o inesperado <b>esconde</b>: o aluno vê
 * uma frase, e o traceback fica no log.
 */
@RestControllerAdvice(basePackageClasses = TratadorDoPortal.class)
class TratadorDoPortal {

    private static final Logger log = LoggerFactory.getLogger(TratadorDoPortal.class);

    static final String MENSAGEM_BANCO_FORA = "Banco de dados indisponível no momento. Tente novamente em instantes.";
    static final String MENSAGEM_ERRO_INTERNO = "Erro interno no servidor. Tente novamente em instantes.";

    private static ResponseEntity<Map<String, String>> detalhe(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("detail", mensagem));
    }

    @ExceptionHandler(ErroDominio.class)
    ResponseEntity<Map<String, String>> dominio(ErroDominio e) {
        var status = switch (e) {
            case NaoEncontrado _ -> HttpStatus.NOT_FOUND;
            case NaoAutorizado _ -> HttpStatus.FORBIDDEN;
            case CredenciaisInvalidas _ -> HttpStatus.UNAUTHORIZED;
            case MuitasTentativas _ -> HttpStatus.TOO_MANY_REQUESTS;
            case RegraDeNegocio r -> r instanceof AprovacaoNecessaria ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        };
        var resposta = ResponseEntity.status(status);
        if (e instanceof MuitasTentativas m) {
            resposta = resposta.header("Retry-After", String.valueOf(m.getSegundos()));
        }
        return resposta.body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(ServicoExterno.class)
    ResponseEntity<Map<String, String>> externo(ServicoExterno e) {
        log.warn("serviço externo falhou: {}", e.getMessage());
        return detalhe(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalido(MethodArgumentNotValidException e) {
        var campos = e.getBindingResult().getFieldErrors().stream()
                .map(f -> "'%s' %s".formatted(f.getField(), f.getDefaultMessage()))
                .collect(java.util.stream.Collectors.joining("; "));
        return detalhe(HttpStatus.UNPROCESSABLE_CONTENT, "Pedido inválido: " + campos + ".");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<Map<String, String>> malFormado(Exception e) {
        return detalhe(HttpStatus.UNPROCESSABLE_CONTENT, "Pedido inválido: " + resumo(e.getMessage()) + ".");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> grandeDemais(MaxUploadSizeExceededException e) {
        return detalhe(HttpStatus.BAD_REQUEST, "O arquivo passa do limite de envio.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> naoEncontrado(NoResourceFoundException e) {
        return detalhe(HttpStatus.NOT_FOUND, "Não encontrado.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> metodo(HttpRequestMethodNotSupportedException e) {
        return detalhe(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<Map<String, String>> bancoFora(Exception e) {
        // Conexão recusada, host errado, senha inválida: problema de infraestrutura, não do usuário.
        log.error("banco indisponível: {}", e.getMessage());
        return detalhe(HttpStatus.SERVICE_UNAVAILABLE, MENSAGEM_BANCO_FORA);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> inesperado(Exception e) {
        log.error("erro inesperado no portal", e);
        return detalhe(HttpStatus.INTERNAL_SERVER_ERROR, MENSAGEM_ERRO_INTERNO);
    }

    private static String resumo(String mensagem) {
        if (mensagem == null) {
            return "corpo mal formado";
        }
        var corte = mensagem.indexOf(':');
        return corte > 0 && corte < 80 ? mensagem.substring(0, corte) : mensagem.substring(0, Math.min(120, mensagem.length()));
    }
}
