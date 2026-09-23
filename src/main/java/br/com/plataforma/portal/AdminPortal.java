package br.com.plataforma.portal;

import br.com.plataforma.analytics.AnalyticsServico;
import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comandos.AcervoComandos;
import br.com.plataforma.comandos.EstruturaComandos;
import br.com.plataforma.comandos.ImportacoesComandos;
import br.com.plataforma.comandos.QuestoesComandos;
import br.com.plataforma.comandos.RascunhosComandos;
import br.com.plataforma.comandos.SimuladosComandos;
import br.com.plataforma.comandos.TaxonomiaComandos;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.importacoes.ImportacoesServico;
import br.com.plataforma.importacoes.Prints;
import br.com.plataforma.materiais.MateriaisServico;
import br.com.plataforma.questoes.ParteDaQuestao;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.rascunhos.PublicacaoServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import br.com.plataforma.vimeo.ImportacaoVimeo;
import br.com.plataforma.vimeo.Vimeo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * API do professor/gerenciador.
 *
 * <p>Mesmíssimos serviços que as tools do MCP chamam — o que muda é só a borda. Toda operação do
 * MCP tem aqui o endpoint equivalente, e o portal em Next.js consome esta API. Além do que o MCP
 * faz, moram aqui o que só o portal faz: turmas, alunos e matrículas, senha de aluno e tokens do
 * MCP. Sessões do Claude Code entram com o token do MCP e por isso não aprovam nem descartam
 * rascunho, não cadastram aluno e não emitem token.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPortal {

    private final CatalogoServico catalogo;
    private final ContasServico contas;
    private final TaxonomiaServico taxonomia;
    private final QuestoesServico questoes;
    private final AnalyticsServico analytics;
    private final RascunhosServico rascunhos;
    private final PublicacaoServico publicacao;
    private final SimuladosServico simulados;
    private final ImportacoesServico importacoes;
    private final MateriaisServico materiais;
    private final AulasServico aulas;
    private final ImportacaoVimeo vimeoImportacao;
    private final Vimeo vimeo;
    private final ConfigDoPortal config;
    private final EstruturaComandos estruturaComandos;
    private final TaxonomiaComandos taxonomiaComandos;
    private final AcervoComandos acervoComandos;
    private final RascunhosComandos rascunhosComandos;
    private final QuestoesComandos questoesComandos;
    private final SimuladosComandos simuladosComandos;
    private final ImportacoesComandos importacoesComandos;

    public AdminPortal(CatalogoServico catalogo, ContasServico contas, TaxonomiaServico taxonomia,
            QuestoesServico questoes, AnalyticsServico analytics, RascunhosServico rascunhos,
            PublicacaoServico publicacao, SimuladosServico simulados, ImportacoesServico importacoes,
            MateriaisServico materiais, AulasServico aulas, ImportacaoVimeo vimeoImportacao, Vimeo vimeo,
            ConfigDoPortal config, EstruturaComandos estruturaComandos, TaxonomiaComandos taxonomiaComandos,
            AcervoComandos acervoComandos, RascunhosComandos rascunhosComandos,
            QuestoesComandos questoesComandos, SimuladosComandos simuladosComandos,
            ImportacoesComandos importacoesComandos) {
        this.catalogo = catalogo;
        this.contas = contas;
        this.taxonomia = taxonomia;
        this.questoes = questoes;
        this.analytics = analytics;
        this.rascunhos = rascunhos;
        this.publicacao = publicacao;
        this.simulados = simulados;
        this.importacoes = importacoes;
        this.materiais = materiais;
        this.aulas = aulas;
        this.vimeoImportacao = vimeoImportacao;
        this.vimeo = vimeo;
        this.config = config;
        this.estruturaComandos = estruturaComandos;
        this.taxonomiaComandos = taxonomiaComandos;
        this.acervoComandos = acervoComandos;
        this.rascunhosComandos = rascunhosComandos;
        this.questoesComandos = questoesComandos;
        this.simuladosComandos = simuladosComandos;
        this.importacoesComandos = importacoesComandos;
    }

    // --- turmas --------------------------------------------------------------

    @GetMapping("/turmas")
    public List<CatalogoServico.TurmaNaLista> turmas(@AuthenticationPrincipal Identidade ident) {
        return catalogo.listarTurmas(ident);
    }

    public record TurmaIn(@NotBlank @Size(max = 120) String nome, @NotNull @Min(2000) @Max(2100) Integer ano) {}

    public record EdicaoTurmaIn(@Size(min = 1, max = 120) String nome, @Min(2000) @Max(2100) Integer ano) {}

    @PostMapping("/turmas")
    public CatalogoServico.TurmaCadastrada criarTurma(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody TurmaIn dados) {
        return catalogo.criarTurma(ident, dados.nome(), dados.ano());
    }

    @PatchMapping("/turmas/{turma}")
    public CatalogoServico.TurmaCadastrada editarTurma(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @Valid @RequestBody EdicaoTurmaIn dados) {
        return catalogo.editarTurma(ident, turma, dados.nome(), dados.ano());
    }

    // --- alunos e matrículas -------------------------------------------------

    public record MatriculaIn(@NotBlank @Email String email, @Size(max = 120) String nome) {}

    @GetMapping("/turmas/{turma}/alunos")
    @Transactional(readOnly = true)
    public ContasServico.AlunosDaTurma alunosDaTurma(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma) {
        return contas.alunosDaTurma(ident, catalogo.resolverTurma(turma));
    }

    /** Matricula; se o e-mail não tem conta, cria com senha temporária (mostrada uma vez). */
    @PostMapping("/turmas/{turma}/alunos")
    @Transactional
    public ContasServico.Matriculado matricular(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @Valid @RequestBody MatriculaIn dados) {
        return contas.matricular(ident, catalogo.resolverTurma(turma), dados.nome(), dados.email());
    }

    @DeleteMapping("/turmas/{turma}/alunos/{aluno}")
    @Transactional
    public ContasServico.Desmatriculado desmatricular(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String aluno) {
        return contas.desmatricular(ident, catalogo.resolverTurma(turma), aluno);
    }

    /** Senha temporária nova, mostrada uma vez. As sessões do aluno caem. */
    @PostMapping("/alunos/{aluno}/senha")
    public ContasServico.SenhaRedefinida redefinirSenha(@AuthenticationPrincipal Identidade ident,
            @PathVariable String aluno) {
        return contas.redefinirSenha(ident, aluno, Instant.now());
    }

    // --- tokens do MCP -------------------------------------------------------

    public record TokenIn(@Size(max = 120) String nome) {}

    @GetMapping("/tokens")
    public List<ContasServico.TokenNaLista> tokens(@AuthenticationPrincipal Identidade ident) {
        return contas.tokensDoOperador(ident);
    }

    /** O valor do token aparece só nesta resposta. */
    @PostMapping("/tokens")
    public ContasServico.TokenEmitido emitirToken(@AuthenticationPrincipal Identidade ident,
            @RequestBody(required = false) TokenIn dados) {
        return contas.emitirToken(ident, dados == null ? null : dados.nome());
    }

    @DeleteMapping("/tokens/{tokenId}")
    public ContasServico.TokenNaLista revogarToken(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer tokenId) {
        return contas.revogarToken(ident, tokenId);
    }

    // --- consulta ------------------------------------------------------------

    @GetMapping("/modulos")
    public List<EstruturaServico.ModuloNaArvore> modulos(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String turma) {
        return catalogo.listarModulos(ident, turma);
    }

    @GetMapping("/assuntos")
    public List<TaxonomiaServico.AssuntoNaLista> assuntos() {
        return taxonomia.listarAssuntos();
    }

    /** Acervo de questões de simulado. Página seguinte: {@code offset} += {@code limite}. */
    @GetMapping("/questoes")
    public List<QuestoesServico.QuestaoDescrita> listaQuestoes(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String assunto, @RequestParam(required = false) String status,
            @RequestParam(required = false) String dificuldade,
            @RequestParam(required = false) @Size(max = 200) String busca,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limite,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return questoes.buscar(ident, assunto, status, dificuldade, busca, limite, offset);
    }

    @GetMapping("/alunos/{aluno}/desempenho")
    public AnalyticsServico.DesempenhoDoAluno desempenho(@AuthenticationPrincipal Identidade ident,
            @PathVariable String aluno, @RequestParam(required = false) String simulado) {
        return analytics.desempenhoDoAluno(ident, aluno, simulado, Instant.now());
    }

    // --- curso: módulo, sub-módulo e item ------------------------------------

    public record ModuloIn(@NotBlank String nome, List<String> submodulos) {}

    public record EdicaoModuloIn(String nome, Integer ordem) {}

    public record SubModuloIn(@NotBlank String nome) {}

    public record EdicaoItemIn(String nome, Integer ordem, String moverParaSubmodulo) {}

    public record ItensIn(@NotEmpty List<RascunhosServico.VideoParaImportar> videos) {}

    public record ClassificacaoIn(@NotBlank String assunto, String subassunto, String itens) {}

    @PostMapping("/turmas/{turma}/modulos")
    public EstruturaComandos.ModuloCriado criarModulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @Valid @RequestBody ModuloIn dados) {
        return estruturaComandos.criarModulo(ident, new EstruturaComandos.CriarModulo(turma, dados.nome(), dados.submodulos()));
    }

    @PatchMapping("/turmas/{turma}/modulos/{modulo}")
    public EstruturaComandos.ModuloEditado editarModulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @RequestBody EdicaoModuloIn dados) {
        return estruturaComandos.editarModulo(ident, new EstruturaComandos.EditarModulo(turma, modulo, dados.nome(), dados.ordem()));
    }

    @DeleteMapping("/turmas/{turma}/modulos/{modulo}")
    public EstruturaServico.Removido removerModulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo) {
        return estruturaComandos.removerDoCurso(ident, new EstruturaComandos.RemoverDoCurso(turma, modulo, null, null));
    }

    @PostMapping("/turmas/{turma}/modulos/{modulo}/submodulos")
    public EstruturaComandos.SubmoduloCriado criarSubmodulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @Valid @RequestBody SubModuloIn dados) {
        return estruturaComandos.criarSubmodulo(ident, new EstruturaComandos.CriarSubmodulo(turma, modulo, dados.nome()));
    }

    @DeleteMapping("/turmas/{turma}/modulos/{modulo}/submodulos/{submodulo}")
    public EstruturaServico.Removido removerSubmodulo(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @PathVariable String submodulo) {
        return estruturaComandos.removerDoCurso(ident, new EstruturaComandos.RemoverDoCurso(turma, modulo, submodulo, null));
    }

    /** Vídeos entrando no sub-módulo — em rascunho, como pela tool. */
    @PostMapping("/turmas/{turma}/modulos/{modulo}/submodulos/{submodulo}/itens")
    public AcervoComandos.ItensImportados importarItens(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @PathVariable String submodulo,
            @Valid @RequestBody ItensIn dados) {
        return acervoComandos.importarVideosComoItens(ident,
                new AcervoComandos.ImportarVideosComoItens(turma, modulo, submodulo, dados.videos()));
    }

    @PatchMapping("/turmas/{turma}/modulos/{modulo}/submodulos/{submodulo}/itens/{item}")
    public EstruturaComandos.ItemEditado editarItem(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @PathVariable String submodulo,
            @PathVariable String item, @RequestBody EdicaoItemIn dados) {
        return estruturaComandos.editarItem(ident, new EstruturaComandos.EditarItem(turma, modulo, submodulo, item,
                dados.nome(), dados.ordem(), dados.moverParaSubmodulo()));
    }

    @DeleteMapping("/turmas/{turma}/modulos/{modulo}/submodulos/{submodulo}/itens/{item}")
    public EstruturaServico.Removido removerItem(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @PathVariable String submodulo,
            @PathVariable String item) {
        return estruturaComandos.removerDoCurso(ident, new EstruturaComandos.RemoverDoCurso(turma, modulo, submodulo, item));
    }

    @PostMapping("/turmas/{turma}/modulos/{modulo}/submodulos/{submodulo}/classificacao")
    public TaxonomiaComandos.VideosClassificados classificarVideos(@AuthenticationPrincipal Identidade ident,
            @PathVariable String turma, @PathVariable String modulo, @PathVariable String submodulo,
            @Valid @RequestBody ClassificacaoIn dados) {
        return taxonomiaComandos.classificarVideos(ident, new TaxonomiaComandos.ClassificarVideos(turma, modulo,
                submodulo, dados.assunto(), dados.subassunto(), dados.itens()));
    }

    // --- assuntos ------------------------------------------------------------

    public record AssuntoIn(@NotBlank String nome, List<String> subassuntos) {}

    public record NomeIn(@NotBlank @Size(max = 120) String nome) {}

    @PostMapping("/assuntos")
    public TaxonomiaComandos.AssuntoCadastrado cadastrarAssunto(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody AssuntoIn dados) {
        return taxonomiaComandos.cadastrarAssunto(ident, new TaxonomiaComandos.CadastrarAssunto(dados.nome(), dados.subassuntos()));
    }

    @PatchMapping("/assuntos/{assunto}")
    public TaxonomiaServico.AssuntoEditado editarAssunto(@AuthenticationPrincipal Identidade ident,
            @PathVariable String assunto, @Valid @RequestBody NomeIn dados) {
        return taxonomia.editarAssunto(ident, assunto, dados.nome());
    }

    @DeleteMapping("/assuntos/{assunto}")
    public TaxonomiaServico.AssuntoRemovido excluirAssunto(@AuthenticationPrincipal Identidade ident,
            @PathVariable String assunto) {
        return taxonomia.excluirAssunto(ident, assunto);
    }

    @PatchMapping("/assuntos/{assunto}/subassuntos/{subassunto}")
    public TaxonomiaServico.SubAssuntoEditado editarSubassunto(@AuthenticationPrincipal Identidade ident,
            @PathVariable String assunto, @PathVariable String subassunto, @Valid @RequestBody NomeIn dados) {
        return taxonomia.editarSubassunto(ident, assunto, subassunto, dados.nome());
    }

    @DeleteMapping("/assuntos/{assunto}/subassuntos/{subassunto}")
    public TaxonomiaServico.SubAssuntoRemovido excluirSubassunto(@AuthenticationPrincipal Identidade ident,
            @PathVariable String assunto, @PathVariable String subassunto) {
        return taxonomia.excluirSubassunto(ident, assunto, subassunto);
    }

    // --- Vimeo ---------------------------------------------------------------

    public record ImportacaoIn(@NotBlank String pasta, @NotBlank String turma,
            @NotEmpty List<ImportacaoVimeo.Destino> destinos) {}

    @GetMapping("/vimeo/pastas")
    public ImportacaoVimeo.Pastas vimeoPastas(@RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "60") int limite) {
        return vimeoImportacao.listarPastas(busca, limite);
    }

    @GetMapping("/vimeo/videos")
    public List<Vimeo.Video> vimeoVideos(@RequestParam(required = false) String pasta,
            @RequestParam(required = false) String busca, @RequestParam(defaultValue = "25") int limite) {
        return vimeo.listarVideos(pasta, busca, limite);
    }

    /** O que a importação faria, sem gravar nada. */
    @PostMapping("/vimeo/importacoes/simulacao")
    public ImportacaoVimeo.Avaliacao simularImportacao(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody ImportacaoIn dados) {
        var plano = vimeoImportacao.lerPlano(dados.pasta());
        return vimeoImportacao.avaliar(ident, plano, catalogo.resolverTurma(dados.turma()), dados.destinos());
    }

    /** A pasta distribuída pelos módulos, em rascunho — um por destino. */
    @PostMapping("/vimeo/importacoes")
    public ImportacaoVimeo.Aplicacao importarPasta(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody ImportacaoIn dados) {
        var plano = vimeoImportacao.lerPlano(dados.pasta());
        return vimeoImportacao.aplicar(ident, plano, catalogo.resolverTurma(dados.turma()), dados.destinos(), Instant.now());
    }

    // --- rascunhos -----------------------------------------------------------

    public record PublicacaoIn(List<Integer> itens) {}

    @GetMapping("/rascunhos")
    public List<RascunhosServico.ResumoDoRascunho> listaRascunhos(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String status) {
        return rascunhos.listar(ident, status);
    }

    @GetMapping("/rascunhos/{rascunhoId}")
    public RascunhosServico.RascunhoDetalhado detalheRascunho(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer rascunhoId) {
        return rascunhos.detalhar(ident, rascunhoId, Instant.now());
    }

    /** Revisar e publicar: é aqui que a aprovação humana é carimbada. */
    @PostMapping("/rascunhos/{rascunhoId}/publicar")
    @Transactional
    public PublicacaoServico.Publicacao publicar(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer rascunhoId, @RequestBody(required = false) PublicacaoIn dados) {
        publicacao.aprovarPeloPortal(ident, rascunhoId);
        return publicacao.publicar(ident, rascunhoId, dados == null ? null : dados.itens(), Instant.now());
    }

    @DeleteMapping("/rascunhos/{rascunhoId}")
    public PublicacaoServico.Descartado descartar(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer rascunhoId) {
        return publicacao.descartar(ident, rascunhoId);
    }

    // --- questões ------------------------------------------------------------

    public record QuestaoIn(@NotBlank String enunciado, @NotNull Map<String, String> alternativas,
            @NotBlank String gabarito, String assunto, String subassunto, String dificuldade, String vimeoId,
            Boolean imagemPendente, String resolucaoComentada) {}

    public record EdicaoQuestaoIn(String enunciado, Map<String, String> alternativas, String gabarito,
            String dificuldade, Boolean imagemPendente, String assunto, String subassunto, String vimeoId,
            String resolucaoComentada) {}

    /** Uma questão avulsa, em rascunho. */
    @PostMapping("/questoes")
    public RascunhosServico.RascunhoDetalhado criarQuestao(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody QuestaoIn dados) {
        return rascunhosComandos.criarQuestaoRascunho(ident, new RascunhosComandos.CriarQuestaoRascunho(
                dados.enunciado(), dados.alternativas(), dados.gabarito(), dados.assunto(), dados.subassunto(),
                dados.dificuldade(), vimeoImportacao.resolucao(dados.vimeoId()),
                dados.imagemPendente() != null && dados.imagemPendente(), dados.resolucaoComentada()));
    }

    @GetMapping("/questoes/{questaoId}")
    public QuestoesServico.QuestaoDetalhada detalharQuestao(@AuthenticationPrincipal Identidade ident,
            @PathVariable String questaoId) {
        return questoes.detalhar(ident, questaoId, Instant.now());
    }

    @PatchMapping("/questoes/{questaoId}")
    public QuestoesServico.QuestaoDetalhada editarQuestao(@AuthenticationPrincipal Identidade ident,
            @PathVariable String questaoId, @RequestBody EdicaoQuestaoIn dados) {
        return questoesComandos.editarQuestao(ident, new QuestoesComandos.EditarQuestao(questaoId, dados.enunciado(),
                dados.alternativas(), dados.gabarito(), dados.dificuldade(), dados.imagemPendente(), dados.assunto(),
                dados.subassunto(), vimeoImportacao.resolucao(dados.vimeoId()), dados.resolucaoComentada()));
    }

    @DeleteMapping("/questoes/{questaoId}")
    public QuestoesServico.QuestaoRemovida removerQuestao(@AuthenticationPrincipal Identidade ident,
            @PathVariable String questaoId) {
        return questoes.remover(ident, questaoId, Instant.now());
    }

    /** A figura entra na primeira marca {@code figura:pendente} da parte; sem marca, no fim. */
    @PostMapping(value = "/questoes/{questaoId}/figuras", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuestoesServico.FiguraNaQuestaoResposta anexarFigura(@AuthenticationPrincipal Identidade ident,
            @PathVariable String questaoId, @RequestPart("arquivo") MultipartFile arquivo,
            @RequestParam(defaultValue = "ENUNCIADO") String parte,
            @RequestParam(required = false) String alternativa) throws IOException {
        // O tamanho e o formato são conferidos pelo serviço, pelos bytes: quem envia escolhe o
        // nome, não o conteúdo.
        var conteudo = arquivo.getBytes();
        return questoes.anexarFigura(ident, questaoId, conteudo, arquivo.getOriginalFilename(), parteDe(parte),
                alternativa, Instant.now());
    }

    private static ParteDaQuestao parteDe(String texto) {
        try {
            return ParteDaQuestao.valueOf((texto == null || texto.isBlank() ? "ENUNCIADO" : texto.strip())
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Parte '%s' inválida. Use ENUNCIADO, ALTERNATIVA, RESOLUCAO.".formatted(texto));
        }
    }

    // --- importação de .docx e de prints -------------------------------------

    public record ImportacaoDocxIn(@NotEmpty List<String> turmas, String titulo, String abreEm, String fechaEm,
            @Min(1) Integer duracaoMinutos, String pastaResolucao) {}

    public record CompletarQuestaoIn(@NotNull Integer numero, @NotBlank String enunciado,
            @NotNull Object alternativas, @NotBlank String gabarito, String resolucao) {}

    public record LinkDeEnvio(Integer importacaoId, String link, String expiraEm, String instrucao) {}

    private LinkDeEnvio comLink(ImportacoesServico.LinkDeEnvio l, String oQue) {
        var base = config.mcpBaseUrl().isBlank() ? "" : config.mcpBaseUrl().replaceAll("/$", "");
        return new LinkDeEnvio(l.importacaoId(), base + "/enviar/" + l.token(), l.expiraEm(),
                "Abra o link, envie %s e volte aqui para revisar. O link vale uma vez, por 30 minutos.".formatted(oQue));
    }

    /** O link de uso único pelo qual o .docx chega — a página é do adaptador MCP. */
    @PostMapping("/importacoes")
    public LinkDeEnvio criarLinkDeEnvio(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody ImportacaoDocxIn dados) {
        return comLink(importacoesComandos.importarSimuladoDocx(ident, new ImportacoesComandos.ImportarSimuladoDocx(
                dados.turmas(), dados.titulo(), dados.abreEm(), dados.fechaEm(), dados.duracaoMinutos(),
                dados.pastaResolucao())), "o arquivo .docx do simulado");
    }

    @PostMapping("/importacoes/prints")
    public LinkDeEnvio criarLinkDePrints(@AuthenticationPrincipal Identidade ident) {
        return comLink(importacoesComandos.importarPrints(ident), "os prints das questões");
    }

    /** O que foi lido. As figuras vêm pelo id, em /api/aluno/figuras/{id}. */
    @GetMapping("/importacoes/{importacaoId}")
    public ImportacoesServico.Revisao revisarImportacao(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer importacaoId, @RequestParam(defaultValue = "1") int de,
            @RequestParam(required = false) Integer ate) {
        return importacoesComandos.revisarImportacao(ident, new ImportacoesComandos.RevisarImportacao(importacaoId, de, ate));
    }

    @PostMapping("/importacoes/{importacaoId}/questoes")
    public ImportacoesServico.QuestaoCompletada completarQuestaoImportada(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer importacaoId, @Valid @RequestBody CompletarQuestaoIn dados) {
        return importacoesComandos.completarQuestaoImportada(ident, new ImportacoesComandos.CompletarQuestaoImportada(
                importacaoId, dados.numero(), dados.enunciado(), dados.alternativas(), dados.gabarito(), dados.resolucao()));
    }

    @GetMapping("/importacoes/{importacaoId}/prints")
    public ImportacoesServico.PrintsDaImportacao printsDaImportacao(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer importacaoId) {
        return importacoes.printsDa(ident, importacaoId);
    }

    private byte[] print(Identidade ident, Integer importacaoId, int numero) {
        var ids = importacoes.printsDa(ident, importacaoId).figuras();
        if (numero < 1 || numero > ids.size()) {
            throw new RegraDeNegocio("O print %d não existe: esta importação tem de 1 a %d.".formatted(numero, ids.size()));
        }
        return importacoes.bytesDaFigura(ids.get(numero - 1));
    }

    /** O print na escala em que o retângulo do recorte vale. */
    @GetMapping("/importacoes/{importacaoId}/prints/{numero}")
    public ResponseEntity<byte[]> verPrint(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer importacaoId, @PathVariable int numero) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(Prints.vista(print(ident, importacaoId, numero)));
    }

    public record RecorteIn(@NotNull @Min(1) Integer print, @NotNull Integer questao,
            @NotNull @Size(min = 4, max = 4) List<Double> retangulo, String parte, String alternativa,
            Integer substituir, Boolean estender) {}

    /** A figura sai do print e entra na questão; o recorte fica em /api/aluno/figuras/{figura_id}. */
    @PostMapping("/importacoes/{importacaoId}/recortes")
    public QuestoesServico.FiguraNaQuestaoResposta recortarFigura(@AuthenticationPrincipal Identidade ident,
            @PathVariable Integer importacaoId, @Valid @RequestBody RecorteIn dados) {
        var recorte = Prints.recortar(print(ident, importacaoId, dados.print()), dados.print(), dados.retangulo(),
                dados.estender() == null || dados.estender());
        var questao = String.valueOf(dados.questao());
        if (dados.substituir() != null) {
            return questoes.trocarFigura(ident, dados.substituir(), questao, recorte, Instant.now());
        }
        return questoes.anexarFigura(ident, questao, recorte, "print " + dados.print(), parteDe(dados.parte()),
                dados.alternativa(), Instant.now());
    }

    // --- simulados -----------------------------------------------------------

    public record SimuladoIn(@NotEmpty List<String> turmas, @NotBlank String titulo, @NotEmpty List<Object> questoes,
            String abreEm, String fechaEm, @Min(1) Integer duracaoMinutos, String pastaResolucao) {}

    public record EdicaoSimuladoIn(String titulo, String abreEm, String fechaEm, @Min(1) Integer duracaoMinutos,
            List<String> turmas, List<Object> questoes) {}

    @GetMapping("/simulados")
    public List<SimuladosServico.ResumoDoSimulado> listaSimulados(@AuthenticationPrincipal Identidade ident,
            @RequestParam(required = false) String turma) {
        return simulados.listar(ident, turma == null ? null : catalogo.resolverTurma(turma), Instant.now());
    }

    /** O simulado e as questões novas dele, num rascunho só. */
    @PostMapping("/simulados")
    public RascunhosServico.RascunhoDetalhado criarSimulado(@AuthenticationPrincipal Identidade ident,
            @Valid @RequestBody SimuladoIn dados) {
        Map<String, Object> resolucoes = null;
        if (dados.pastaResolucao() != null && !dados.pastaResolucao().isBlank()) {
            resolucoes = ImportacaoVimeo.resolucoesPorNumero(vimeoImportacao.lerPlano(dados.pastaResolucao()));
        }
        return rascunhosComandos.criarSimuladoRascunho(ident, new RascunhosComandos.CriarSimuladoRascunho(
                dados.turmas(), dados.titulo(), vimeoImportacao.questoesComResolucao(dados.questoes()),
                dados.abreEm(), dados.fechaEm(), dados.duracaoMinutos(), resolucoes));
    }

    @GetMapping("/simulados/{simulado}")
    public SimuladosServico.SimuladoDetalhado detalharSimulado(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado) {
        return simulados.detalhar(ident, simulado, Instant.now());
    }

    @PatchMapping("/simulados/{simulado}")
    public SimuladosServico.ResumoDoSimulado editarSimulado(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado, @Valid @RequestBody EdicaoSimuladoIn dados) {
        return simuladosComandos.editarSimulado(ident, new SimuladosComandos.EditarSimulado(simulado, dados.titulo(),
                dados.abreEm(), dados.fechaEm(), dados.duracaoMinutos(), dados.turmas(),
                vimeoImportacao.questoesComResolucao(dados.questoes())));
    }

    @DeleteMapping("/simulados/{simulado}")
    public SimuladosServico.SimuladoRemovido removerSimulado(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado) {
        return simulados.remover(ident, simulado, Instant.now());
    }

    @GetMapping("/simulados/{simulado}/ranking")
    public SimuladosServico.Ranking ranking(@AuthenticationPrincipal Identidade ident, @PathVariable String simulado) {
        return simulados.ranking(ident, simulado, Instant.now());
    }

    @GetMapping("/simulados/{simulado}/estatisticas")
    public AnalyticsServico.EstatisticasDoSimulado estatisticas(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado) {
        return analytics.estatisticasDoSimulado(ident, simulado, Instant.now());
    }

    // --- materiais -----------------------------------------------------------

    public record EdicaoMaterialIn(@Size(min = 1, max = 200) String titulo, String status, List<String> turmas,
            List<String> alunos) {}

    @GetMapping("/materiais")
    public List<MateriaisServico.Resumo> listaMateriais(@AuthenticationPrincipal Identidade ident) {
        return materiais.listar(ident);
    }

    /** Envia o PDF. Nasce em rascunho: publicar é um PATCH depois de conferir. */
    @PostMapping(value = "/materiais", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public MateriaisServico.Resumo enviarMaterial(@AuthenticationPrincipal Identidade ident,
            @RequestPart("arquivo") MultipartFile arquivo, @RequestParam(defaultValue = "") String titulo,
            @RequestParam(required = false) List<String> turmas, @RequestParam(required = false) List<String> alunos)
            throws IOException {
        var nome = arquivo.getOriginalFilename() == null ? "" : arquivo.getOriginalFilename().strip();
        var semPdf = nome.toLowerCase(Locale.ROOT).endsWith(".pdf") ? nome.substring(0, nome.length() - 4) : nome;
        return materiais.criar(ident, titulo.isBlank() ? semPdf : titulo, arquivo.getBytes(), nome,
                turmas == null ? List.of() : catalogo.resolverTurmas(turmas), alunos == null ? List.of() : alunos);
    }

    @GetMapping("/materiais/{material}")
    public MateriaisServico.Resumo detalharMaterial(@AuthenticationPrincipal Identidade ident, @PathVariable String material) {
        return materiais.detalhar(ident, material);
    }

    @PatchMapping("/materiais/{material}")
    @Transactional
    public MateriaisServico.Resumo editarMaterial(@AuthenticationPrincipal Identidade ident,
            @PathVariable String material, @Valid @RequestBody EdicaoMaterialIn dados) {
        return materiais.editar(ident, material, dados.titulo(), dados.status(),
                dados.turmas() == null ? null : catalogo.resolverTurmas(dados.turmas()), dados.alunos(), Instant.now());
    }

    @DeleteMapping("/materiais/{material}")
    public MateriaisServico.Removido removerMaterial(@AuthenticationPrincipal Identidade ident, @PathVariable String material) {
        return materiais.remover(ident, material);
    }

    // --- aulas ao vivo -------------------------------------------------------
    //
    // A sala é do Zoom, a porta é nossa. A conta do Zoom é dividida com outra plataforma que tem
    // aula rodando: nenhuma rota daqui alcança reunião que não tenha nascido nesta tabela.

    public record AulaIn(@NotBlank @Size(max = 200) String titulo, @NotBlank String inicioEm,
            @Min(5) @Max(480) Integer minutos, @Size(max = 2000) String descricao, Boolean gravar,
            List<String> turmas, List<String> alunos, Integer submoduloId, Boolean publicarGravacao) {}

    public record EdicaoAulaIn(@Size(min = 1, max = 200) String titulo, String inicioEm, @Min(5) @Max(480) Integer minutos,
            String status, List<String> turmas, List<String> alunos, Boolean gravar, Integer submoduloId,
            Boolean publicarGravacao) {}

    private static Instant horario(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(texto.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw new RegraDeNegocio("O horário da aula precisa de fuso.");
        }
    }

    @GetMapping("/aulas")
    public List<AulasServico.Resumo> listaAulas(@AuthenticationPrincipal Identidade ident) {
        return aulas.listar(ident, Instant.now());
    }

    /** Agenda a aula. Nasce em rascunho, e a sala do Zoom só abre ao publicar. */
    @PostMapping("/aulas")
    @Transactional
    public AulasServico.Resumo criarAula(@AuthenticationPrincipal Identidade ident, @Valid @RequestBody AulaIn d) {
        return aulas.criar(ident, new AulasServico.Dados(d.titulo(), horario(d.inicioEm()), d.minutos(), d.descricao(),
                d.gravar(), d.turmas() == null ? List.of() : catalogo.resolverTurmas(d.turmas()),
                d.alunos() == null ? List.of() : d.alunos(), d.submoduloId(), d.publicarGravacao(), null), Instant.now());
    }

    @PatchMapping("/aulas/{aula}")
    @Transactional
    public AulasServico.Resumo editarAula(@AuthenticationPrincipal Identidade ident, @PathVariable String aula,
            @Valid @RequestBody EdicaoAulaIn d) {
        return aulas.editar(ident, aula, new AulasServico.Dados(d.titulo(), horario(d.inicioEm()), d.minutos(), null,
                d.gravar(), d.turmas() == null ? null : catalogo.resolverTurmas(d.turmas()), d.alunos(),
                d.submoduloId(), d.publicarGravacao(), d.status()), Instant.now());
    }

    /** Some do portal e desmarca a sala — a nossa, pelo id que guardamos. */
    @DeleteMapping("/aulas/{aula}")
    public AulasServico.Removida removerAula(@AuthenticationPrincipal Identidade ident, @PathVariable String aula) {
        return aulas.remover(ident, aula);
    }

    /** O link de iniciar, buscado na hora: o do Zoom expira em duas horas. */
    @PostMapping("/aulas/{aula}/iniciar")
    public AulasServico.LinkDoProfessor iniciarAula(@AuthenticationPrincipal Identidade ident, @PathVariable String aula) {
        return aulas.linkDoProfessor(ident, aula);
    }
}
