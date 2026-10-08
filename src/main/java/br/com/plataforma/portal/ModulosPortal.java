package br.com.plataforma.portal;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.materiais.MateriaisServico;
import br.com.plataforma.materiais.MaterialLigado;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.rascunhos.PublicacaoServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import br.com.plataforma.simulados.MontagemDaProva;
import br.com.plataforma.vimeo.ImportacaoVimeo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final MateriaisServico materiais;
    private final RascunhosServico rascunhos;
    private final PublicacaoServico publicacao;
    private final ImportacaoVimeo vimeo;

    public ModulosPortal(EstruturaServico estrutura, CatalogoServico catalogo, MateriaisServico materiais,
            RascunhosServico rascunhos, PublicacaoServico publicacao, ImportacaoVimeo vimeo) {
        this.estrutura = estrutura;
        this.catalogo = catalogo;
        this.materiais = materiais;
        this.rascunhos = rascunhos;
        this.publicacao = publicacao;
        this.vimeo = vimeo;
    }

    public record TurmasIn(@NotNull List<String> turmas) {}

    public record CopiaIn(@NotBlank String de) {}

    public record ItemAtribuido(Integer itemId, List<String> turmas) {}

    @GetMapping("/biblioteca")
    public List<EstruturaServico.ModuloDaBiblioteca> biblioteca(@AuthenticationPrincipal Identidade ident) {
        ident.exigirOperador();
        return estrutura.biblioteca();
    }

    /** Todos os módulos com sub-módulos e itens: a tela "Aulas". Nas rotas de edição, a turma é "biblioteca". */
    @GetMapping("/biblioteca/arvore")
    public List<EstruturaServico.ModuloNaArvore> arvore(@AuthenticationPrincipal Identidade ident) {
        ident.exigirOperador();
        return estrutura.arvoreDaBiblioteca();
    }

    /** As turmas que recebem o módulo, trocadas de uma vez. */
    @PutMapping("/modulos/{modulo}/turmas")
    @Transactional
    public EstruturaServico.ModuloDaBiblioteca turmasDoModulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String modulo, @Valid @RequestBody TurmasIn dados) {
        var m = estrutura.atribuirModulo(ident, estrutura.resolverModulo(null, modulo),
                catalogo.resolverTurmas(dados.turmas()));
        return EstruturaServico.ModuloDaBiblioteca.de(m);
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

    // --- PDF nas aulas (decisão 0013) ------------------------------------------

    /** {@code material} null tira o PDF da linha. */
    public record MaterialIn(Integer material) {}

    public record PdfIn(@NotNull Integer material, String nome) {}

    public record ItemComPdf(Integer itemId, String nome, MaterialLigado material) {}

    private br.com.plataforma.estrutura.Item exigirItem(Integer item) {
        return estrutura.item(item).orElseThrow(() -> new NaoEncontrado("Item %d não existe.".formatted(item)));
    }

    @PutMapping("/itens/{item}/material")
    @Transactional
    public ItemComPdf materialDoItem(@AuthenticationPrincipal Identidade ident, @PathVariable Integer item,
            @RequestBody MaterialIn dados) {
        var i = estrutura.anexarMaterial(ident, exigirItem(item),
                dados.material() == null ? null : materiais.exigir(String.valueOf(dados.material())));
        return new ItemComPdf(i.getId(), i.getNome(), MaterialLigado.de(i.getMaterial()));
    }

    public record Capa(Integer moduloId, String icone, Long fotoVersao) {}

    private br.com.plataforma.estrutura.Modulo exigirModulo(Integer modulo) {
        return estrutura.modulo(modulo).orElseThrow(() -> new NaoEncontrado("Módulo %d não existe.".formatted(modulo)));
    }

    /** A foto do cartão do módulo. O portal manda a imagem já recortada em quadrado. */
    @PostMapping(value = "/modulos/{modulo}/foto", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Capa fotoDoModulo(@AuthenticationPrincipal Identidade ident, @PathVariable Integer modulo,
            @org.springframework.web.bind.annotation.RequestPart("arquivo") org.springframework.web.multipart.MultipartFile arquivo)
            throws java.io.IOException {
        var m = estrutura.definirFoto(ident, exigirModulo(modulo), arquivo.getBytes(), Instant.now());
        return new Capa(m.getId(), m.getIcone(), m.getFotoVersao());
    }

    public record QuestaoNovaIn(@NotBlank String enunciado, @NotNull Map<String, String> alternativas,
            @NotBlank String gabarito, String assunto, String subassunto, String dificuldade, String vimeoId,
            String resolucaoComentada, Map<String, String> comentarios) {}

    /** A questão do acervo ({@code questaoId}) ou uma nova, criada ali mesmo ({@code nova}). */
    public record QuestaoNaLinhaIn(Integer questaoId, String nome, @Valid QuestaoNovaIn nova) {}

    /**
     * Uma linha de questão no fim do sub-módulo. É o professor montando: já sai publicada, com a
     * aprovação dele gravada.
     */
    @PostMapping("/submodulos/{submodulo}/questao")
    @Transactional
    public EstruturaServico.ItemNaArvore questaoNoSubmodulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer submodulo, @Valid @RequestBody QuestaoNaLinhaIn dados) {
        var sub = estrutura.submodulo(submodulo)
                .orElseThrow(() -> new NaoEncontrado("Sub-módulo %d não existe.".formatted(submodulo)));
        if ((dados.questaoId() == null) == (dados.nova() == null)) {
            throw new RegraDeNegocio("Informe a questão do acervo ou a questão nova — uma das duas.");
        }
        var nova = dados.nova();
        MontagemDaProva.Entrada entrada = nova == null
                ? new MontagemDaProva.PorId(String.valueOf(dados.questaoId()))
                : new MontagemDaProva.Nova(new QuestoesServico.DadosDaQuestaoNova(nova.enunciado(),
                        nova.alternativas(), nova.gabarito(), nova.assunto(), nova.subassunto(),
                        nova.dificuldade(), vimeo.resolucao(nova.vimeoId()), false,
                        nova.resolucaoComentada(), null, nova.comentarios()));
        var rascunho = rascunhos.criarQuestoesNaAula(ident, null, sub,
                List.of(new RascunhosServico.QuestaoParaAula(entrada, dados.nome())), null);
        publicacao.publicarPeloPortal(ident, rascunho.getId(), Instant.now());
        return EstruturaServico.ItemNaArvore.de(estrutura.itensDoRascunho(rascunho.getId(), false).getFirst());
    }

    /** Uma linha só de PDF no fim do sub-módulo. */
    @PostMapping("/submodulos/{submodulo}/pdf")
    @Transactional
    public ItemComPdf pdfNoSubmodulo(@AuthenticationPrincipal Identidade ident, @PathVariable Integer submodulo,
            @Valid @RequestBody PdfIn dados) {
        var sub = estrutura.submodulo(submodulo)
                .orElseThrow(() -> new NaoEncontrado("Sub-módulo %d não existe.".formatted(submodulo)));
        var i = estrutura.criarItemPdf(ident, sub, materiais.exigir(String.valueOf(dados.material())), dados.nome());
        return new ItemComPdf(i.getId(), i.getNome(), MaterialLigado.de(i.getMaterial()));
    }

    // --- a ordem e o nome, pela tela de montar o curso -------------------------

    /** A fila como ficou na tela, de cima para baixo. */
    public record OrdemIn(@NotNull List<Integer> ids) {}

    public record NomeDoSubmoduloIn(@NotBlank @jakarta.validation.constraints.Size(max = 120) String nome) {}

    public record SubmoduloEditado(Integer submoduloId, String nome) {}

    private br.com.plataforma.estrutura.SubModulo exigirSubmodulo(Integer submodulo) {
        return estrutura.submodulo(submodulo)
                .orElseThrow(() -> new NaoEncontrado("Sub-módulo %d não existe.".formatted(submodulo)));
    }

    /** A ordem dos módulos na biblioteca: é a que o aluno vê em "Meu curso". */
    @PutMapping("/biblioteca/ordem")
    public OrdemIn ordemDosModulos(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody OrdemIn dados) {
        estrutura.ordenarModulos(ident, dados.ids());
        return dados;
    }

    @PutMapping("/modulos/{modulo}/submodulos/ordem")
    @Transactional
    public OrdemIn ordemDosSubmodulos(@AuthenticationPrincipal Identidade ident, @PathVariable Integer modulo,
            @Valid @RequestBody OrdemIn dados) {
        estrutura.ordenarSubmodulos(ident, exigirModulo(modulo), dados.ids());
        return dados;
    }

    @PutMapping("/submodulos/{submodulo}/itens/ordem")
    @Transactional
    public OrdemIn ordemDosItens(@AuthenticationPrincipal Identidade ident, @PathVariable Integer submodulo,
            @Valid @RequestBody OrdemIn dados) {
        estrutura.ordenarItens(ident, exigirSubmodulo(submodulo), dados.ids());
        return dados;
    }

    @org.springframework.web.bind.annotation.PatchMapping("/submodulos/{submodulo}")
    @Transactional
    public SubmoduloEditado renomearSubmodulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer submodulo, @Valid @RequestBody NomeDoSubmoduloIn dados) {
        var sub = estrutura.editarSubmodulo(ident, exigirSubmodulo(submodulo), dados.nome(), null);
        return new SubmoduloEditado(sub.getId(), sub.getNome());
    }

    @PostMapping("/turmas/{turma}/modulos/copiar")
    public EstruturaServico.ModulosCopiados copiar(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @Valid @RequestBody CopiaIn dados) {
        return estrutura.copiarModulos(ident, catalogo.resolverTurma(dados.de()), catalogo.resolverTurma(turma));
    }
}
