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

    // --- a análise escrita ---------------------------------------------------------

    @GetMapping("/aluno/analise")
    public AnaliseServico.MinhaAnalise minhaAnalise(@AuthenticationPrincipal Identidade ident) {
        return analises.minha(ident, Instant.now());
    }

    @GetMapping("/admin/alunos/{aluno}/analise")
    public AnaliseServico.DoAluno analiseDoAluno(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno) {
        return analises.doAluno(ident, aluno, Instant.now());
    }

    public record Visibilidade(boolean oculta) {}

    /** Ocultar tira a análise da frente do aluno; mostrar devolve. */
    @PostMapping("/admin/alunos/{aluno}/analise/visibilidade")
    public AnaliseServico.DoAluno visibilidade(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno,
            @RequestBody Visibilidade pedido) {
        return analises.ocultar(ident, aluno, pedido.oculta(), Instant.now());
    }

    public record PedirOutra(String orientacao) {}

    /** Pedir outra oculta a atual e põe o aluno no começo da fila, com a orientação do professor. */
    @PostMapping("/admin/alunos/{aluno}/analise/refazer")
    public AnaliseServico.DoAluno pedirOutra(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno,
            @RequestBody(required = false) PedirOutra pedido) {
        return analises.pedirOutra(ident, aluno, pedido == null ? null : pedido.orientacao(), Instant.now());
    }
}
