package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.importacoes.ImportacoesServico;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O arquivo que chega pelo link de envio.
 *
 * <p>Estes comandos guardam e leem; quem <b>lê o formato</b> — o .docx, o EMF/WMF, o recorte do
 * print — é o adaptador MCP, onde o parser e o Pillow já moram. O link que sai daqui aponta para
 * a página de envio dele.
 */
@RestController
@RequestMapping("/comandos")
public class ImportacoesComandos {

    private final ImportacoesServico importacoes;
    private final CatalogoServico catalogo;
    private final QuestoesServico questoes;

    public ImportacoesComandos(ImportacoesServico importacoes, CatalogoServico catalogo,
            QuestoesServico questoes) {
        this.importacoes = importacoes;
        this.catalogo = catalogo;
        this.questoes = questoes;
    }

    // --- importar_simulado_docx ----------------------------------------------

    public record ImportarSimuladoDocx(
            @NotEmpty(message = "é obrigatória: ao menos uma turma") List<String> turmas,
            String titulo,
            String abreEm,
            String fechaEm,
            Integer duracaoMinutos,
            String pastaResolucao) {}

    /**
     * O link de envio do .docx.
     *
     * <p>Turmas, agenda e tempo de prova são conferidos <b>já aqui</b> — um erro de digitação
     * aparece no chat antes de o professor enviar o arquivo.
     */
    @PostMapping("/importar_simulado_docx")
    @Transactional
    public ImportacoesServico.LinkDeEnvio importarSimuladoDocx(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody ImportarSimuladoDocx pedido) {
        var turmas = catalogo.resolverTurmas(pedido.turmas()).stream()
                .map(br.com.plataforma.catalogo.Turma::getNome).toList();
        SimuladosServico.lerDataHora(pedido.abreEm());
        SimuladosServico.lerDataHora(pedido.fechaEm());
        if (pedido.duracaoMinutos() != null && pedido.duracaoMinutos() <= 0) {
            throw new RegraDeNegocio("O tempo de prova precisa ser maior que zero.");
        }

        var parametros = new LinkedHashMap<String, Object>();
        parametros.put("formato", "DOCX");
        parametros.put("turmas", turmas);
        parametros.put("titulo", pedido.titulo());
        parametros.put("abre_em", pedido.abreEm());
        parametros.put("fecha_em", pedido.fechaEm());
        parametros.put("duracao_minutos", pedido.duracaoMinutos());
        parametros.put("pasta_resolucao", pedido.pastaResolucao());

        return importacoes.criarLink(ident, parametros, Instant.now());
    }

    // --- importar_prints -----------------------------------------------------

    /** O link para prints. Turma e agenda ficam para quando o Claude criar as questões. */
    @PostMapping("/importar_prints")
    @Transactional
    public ImportacoesServico.LinkDeEnvio importarPrints(@AuthenticationPrincipal Identidade ident) {
        return importacoes.criarLink(ident, Map.of("formato", "PRINTS"), Instant.now());
    }

    // --- revisar_importacao --------------------------------------------------

    public record RevisarImportacao(
            @NotNull(message = "é obrigatório: o id da importação") Integer importacao,
            Integer de,
            Integer ate) {}

    @PostMapping("/revisar_importacao")
    @Transactional(readOnly = true)
    public ImportacoesServico.Revisao revisarImportacao(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody RevisarImportacao pedido) {
        return importacoes.revisar(ident, pedido.importacao(),
                pedido.de() == null ? 1 : pedido.de(), pedido.ate(),
                questoes::etiquetasDa, Instant.now());
    }

    // --- completar_questao_importada -----------------------------------------

    public record CompletarQuestaoImportada(
            @NotNull(message = "é obrigatório: o id da importação") Integer importacao,
            @NotNull(message = "é obrigatório: o número da questão no documento") Integer numero,
            @NotBlank(message = "é obrigatória: a faixa de blocos do enunciado, ex.: '12-18'")
            String enunciado,
            @NotNull(message = "é obrigatória: a faixa de blocos das alternativas")
            Object alternativas,
            @NotBlank(message = "é obrigatório: a letra correta") String gabarito,
            String resolucao) {}

    /**
     * Monta, a partir dos blocos do documento, a questão que as regras não fecharam.
     *
     * <p>O texto sai do documento, não de uma redigitação — e a questão entra no rascunho na
     * posição do seu número.
     */
    @PostMapping("/completar_questao_importada")
    @Transactional
    public ImportacoesServico.QuestaoCompletada completarQuestaoImportada(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CompletarQuestaoImportada pedido) {
        return importacoes.completarQuestao(ident, pedido.importacao(), pedido.numero(),
                pedido.enunciado(), pedido.alternativas(), pedido.gabarito(), pedido.resolucao(),
                Instant.now());
    }

    // --- o que o adaptador precisa para o Pillow ------------------------------

    public record PrintsDaImportacao(
            @NotNull(message = "é obrigatório: o id da importação") Integer importacao) {}

    /** Quais figuras são os prints, na ordem — o adaptador redimensiona com Pillow. */
    @PostMapping("/prints_da_importacao")
    @Transactional(readOnly = true)
    public ImportacoesServico.PrintsDaImportacao printsDaImportacao(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody PrintsDaImportacao pedido) {
        return importacoes.printsDa(ident, pedido.importacao());
    }

    public record BytesDaFigura(@NotNull Integer figura) {}

    public record FiguraEmBase64(Integer figuraId, String tipo, String parte, String conteudoBase64) {}

    /** Os bytes de uma figura, para o adaptador redimensionar ou recortar. */
    @PostMapping("/bytes_da_figura")
    @Transactional(readOnly = true)
    public FiguraEmBase64 bytesDaFigura(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody BytesDaFigura pedido) {
        ident.exigirOperador();
        var onde = importacoes.ondeAparece(pedido.figura());
        return new FiguraEmBase64(pedido.figura(), onde.tipo(), onde.parte(),
                java.util.Base64.getEncoder().encodeToString(importacoes.bytesDaFigura(pedido.figura())));
    }

    public record GuardarFigura(
            @NotBlank(message = "é obrigatório: o conteúdo em base64") String conteudoBase64,
            @NotBlank(message = "é obrigatório: o tipo, ex.: image/png") String tipo,
            String nome) {}

    public record FiguraGuardada(Integer figuraId) {}

    /** O adaptador recorta com Pillow e manda o resultado; guardar é daqui. */
    @PostMapping("/guardar_figura")
    @Transactional
    public FiguraGuardada guardarFigura(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody GuardarFigura pedido) {
        ident.exigirOperador();
        var bytes = java.util.Base64.getDecoder().decode(pedido.conteudoBase64());
        return new FiguraGuardada(importacoes.guardarFigura(bytes, pedido.tipo(), pedido.nome()));
    }
}
