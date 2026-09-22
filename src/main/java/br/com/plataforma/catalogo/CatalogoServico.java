package br.com.plataforma.catalogo;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.estrutura.EstruturaServico;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogoServico {

    private final TurmaRepositorio turmas;
    private final EstruturaServico estrutura;
    private final br.com.plataforma.contas.ContasServico contas;
    private final br.com.plataforma.acervo.AcervoServico acervo;
    private final br.com.plataforma.acervo.AcessoServico acesso;

    public CatalogoServico(TurmaRepositorio turmas, EstruturaServico estrutura,
            br.com.plataforma.contas.ContasServico contas, br.com.plataforma.acervo.AcervoServico acervo,
            br.com.plataforma.acervo.AcessoServico acesso) {
        this.turmas = turmas;
        this.estrutura = estrutura;
        this.contas = contas;
        this.acervo = acervo;
        this.acesso = acesso;
    }

    /** As turmas que a identidade enxerga: operador, todas; aluno, as dele. */
    @Transactional(readOnly = true)
    public List<Turma> turmasVisiveis(Identidade ident) {
        if (ident.eOperador()) {
            return turmas.findAllByOrderByAnoAscNomeAsc();
        }
        var ids = contas.turmasDoAluno(ident.usuarioId());
        return ids.isEmpty() ? List.of() : turmas.findByIdInOrderByAnoAscNomeAsc(ids);
    }

    /**
     * Acha a turma pelo id, pelo nome exato ou por um pedaço único do nome.
     *
     * <p>Aceita nome porque quem chama é um modelo repetindo o que o professor disse
     * ("Extensivo 2027"), não um sistema com ids. Quando não acha, lista as que existem: a
     * mensagem é o que o modelo lê para se corrigir.
     */
    @Transactional(readOnly = true)
    public Turma resolverTurma(String referencia) {
        var todas = turmas.findAllByOrderByNomeAsc();
        var texto = referencia == null ? "" : referencia.strip().toLowerCase(Locale.ROOT);

        if (!texto.isEmpty() && texto.length() <= 18 && texto.chars().allMatch(Character::isDigit)) {
            var id = Long.parseLong(texto);
            for (var t : todas) {
                if (t.getId() == id) {
                    return t;
                }
            }
        }

        var exatas = todas.stream().filter(t -> minusculo(t).equals(texto)).toList();
        if (!exatas.isEmpty()) {
            return exatas.getFirst();
        }

        var parciais = todas.stream().filter(t -> minusculo(t).contains(texto)).toList();
        if (parciais.size() == 1) {
            return parciais.getFirst();
        }
        if (parciais.size() > 1) {
            throw new NaoEncontrado(
                    "'%s' corresponde a mais de uma turma: %s.".formatted(referencia, nomes(parciais)));
        }

        var disponiveis = todas.isEmpty() ? "(nenhuma cadastrada)" : nomes(todas);
        throw new NaoEncontrado("Turma '%s' não existe. Turmas: %s.".formatted(referencia, disponiveis));
    }

    /** Várias turmas de uma vez, sem repetir: "Extensivo, Extensivo" é uma turma só. */
    @Transactional(readOnly = true)
    public List<Turma> resolverTurmas(List<String> referencias) {
        var achadas = new java.util.LinkedHashMap<Integer, Turma>();
        for (var ref : referencias) {
            var t = resolverTurma(ref);
            achadas.putIfAbsent(t.getId(), t);
        }
        return List.copyOf(achadas.values());
    }

    public record TurmaNaLista(
            Integer id, String nome, Integer ano, int alunos, int modulos,
            int itensPublicados, Integer itensEmRascunho) {}

    @Transactional(readOnly = true)
    public List<TurmaNaLista> listarTurmas(Identidade ident) {
        return turmasVisiveis(ident).stream()
                .map(t -> new TurmaNaLista(
                        t.getId(), t.getNome(), t.getAno(),
                        turmas.contarAlunos(t.getId()),
                        estrutura.contarModulos(t),
                        estrutura.contarItensDaTurma(t, Status.PUBLICADO),
                        ident.eOperador() ? estrutura.contarItensDaTurma(t, Status.RASCUNHO) : null))
                .toList();
    }

    /** A árvore módulo › sub-módulo › item, de uma turma ou de todas. */
    @Transactional(readOnly = true)
    public List<EstruturaServico.ModuloNaArvore> listarModulos(Identidade ident, String turma) {
        List<Turma> alvos;
        if (turma != null) {
            alvos = List.of(resolverTurma(turma));
            contas.exigirAcessoATurma(ident, alvos.getFirst());
        } else {
            alvos = turmasVisiveis(ident);
        }

        return alvos.stream()
                .flatMap(t -> estrutura.arvoreDaTurma(t, ident.eAluno()).stream())
                .toList();
    }

    // --- a tela do aluno -----------------------------------------------------

    public record ItemComVideo(
            Integer id, String nome, Integer ordem, Status status, Integer videoId,
            br.com.plataforma.acervo.AcessoServico.VideoDescrito video) {}

    public record SubModuloComVideos(
            Integer id, String nome, br.com.plataforma.estrutura.TipoSubModulo tipo, Integer ordem,
            List<ItemComVideo> itens) {}

    public record ModuloComVideos(
            Integer id, String nome, Integer ordem, String turma, List<SubModuloComVideos> submodulos) {}

    public record ConteudoDaTurma(String turma, Integer turmaId, List<ModuloComVideos> modulos) {}

    /**
     * O que a tela do aluno mostra: turma > módulo > sub-módulo > vídeos.
     *
     * <p>O vídeo passa pelo {@link br.com.plataforma.acervo.AcessoServico} antes de sair: o que o
     * aluno pode assistir vem com {@code embed_url}; o que não pode vem com nome e aviso, e nada
     * mais.
     */
    @Transactional(readOnly = true)
    public List<ConteudoDaTurma> conteudoDoAluno(Identidade ident, java.time.Instant agora) {
        var saida = new java.util.ArrayList<ConteudoDaTurma>();
        for (var turma : turmasVisiveis(ident)) {
            var modulos = estrutura.arvoreDaTurma(turma, ident.eAluno());
            if (modulos.isEmpty()) {
                continue;
            }
            var ids = modulos.stream().flatMap(m -> m.submodulos().stream())
                    .flatMap(s -> s.itens().stream()).map(EstruturaServico.ItemNaArvore::videoId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            var videos = acervo.porIds(ids);
            var liberados = acesso.videosLiberados(ident, ids, agora);

            saida.add(new ConteudoDaTurma(turma.getNome(), turma.getId(), modulos.stream()
                    .map(m -> new ModuloComVideos(m.id(), m.nome(), m.ordem(), m.turma(),
                            m.submodulos().stream().map(s -> new SubModuloComVideos(s.id(), s.nome(),
                                    s.tipo(), s.ordem(), s.itens().stream()
                                            .map(i -> new ItemComVideo(i.id(), i.nome(), i.ordem(),
                                                    i.status(), i.videoId(),
                                                    br.com.plataforma.acervo.AcessoServico.descrever(
                                                            videos.get(i.videoId()),
                                                            liberados.contains(i.videoId()))))
                                            .toList()))
                                    .toList()))
                    .toList()));
        }
        return saida;
    }

    // --- cadastro de turma ---------------------------------------------------

    public record TurmaCadastrada(Integer id, String nome, Integer ano) {}

    private String exigirNomeLivre(String nome, Integer exceto) {
        var limpo = nome == null ? "" : nome.strip();
        if (limpo.isEmpty()) {
            throw new RegraDeNegocio("A turma precisa de um nome, ex.: 'Extensivo 2027'.");
        }
        var outra = turmas.findFirstByNomeIgnoreCase(limpo);
        if (outra.isPresent() && (exceto == null || !outra.get().getId().equals(exceto))) {
            throw new RegraDeNegocio("Já existe uma turma chamada '%s'.".formatted(limpo));
        }
        return limpo;
    }

    @Transactional
    public TurmaCadastrada criarTurma(Identidade ident, String nome, Integer ano) {
        ident.exigirOperador();
        var t = new Turma(exigirNomeLivre(nome, null), ano);
        t.tocar(ident);
        t = turmas.save(t);
        return new TurmaCadastrada(t.getId(), t.getNome(), t.getAno());
    }

    /** Muda nome e ano. A turma é endereço de módulo, matrícula e simulado: todos a acompanham. */
    @Transactional
    public TurmaCadastrada editarTurma(Identidade ident, String referencia, String nome, Integer ano) {
        ident.exigirOperador();
        var t = resolverTurma(referencia);
        if (nome != null) {
            t.renomear(exigirNomeLivre(nome, t.getId()));
        }
        if (ano != null) {
            t.mudarAno(ano);
        }
        t.tocar(ident);
        return new TurmaCadastrada(t.getId(), t.getNome(), t.getAno());
    }

    private static String minusculo(Turma t) {
        return t.getNome().toLowerCase(Locale.ROOT);
    }

    private static String nomes(List<Turma> lista) {
        return lista.stream().map(Turma::getNome).collect(joining(", "));
    }
}
