package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.menu.MenuServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O menu do aluno pelo chat. Definir e copiar mudam o menu na hora: o preview (antes e depois) é
 * no chat, antes da chamada, como nas outras tools que alteram direto.
 */
@RestController
@RequestMapping("/comandos")
public class MenuComandos {

    private final MenuServico menu;
    private final CatalogoServico catalogo;

    public MenuComandos(MenuServico menu, CatalogoServico catalogo) {
        this.menu = menu;
        this.catalogo = catalogo;
    }

    public record DaTurma(@NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma) {}

    @PostMapping("/listar_menu")
    public MenuServico.Menu listarMenu(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody DaTurma pedido) {
        return menu.daTurma(ident, catalogo.resolverTurma(pedido.turma()));
    }

    public record Botao(String rotulo, String funcionalidade, String categoria) {}

    public record DefinirMenu(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotNull(message = "é obrigatório: a lista de botões, na ordem; vazia volta ao menu de sempre")
            List<Botao> botoes) {}

    @PostMapping("/definir_menu")
    public MenuServico.Menu definirMenu(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody DefinirMenu pedido) {
        return menu.definir(ident, catalogo.resolverTurma(pedido.turma()), pedido.botoes().stream()
                .map(b -> MenuServico.Botao.de(b.rotulo(), b.funcionalidade(), b.categoria())).toList());
    }

    public record CopiarMenu(
            @NotBlank(message = "é obrigatória: a turma de onde o menu vem") String de,
            @NotBlank(message = "é obrigatória: a turma que recebe o menu") String para) {}

    @PostMapping("/copiar_menu")
    public MenuServico.Menu copiarMenu(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CopiarMenu pedido) {
        return menu.copiar(ident, catalogo.resolverTurma(pedido.de()), catalogo.resolverTurma(pedido.para()));
    }
}
