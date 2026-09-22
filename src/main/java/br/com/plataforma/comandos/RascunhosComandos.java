package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.rascunhos.PublicacaoServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import br.com.plataforma.simulados.SimuladosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

/** Propor, ver e publicar. Publicar exige aprovação humana gravada no banco. */
@RestController
@RequestMapping("/comandos")
public class RascunhosComandos {

    private final RascunhosServico rascunhos;
    private final PublicacaoServico publicacao;
    private final CatalogoServico catalogo;
    private final EntradasDaProva entradas;

    public RascunhosComandos(RascunhosServico rascunhos, PublicacaoServico publicacao,
            CatalogoServico catalogo, EntradasDaProva entradas) {
        this.rascunhos = rascunhos;
        this.publicacao = publicacao;
        this.catalogo = catalogo;
        this.entradas = entradas;
    }

    // --- criar_questao_rascunho ----------------------------------------------

    public record CriarQuestaoRascunho(
            @NotBlank(message = "é obrigatório (Markdown e LaTeX)") String enunciado,
            @NotNull(message = "são obrigatórias: A a E") Map<String, String> alternativas,
            @NotBlank(message = "é obrigatório: a letra correta") String gabarito,
            String assunto,
            String subassunto,
            String dificuldade,
            QuestoesServico.DadosDoVideo resolucao,
            Boolean imagemPendente,
            String resolucaoComentada) {}

    @PostMapping("/criar_questao_rascunho")
    @Transactional
    public RascunhosServico.RascunhoDetalhado criarQuestaoRascunho(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CriarQuestaoRascunho pedido) {
        var r = rascunhos.criarQuestaoRascunho(ident, new QuestoesServico.DadosDaQuestaoNova(
                pedido.enunciado(), pedido.alternativas(), pedido.gabarito(), pedido.assunto(),
                pedido.subassunto(), pedido.dificuldade(), pedido.resolucao(),
                pedido.imagemPendente(), pedido.resolucaoComentada(), null));
        return rascunhos.detalhar(ident, r.getId(), Instant.now());
    }

    // --- criar_simulado_rascunho ---------------------------------------------

    public record CriarSimuladoRascunho(
            @NotEmpty(message = "é obrigatória: ao menos uma turma") List<String> turmas,
            @NotBlank(message = "é obrigatório: o título da prova") String titulo,
            @NotEmpty(message = "é obrigatória: ao menos uma questão") List<Object> questoes,
            String abreEm,
            String fechaEm,
            Integer duracaoMinutos,
            Map<String, Object> resolucoes) {}

    /**
     * Monta o simulado e as questões novas dele num rascunho só.
     *
     * <p>Agenda e tempo de prova podem ficar para {@code editar_simulado}: o rascunho nasce sem
     * eles, mas não publica sem eles.
     */
    @PostMapping("/criar_simulado_rascunho")
    @Transactional
    public RascunhosServico.RascunhoDetalhado criarSimuladoRascunho(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CriarSimuladoRascunho pedido) {
        var r = rascunhos.criarSimuladoRascunho(ident, pedido.titulo(),
                catalogo.resolverTurmas(pedido.turmas()),
                entradas.traduzir(pedido.questoes()),
                SimuladosServico.lerDataHora(pedido.abreEm()),
                SimuladosServico.lerDataHora(pedido.fechaEm()),
                pedido.duracaoMinutos(),
                entradas.resolucoes(pedido.resolucoes()));
        return rascunhos.detalhar(ident, r.getId(), Instant.now());
    }

    // --- listar_rascunhos ----------------------------------------------------

    public record ListarRascunhos(String status) {}

    @PostMapping("/listar_rascunhos")
    @Transactional(readOnly = true)
    public List<RascunhosServico.ResumoDoRascunho> listarRascunhos(
            @AuthenticationPrincipal Identidade ident,
            @RequestBody(required = false) ListarRascunhos pedido) {
        return rascunhos.listar(ident, pedido == null ? null : pedido.status());
    }

    // --- detalhar_rascunho ---------------------------------------------------

    public record DetalharRascunho(
            @NotNull(message = "é obrigatório: o id do rascunho") Integer rascunho) {}

    @PostMapping("/detalhar_rascunho")
    @Transactional(readOnly = true)
    public RascunhosServico.RascunhoDetalhado detalharRascunho(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharRascunho pedido) {
        return rascunhos.detalhar(ident, pedido.rascunho(), Instant.now());
    }

    // --- resumo_para_confirmacao ---------------------------------------------

    public record TextoDaConfirmacao(Integer rascunhoId, String texto) {}

    /** O que o professor lê antes de decidir. O modelo não redige isto. */
    @PostMapping("/resumo_para_confirmacao")
    @Transactional(readOnly = true)
    public TextoDaConfirmacao resumoParaConfirmacao(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody DetalharRascunho pedido) {
        return new TextoDaConfirmacao(pedido.rascunho(),
                publicacao.resumoParaConfirmacao(ident, pedido.rascunho(), Instant.now()));
    }

    // --- publicar_rascunho ---------------------------------------------------

    public record PublicarRascunho(
            @NotNull(message = "é obrigatório: o id do rascunho") Integer rascunho,
            List<Integer> itensIds,
            /** Só o servidor MCP manda isto, depois de o professor aceitar a confirmação. */
            Boolean confirmadoPeloProfessor) {}

    /**
     * Publica um rascunho já aprovado por um humano.
     *
     * <p>Sem aprovação, devolve 400 com {@code type} de aprovação necessária — é esse o gatilho
     * para o adaptador pedir a confirmação ao professor e chamar de novo.
     */
    @PostMapping("/publicar_rascunho")
    @Transactional
    public PublicacaoServico.Publicacao publicarRascunho(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody PublicarRascunho pedido) {
        if (Boolean.TRUE.equals(pedido.confirmadoPeloProfessor())) {
            publicacao.registrarConfirmacaoDoClienteMcp(ident, pedido.rascunho());
        }
        return publicacao.publicar(ident, pedido.rascunho(), pedido.itensIds(), Instant.now());
    }
}
