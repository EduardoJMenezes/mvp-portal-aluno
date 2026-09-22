package br.com.plataforma.comandos;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
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
}
