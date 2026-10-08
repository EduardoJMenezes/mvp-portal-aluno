package br.com.plataforma.portal;

import br.com.plataforma.analytics.DevolutivaServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A devolutiva por assunto: do aluno para si, e do professor para um aluno ou para a turma. */
@RestController
@RequestMapping("/api")
public class DevolutivaPortal {

    private final DevolutivaServico devolutiva;
    private final CatalogoServico catalogo;

    public DevolutivaPortal(DevolutivaServico devolutiva, CatalogoServico catalogo) {
        this.devolutiva = devolutiva;
        this.catalogo = catalogo;
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
}
