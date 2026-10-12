package br.com.plataforma.portal;

import br.com.plataforma.analytics.AnaliseServico;
import br.com.plataforma.analytics.DevolutivaServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A devolutiva por assunto e a análise escrita: do aluno para si, e do professor para um aluno ou
 * para a turma.
 */
@RestController
@RequestMapping("/api")
public class DevolutivaPortal {

    private final DevolutivaServico devolutiva;
    private final CatalogoServico catalogo;
    private final AnaliseServico analises;

    public DevolutivaPortal(DevolutivaServico devolutiva, CatalogoServico catalogo, AnaliseServico analises) {
        this.devolutiva = devolutiva;
        this.catalogo = catalogo;
        this.analises = analises;
    }

    @GetMapping("/aluno/desempenho/assuntos")
    public DevolutivaServico.Devolutiva minha(@AuthenticationPrincipal Identidade ident) {
        return devolutiva.minha(ident, Instant.now());
    }

    @GetMapping("/admin/alunos/{aluno}/assuntos")
    public DevolutivaServico.Devolutiva doAluno(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno) {
        return devolutiva.doAluno(ident, aluno, Instant.now());
    }

    @GetMapping("/admin/turmas/{turma}/devolutiva")
    @Transactional
    public DevolutivaServico.DevolutivaDaTurma daTurma(@AuthenticationPrincipal Identidade ident, @PathVariable String turma) {
        ident.exigirOperador();
        return devolutiva.daTurma(ident, catalogo.resolverTurma(turma), Instant.now());
    }

    // --- a análise escrita e o acompanhamento ---------------------------------------

    /** Os alunos, para o professor procurar um pelo nome e abrir o acompanhamento dele. */
    @GetMapping("/admin/alunos")
    public java.util.List<AnaliseServico.AlunoNaBusca> alunos(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String busca) {
        return analises.alunos(ident, busca);
    }

    /** A análise de um aluno: a geral (só do professor) ou, com {@code simulado}, a que ele lê no resultado. */
    @GetMapping("/admin/alunos/{aluno}/analise")
    public AnaliseServico.DoAluno analiseDoAluno(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno,
            @RequestParam(required = false) String simulado) {
        return analises.doAluno(ident, aluno, simulado);
    }

    public record Visibilidade(String simulado, boolean oculta) {}

    /** Ocultar tira a análise do simulado da frente do aluno; mostrar devolve. */
    @PostMapping("/admin/alunos/{aluno}/analise/visibilidade")
    public AnaliseServico.DoAluno visibilidade(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno,
            @RequestBody Visibilidade pedido) {
        return analises.ocultar(ident, aluno, pedido.simulado(), pedido.oculta(), Instant.now());
    }

    /**
     * O professor libera o resultado pelo portal. O caminho de sempre é o Claude, que antes gera as
     * análises; este botão existe para o resultado não ficar preso se ele não puder usá-lo.
     */
    @PostMapping("/admin/simulados/{simulado}/resultado/liberar")
    public AnaliseServico.ResultadoLiberado liberarResultado(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado) {
        return analises.liberarResultado(ident, simulado, Instant.now());
    }
}
