package br.com.plataforma.comandos;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.rascunhos.RascunhosServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O que a importação do Vimeo grava aqui.
 *
 * <p>Falar com a API do Vimeo — listar pastas, listar vídeos, ler o número do título, casar as
 * faixas com os destinos — é do adaptador MCP, que é quem tem o cliente e o token. O que chega
 * aqui já é a lista pronta: este lado só grava, e grava em rascunho.
 */
@RestController
@RequestMapping("/comandos")
public class AcervoComandos {

    private final CatalogoServico catalogo;
    private final RascunhosServico rascunhos;
    private final AcervoServico acervo;

    public AcervoComandos(CatalogoServico catalogo, RascunhosServico rascunhos,
            AcervoServico acervo) {
        this.catalogo = catalogo;
        this.rascunhos = rascunhos;
        this.acervo = acervo;
    }

    public record VideosNoAcervo(List<String> vimeoIds) {}

    public record JaExistem(List<String> vimeoIds) {}

    /** O que a simulação da importação pergunta antes de propor: o que já está aqui. */
    @PostMapping("/videos_no_acervo")
    @Transactional(readOnly = true)
    public JaExistem videosNoAcervo(
            @AuthenticationPrincipal Identidade ident, @RequestBody VideosNoAcervo pedido) {
        ident.exigirOperador();
        return new JaExistem(acervo.quaisJaExistem(
                pedido.vimeoIds() == null ? List.of() : pedido.vimeoIds()));
    }

    public record ImportarVideosComoItens(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o módulo de destino") String modulo,
            @NotBlank(message = "é obrigatório: o sub-módulo de destino") String submodulo,
            @NotEmpty(message = "é obrigatória: ao menos um vídeo")
            List<RascunhosServico.VideoParaImportar> videos) {}

    public record ItensImportados(
            RascunhosServico.RascunhoDetalhado rascunho, List<String> erros, String aviso) {}

    @PostMapping("/importar_videos_como_itens")
    @Transactional
    public ItensImportados importarVideosComoItens(
            @AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody ImportarVideosComoItens pedido) {
        var turma = catalogo.resolverTurma(pedido.turma());
        var resultado = rascunhos.importarVideosComoItens(ident, turma, pedido.modulo(),
                pedido.submodulo(), pedido.videos());

        return new ItensImportados(
                rascunhos.detalhar(ident, resultado.rascunho().getId(), Instant.now()),
                resultado.erros(),
                "Nada foi publicado. O rascunho precisa da aprovação do professor.");
    }
}
