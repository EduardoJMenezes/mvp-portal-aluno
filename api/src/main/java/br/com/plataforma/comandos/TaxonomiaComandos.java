package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Faixa;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/comandos")
public class TaxonomiaComandos {

    private final CatalogoServico catalogo;
    private final EstruturaServico estrutura;
    private final TaxonomiaServico taxonomia;

    public TaxonomiaComandos(
            CatalogoServico catalogo, EstruturaServico estrutura, TaxonomiaServico taxonomia) {
        this.catalogo = catalogo;
        this.estrutura = estrutura;
        this.taxonomia = taxonomia;
    }

    // --- listar_assuntos -----------------------------------------------------

    @PostMapping("/listar_assuntos")
    @Transactional(readOnly = true)
    public List<TaxonomiaServico.AssuntoNaLista> listarAssuntos() {
        return taxonomia.listarAssuntos();
    }

    // --- cadastrar_assunto ---------------------------------------------------

    public record CadastrarAssunto(
            @NotBlank(message = "é obrigatório, ex.: 'Estequiometria' — sem o K03 na frente") String nome,
            List<String> subassuntos) {}

    public record AssuntoCadastrado(Integer assuntoId, String assunto, List<String> subassuntos) {}

    /** O assunto e, de uma vez, os sub-assuntos dele. Repetir não duplica. */
    @PostMapping("/cadastrar_assunto")
    @Transactional
    public AssuntoCadastrado cadastrarAssunto(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody CadastrarAssunto pedido) {
        var assunto = taxonomia.criarAssunto(ident, pedido.nome());
        var criados = (pedido.subassuntos() == null ? List.<String>of() : pedido.subassuntos()).stream()
                .map(s -> taxonomia.criarSubassunto(ident, assunto, s).getNome())
                .toList();
        return new AssuntoCadastrado(assunto.getId(), assunto.getNome(), criados);
    }

    // --- classificar_videos --------------------------------------------------

    public record ClassificarVideos(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o módulo onde estão os vídeos") String modulo,
            @NotBlank(message = "é obrigatório: o sub-módulo onde estão os vídeos") String submodulo,
            @NotBlank(message = "é obrigatório: um assunto já cadastrado") String assunto,
            String subassunto,
            String itens) {}

    public record VideosClassificados(
            List<String> videosClassificados, String assunto, String subassunto) {}

    /**
     * Etiqueta os vídeos de um sub-módulo com um assunto, em lote.
     *
     * <p>Aceita o mesmo jeito de falar da importação: "Q01-Q03". Vazio, classifica o sub-módulo
     * inteiro.
     */
    @PostMapping("/classificar_videos")
    @Transactional
    public VideosClassificados classificarVideos(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody ClassificarVideos pedido) {
        var turma = catalogo.resolverTurma(pedido.turma());
        var alvos = estrutura.alvos(turma, pedido.modulo(), pedido.submodulo(), null);

        List<Item> escolhidos = estrutura.itensDo(alvos.submodulo());
        if (pedido.itens() != null && !pedido.itens().isBlank()) {
            var numeros = Faixa.interpretar(pedido.itens());
            escolhidos = escolhidos.stream().filter(i -> numeros.contains(Faixa.doNome(i.getNome()))).toList();
        }
        if (escolhidos.isEmpty()) {
            throw new RegraDeNegocio("Nenhum item casou com a faixa informada.");
        }

        var assunto = taxonomia.resolverAssunto(pedido.assunto());
        var subassunto = pedido.subassunto() == null
                ? null
                : taxonomia.resolverSubassunto(assunto, pedido.subassunto());

        for (var item : escolhidos) {
            taxonomia.classificarVideo(ident, item.getVideo(), assunto, subassunto);
        }

        return new VideosClassificados(
                escolhidos.stream().map(Item::getNome).toList(),
                assunto.getNome(),
                subassunto == null ? null : subassunto.getNome());
    }
}
