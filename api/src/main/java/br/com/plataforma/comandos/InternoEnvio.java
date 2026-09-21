package br.com.plataforma.comandos;

import br.com.plataforma.importacoes.ImportacoesServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A página de envio: aqui a credencial é o <b>link</b>, não um operador.
 *
 * <p>Quem abre o link é o professor num navegador, sem sessão — ele recebeu o endereço no chat.
 * Não há {@code X-Operador} para mandar, e inventar um seria pior que não ter: a identidade que
 * fica gravada é a do <b>dono do link</b>, que o próprio registro carrega, no canal DOCX.
 *
 * <p>Por isso estas portas moram em {@code /interno} e não em {@code /comandos}. Elas alteram
 * estado, sim — e quem autoriza é o token do link, que o domínio confere a cada chamada: existe,
 * não expirou (30 minutos), não foi usado e é do formato certo. O token de serviço prova só que o
 * pedido passou pelo adaptador; o resto quem decide é {@code ImportacoesServico.aguardando}.
 */
@RestController
@RequestMapping("/interno")
public class InternoEnvio {

    private final ImportacoesServico importacoes;

    public InternoEnvio(ImportacoesServico importacoes) {
        this.importacoes = importacoes;
    }

    public record SituacaoDoLink(@NotBlank(message = "é obrigatório: o token do link") String token) {}

    /** A página pergunta se o link ainda vale antes de aceitar o arquivo. */
    @PostMapping("/situacao_do_link")
    @Transactional(readOnly = true)
    public ImportacoesServico.SituacaoDoLink situacaoDoLink(@Valid @RequestBody SituacaoDoLink pedido) {
        return importacoes.situacaoDoLink(pedido.token(), Instant.now());
    }

    public record RegistrarDocx(
            @NotBlank(message = "é obrigatório: o token do link") String token,
            @NotNull(message = "é obrigatório: o que o parser leu") ImportacoesServico.DocxLido lido) {}

    /** O adaptador leu o .docx e entrega o resultado. */
    @PostMapping("/registrar_docx")
    @Transactional
    public ImportacoesServico.DocxRegistrado registrarDocx(@Valid @RequestBody RegistrarDocx pedido) {
        return importacoes.registrarDocx(pedido.token(), pedido.lido(), Instant.now());
    }

    public record RegistrarPrints(
            @NotBlank(message = "é obrigatório: o token do link") String token,
            @NotEmpty(message = "é obrigatório: ao menos um print")
            List<ImportacoesServico.PrintRecebido> arquivos) {}

    /** O adaptador recebe os prints da página e os entrega já validados como imagem. */
    @PostMapping("/registrar_prints")
    @Transactional
    public ImportacoesServico.PrintsRegistrados registrarPrints(@Valid @RequestBody RegistrarPrints pedido) {
        return importacoes.registrarPrints(pedido.token(), pedido.arquivos(), Instant.now());
    }
}
