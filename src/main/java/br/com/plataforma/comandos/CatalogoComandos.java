package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/comandos")
public class CatalogoComandos {

    private final CatalogoServico catalogo;

    public CatalogoComandos(CatalogoServico catalogo) {
        this.catalogo = catalogo;
    }

    @PostMapping("/listar_turmas")
    @Transactional(readOnly = true)
    public List<CatalogoServico.TurmaNaLista> listarTurmas(@AuthenticationPrincipal Identidade ident) {
        return catalogo.listarTurmas(ident);
    }

    public record CriarTurma(
            @NotBlank(message = "é obrigatório: o nome da turma, ex.: 'Extensivo 2027 Online'")
            @Size(max = 120, message = "tem até 120 letras") String nome) {}

    /** Turma nova, vazia: sem alunos e sem módulos. Os módulos chegam por atribuir_turmas ou copiar_modulos. */
    @PostMapping("/criar_turma")
    public CatalogoServico.TurmaCadastrada criarTurma(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody CriarTurma pedido) {
        return catalogo.criarTurma(ident, pedido.nome());
    }
}
