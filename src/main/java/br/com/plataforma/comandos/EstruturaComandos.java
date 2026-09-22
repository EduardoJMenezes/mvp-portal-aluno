package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comandos de estrutura do curso, chamados pelo adaptador MCP.
 *
 * <p>Cada método é uma transação inteira: é o que preserva o {@code with _sessao()} do Python.
 */
@RestController
@RequestMapping("/comandos")
public class EstruturaComandos {

    private final CatalogoServico catalogo;
    private final EstruturaServico estrutura;

    public EstruturaComandos(CatalogoServico catalogo, EstruturaServico estrutura) {
        this.catalogo = catalogo;
        this.estrutura = estrutura;
    }

    // --- criar_modulo --------------------------------------------------------

    public record CriarModulo(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório, ex.: 'K01 - Introdução à química orgânica'") String nome,
            List<String> submodulos) {}

    public record ModuloCriado(
            Integer moduloId, String modulo, String turma, List<String> submodulos, String mensagem) {}

    @PostMapping("/criar_modulo")
    @Transactional
    public ModuloCriado criarModulo(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody CriarModulo pedido) {
        var turma = catalogo.resolverTurma(pedido.turma());
        var modulo = estrutura.criarModulo(ident, turma, pedido.nome(), null);

        var nomes = pedido.submodulos() == null || pedido.submodulos().isEmpty()
                ? EstruturaServico.SUBMODULOS_PADRAO
                : pedido.submodulos();
        var criados = new ArrayList<String>();
        for (int i = 0; i < nomes.size(); i++) {
            criados.add(estrutura.criarSubmodulo(ident, modulo, nomes.get(i), i + 1).getNome());
        }

        return new ModuloCriado(modulo.getId(), modulo.getNome(), turma.getNome(), criados,
                "Módulo criado, ainda sem nenhum vídeo.");
    }

    // --- criar_submodulo -----------------------------------------------------

    public record CriarSubmodulo(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o nome ou o id do módulo") String modulo,
            @NotBlank(message = "é obrigatório, ex.: 'Questões da apostila'") String nome) {}

    public record SubmoduloCriado(Integer submoduloId, String submodulo, String modulo, String turma) {}

    @PostMapping("/criar_submodulo")
    @Transactional
    public SubmoduloCriado criarSubmodulo(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody CriarSubmodulo pedido) {
        var turma = catalogo.resolverTurma(pedido.turma());
        var alvos = estrutura.alvos(turma, pedido.modulo(), null, null);
        var sub = estrutura.criarSubmodulo(ident, alvos.modulo(), pedido.nome(), null);
        return new SubmoduloCriado(sub.getId(), sub.getNome(), alvos.modulo().getNome(), turma.getNome());
    }

    // --- editar_modulo -------------------------------------------------------

    public record EditarModulo(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o módulo a alterar") String modulo,
            String novoNome,
            Integer novaOrdem) {}

    public record ModuloEditado(String modulo, Integer ordem, String turma) {}

    @PostMapping("/editar_modulo")
    @Transactional
    public ModuloEditado editarModulo(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody EditarModulo pedido) {
        if (pedido.novoNome() == null && pedido.novaOrdem() == null) {
            throw new RegraDeNegocio("Diga o que mudar: nome, ordem, ou os dois.");
        }
        var turma = catalogo.resolverTurma(pedido.turma());
        var alvos = estrutura.alvos(turma, pedido.modulo(), null, null);
        var modulo = estrutura.editarModulo(ident, alvos.modulo(), pedido.novoNome(), pedido.novaOrdem());
        return new ModuloEditado(modulo.getNome(), modulo.getOrdem(), turma.getNome());
    }

    // --- editar_item ---------------------------------------------------------

    public record EditarItem(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o módulo onde o item está") String modulo,
            @NotBlank(message = "é obrigatório: o sub-módulo onde o item está") String submodulo,
            @NotBlank(message = "é obrigatório, ex.: 'Q04'") String item,
            String novoNome,
            Integer novaOrdem,
            String moverParaSubmodulo) {}

    public record ItemEditado(String item, Integer ordem, String submodulo) {}

    @PostMapping("/editar_item")
    @Transactional
    public ItemEditado editarItem(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody EditarItem pedido) {
        if (pedido.novoNome() == null && pedido.novaOrdem() == null && pedido.moverParaSubmodulo() == null) {
            throw new RegraDeNegocio("Diga o que mudar: nome, ordem ou mover_para_submodulo.");
        }
        var turma = catalogo.resolverTurma(pedido.turma());
        var alvos = estrutura.alvos(turma, pedido.modulo(), pedido.submodulo(), pedido.item());

        var alvo = alvos.item();
        if (pedido.moverParaSubmodulo() != null) {
            alvo = estrutura.moverItem(ident, alvo,
                    estrutura.resolverSubmodulo(alvos.modulo(), pedido.moverParaSubmodulo()));
        }
        alvo = estrutura.editarItem(ident, alvo, pedido.novoNome(), pedido.novaOrdem());
        return new ItemEditado(alvo.getNome(), alvo.getOrdem(), alvo.getSubmodulo().getNome());
    }

    // --- remover_do_curso ----------------------------------------------------

    public record RemoverDoCurso(
            @NotBlank(message = "é obrigatória: o nome ou o id da turma") String turma,
            @NotBlank(message = "é obrigatório: o módulo alvo, ou onde está o alvo") String modulo,
            String submodulo,
            String item) {}

    /**
     * Remove o mais específico que vier: o item, o sub-módulo ou o módulo.
     *
     * <p>Nada é apagado: a remoção é lógica e reversível, e não cascateia — os filhos ficam
     * intactos e voltam junto se o pai for restaurado.
     */
    @PostMapping("/remover_do_curso")
    @Transactional
    public EstruturaServico.Removido removerDoCurso(
            @AuthenticationPrincipal Identidade ident, @Valid @RequestBody RemoverDoCurso pedido) {
        if (pedido.item() != null && pedido.submodulo() == null) {
            // Sem esta checagem, "remova a Q04" sem o sub-módulo removeria o módulo inteiro.
            throw new RegraDeNegocio("Para remover um item, diga também o sub-módulo dele.");
        }
        var turma = catalogo.resolverTurma(pedido.turma());
        var a = estrutura.alvos(turma, pedido.modulo(), pedido.submodulo(), pedido.item());

        if (a.item() != null) {
            return estrutura.removerItem(ident, a.item());
        }
        if (a.submodulo() != null) {
            return estrutura.removerSubmodulo(ident, a.submodulo());
        }
        return estrutura.removerModulo(ident, a.modulo());
    }

    // --- listar_modulos ------------------------------------------------------

    public record ListarModulos(String turma) {}

    @PostMapping("/listar_modulos")
    @Transactional(readOnly = true)
    public List<EstruturaServico.ModuloNaArvore> listarModulos(
            @AuthenticationPrincipal Identidade ident, @RequestBody(required = false) ListarModulos pedido) {
        return catalogo.listarModulos(ident, pedido == null ? null : pedido.turma());
    }
}
