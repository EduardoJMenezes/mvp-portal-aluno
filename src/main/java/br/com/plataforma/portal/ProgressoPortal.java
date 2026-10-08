package br.com.plataforma.portal;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.progresso.ProgressoServico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aula assistida: o aluno registra o que viu, o professor acompanha por turma e por aluno. */
@RestController
@RequestMapping("/api")
public class ProgressoPortal {

    private final ProgressoServico progresso;
    private final CatalogoServico catalogo;

    public ProgressoPortal(ProgressoServico progresso, CatalogoServico catalogo) {
        this.progresso = progresso;
        this.catalogo = catalogo;
    }

    // --- o aluno -----------------------------------------------------------------

    public record PosicaoIn(@NotNull Integer posicaoSegundos, @NotNull Integer duracaoSegundos) {}

    public record ConcluidoIn(@NotNull Boolean concluido) {}

    /** Onde o aluno parou neste vídeo: o player continua daqui. */
    @GetMapping("/aluno/itens/{item}/progresso")
    public ProgressoServico.Estado ver(@AuthenticationPrincipal Identidade ident, @PathVariable Integer item) {
        return progresso.ver(ident, item, Instant.now());
    }

    /** O aviso do player, de tempos em tempos. Passou de 90%, o vídeo fica concluído. */
    @PostMapping("/aluno/itens/{item}/progresso")
    public ProgressoServico.Estado registrar(@AuthenticationPrincipal Identidade ident, @PathVariable Integer item,
            @Valid @RequestBody PosicaoIn dados) {
        return progresso.registrar(ident, item, dados.posicaoSegundos(), dados.duracaoSegundos(), Instant.now());
    }

    @PutMapping("/aluno/itens/{item}/concluido")
    public ProgressoServico.Estado marcar(@AuthenticationPrincipal Identidade ident, @PathVariable Integer item,
            @Valid @RequestBody ConcluidoIn dados) {
        return progresso.marcar(ident, item, dados.concluido(), Instant.now());
    }

    // --- o professor -------------------------------------------------------------

    @GetMapping("/admin/turmas/{turma}/progresso")
    @Transactional(readOnly = true)
    public ProgressoServico.ProgressoDaTurma daTurma(@AuthenticationPrincipal Identidade ident, @PathVariable String turma) {
        ident.exigirOperador();
        return progresso.daTurma(ident, catalogo.resolverTurma(turma), Instant.now());
    }

    @GetMapping("/admin/alunos/{aluno}/progresso")
    public ProgressoServico.ProgressoDoAluno doAluno(@AuthenticationPrincipal Identidade ident, @PathVariable String aluno) {
        return progresso.doAluno(ident, aluno, Instant.now());
    }
}
