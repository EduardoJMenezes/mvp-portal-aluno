package br.com.plataforma.portal;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.menu.MenuServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** O menu do aluno: o aluno lê o dele (com o aviso de ao vivo), o professor monta o de cada turma. */
@RestController
public class MenuPortal {

    private final MenuServico menu;
    private final CatalogoServico catalogo;

    public MenuPortal(MenuServico menu, CatalogoServico catalogo) {
        this.menu = menu;
        this.catalogo = catalogo;
    }

    public record BotaoIn(String rotulo, String funcionalidade, String categoria) {}

    public record MenuIn(@NotNull List<BotaoIn> botoes) {}

    public record CopiaIn(@NotBlank String de) {}

    @GetMapping("/api/aluno/menu")
    public List<MenuServico.BotaoDoAluno> doAluno(@AuthenticationPrincipal Identidade ident) {
        return menu.doAluno(ident, Instant.now());
    }

    @GetMapping("/api/admin/turmas/{turma}/menu")
    public MenuServico.Menu daTurma(@AuthenticationPrincipal Identidade ident, @PathVariable String turma) {
        return menu.daTurma(ident, catalogo.resolverTurma(turma));
    }

    @PutMapping("/api/admin/turmas/{turma}/menu")
    public MenuServico.Menu definir(@AuthenticationPrincipal Identidade ident, @PathVariable String turma,
            @Valid @RequestBody MenuIn dados) {
        return menu.definir(ident, catalogo.resolverTurma(turma), dados.botoes().stream()
                .map(b -> MenuServico.Botao.de(b.rotulo(), b.funcionalidade(), b.categoria())).toList());
    }

    @PostMapping("/api/admin/turmas/{turma}/menu/copiar")
    public MenuServico.Menu copiar(@AuthenticationPrincipal Identidade ident, @PathVariable String turma,
            @Valid @RequestBody CopiaIn dados) {
        return menu.copiar(ident, catalogo.resolverTurma(dados.de()), catalogo.resolverTurma(turma));
    }
}
