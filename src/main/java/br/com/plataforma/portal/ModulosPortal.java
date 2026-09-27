package br.com.plataforma.portal;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.estrutura.EstruturaServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A biblioteca de módulos e a atribuição às turmas (decisão 0011), para as telas do professor. */
@RestController
@RequestMapping("/api/admin")
public class ModulosPortal {

    private final EstruturaServico estrutura;
    private final CatalogoServico catalogo;

    public ModulosPortal(EstruturaServico estrutura, CatalogoServico catalogo) {
        this.estrutura = estrutura;
        this.catalogo = catalogo;
    }

    public record TurmasIn(@NotNull List<String> turmas) {}

    public record CopiaIn(@NotBlank String de) {}

    public record ItemAtribuido(Integer itemId, List<String> turmas) {}

    @GetMapping("/biblioteca")
    public List<EstruturaServico.ModuloDaBiblioteca> biblioteca(@AuthenticationPrincipal Identidade ident) {
        ident.exigirOperador();
        return estrutura.biblioteca();
    }

    /** As turmas que recebem o módulo, trocadas de uma vez. */
    @PutMapping("/modulos/{modulo}/turmas")
    @Transactional
    public EstruturaServico.ModuloDaBiblioteca turmasDoModulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String modulo, @Valid @RequestBody TurmasIn dados) {
        var m = estrutura.atribuirModulo(ident, estrutura.resolverModulo(null, modulo),
                catalogo.resolverTurmas(dados.turmas()));
        return new EstruturaServico.ModuloDaBiblioteca(m.getId(), m.getNome(), m.getOrdem(), m.getCategoria(),
                m.getTurmas().stream().map(br.com.plataforma.catalogo.Turma::getNome).toList());
    }

    /** A aula só para estas turmas; lista vazia devolve a aula a toda turma do módulo. */
    @PutMapping("/itens/{item}/turmas")
    @Transactional
    public ItemAtribuido turmasDoItem(@AuthenticationPrincipal Identidade ident, @PathVariable Integer item,
            @Valid @RequestBody TurmasIn dados) {
        var i = estrutura.item(item).orElseThrow(() -> new NaoEncontrado("Item %d não existe.".formatted(item)));
        estrutura.restringirItem(ident, i, catalogo.resolverTurmas(dados.turmas()));
        return new ItemAtribuido(i.getId(), i.getTurmas().stream().map(br.com.plataforma.catalogo.Turma::getNome).toList());
    }

    @PostMapping("/turmas/{turma}/modulos/copiar")
    public EstruturaServico.ModulosCopiados copiar(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @Valid @RequestBody CopiaIn dados) {
        return estrutura.copiarModulos(ident, catalogo.resolverTurma(dados.de()), catalogo.resolverTurma(turma));
    }
}
