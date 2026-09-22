package br.com.plataforma.comandos;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.questoes.QuestoesServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comandos do acervo de questões de simulado. */
@RestController
@RequestMapping("/comandos")
public class QuestoesComandos {

    private final QuestoesServico questoes;

    public QuestoesComandos(QuestoesServico questoes) {
        this.questoes = questoes;
    }

    // --- buscar_questoes -----------------------------------------------------

    public record BuscarQuestoes(
            String assunto,
            String status,
            String dificuldade,
            String busca,
            @Min(value = 1, message = "vai de 1 a 200") @Max(value = 200, message = "vai de 1 a 200")
            Integer limite,
            Integer deslocamento) {}

    @PostMapping("/buscar_questoes")
    @Transactional(readOnly = true)
    public List<QuestoesServico.QuestaoDescrita> buscarQuestoes(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody(required = false) BuscarQuestoes pedido) {
        var p = pedido == null ? new BuscarQuestoes(null, null, null, null, null, null) : pedido;
        return questoes.buscar(ident, p.assunto(), p.status(), p.dificuldade(), p.busca(),
                p.limite() == null ? 50 : p.limite(),
                p.deslocamento() == null ? 0 : p.deslocamento());
    }

    // --- detalhar_questao ----------------------------------------------------

    public record DetalharQuestao(
            @NotBlank(message = "é obrigatório: o id da questão") String questao) {}

    @PostMapping("/detalhar_questao")
    @Transactional(readOnly = true)
    public QuestoesServico.QuestaoDetalhada detalharQuestao(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharQuestao pedido) {
        return questoes.detalhar(ident, pedido.questao(), Instant.now());
    }

    // --- editar_questao ------------------------------------------------------

    public record EditarQuestao(
            @NotBlank(message = "é obrigatório: o id da questão") String questao,
            String enunciado,
            Map<String, String> alternativas,
            String gabarito,
            String dificuldade,
            Boolean imagemPendente,
            String assunto,
            String subassunto,
            /** O vídeo inteiro como o adaptador o leu do Vimeo — o embed_url carrega o hash de
             * privacidade, e sem ele um vídeo unlisted não toca. `vimeo_id` vazio tira a
             * resolução. */
            QuestoesServico.DadosDoVideo resolucao,
            String resolucaoComentada) {}

    /**
     * Altera a questão direto — o preview é no chat, antes da chamada.
     *
     * <p>{@code alternativas} pode vir parcial: só as letras informadas mudam. {@code assunto}
     * troca a classificação inteira; vazio tira a classificação. Em {@code resolucao}, um
     * {@code vimeo_id} vazio tira a resolução em vídeo.
     */
    @PostMapping("/editar_questao")
    @Transactional
    public QuestoesServico.QuestaoDetalhada editarQuestao(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody EditarQuestao pedido) {
        var agora = Instant.now();
        var alterada = questoes.editar(ident, pedido.questao(),
                new QuestoesServico.Alteracao(pedido.enunciado(), pedido.alternativas(),
                        pedido.gabarito(), pedido.dificuldade(), pedido.imagemPendente(),
                        pedido.assunto(), pedido.subassunto(), pedido.resolucao(),
                        pedido.resolucaoComentada()),
                agora);

        return questoes.detalhar(ident, String.valueOf(alterada.getId()), agora);
    }

    // --- a figura recortada do print ------------------------------------------

    public record AnexarFigura(
            @NotBlank(message = "é obrigatório: o id da questão") String questao,
            @NotBlank(message = "é obrigatório: o conteúdo em base64") String conteudoBase64,
            String nome,
            String parte,
            String alternativa) {}

    /** O adaptador recorta com Pillow; pôr no texto e tirar a pendência é daqui. */
    @PostMapping("/anexar_figura")
    @Transactional
    public QuestoesServico.FiguraNaQuestaoResposta anexarFigura(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody AnexarFigura pedido) {
        return questoes.anexarFigura(ident, pedido.questao(),
                java.util.Base64.getDecoder().decode(pedido.conteudoBase64()), pedido.nome(),
                parteDe(pedido.parte()), pedido.alternativa(), Instant.now());
    }

    public record TrocarFigura(
            @NotNull(message = "é obrigatório: o id da figura") Integer figura,
            @NotBlank(message = "é obrigatório: o id da questão") String questao,
            @NotBlank(message = "é obrigatório: o conteúdo em base64") String conteudoBase64) {}

    @PostMapping("/trocar_figura")
    @Transactional
    public QuestoesServico.FiguraNaQuestaoResposta trocarFigura(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody TrocarFigura pedido) {
        return questoes.trocarFigura(ident, pedido.figura(), pedido.questao(),
                java.util.Base64.getDecoder().decode(pedido.conteudoBase64()), Instant.now());
    }

    private static br.com.plataforma.questoes.ParteDaQuestao parteDe(String texto) {
        if (texto == null || texto.isBlank()) {
            return br.com.plataforma.questoes.ParteDaQuestao.ENUNCIADO;
        }
        try {
            return br.com.plataforma.questoes.ParteDaQuestao.valueOf(
                    texto.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new br.com.plataforma.comum.RegraDeNegocio(
                    "Parte '%s' inválida. Use ENUNCIADO, ALTERNATIVA, RESOLUCAO.".formatted(texto));
        }
    }

    // --- remover_questao -----------------------------------------------------

    public record RemoverQuestao(
            @NotBlank(message = "é obrigatório: o id da questão") String questao) {}

    @PostMapping("/remover_questao")
    @Transactional
    public QuestoesServico.QuestaoRemovida removerQuestao(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody RemoverQuestao pedido) {
        return questoes.remover(ident, pedido.questao(), Instant.now());
    }
}
