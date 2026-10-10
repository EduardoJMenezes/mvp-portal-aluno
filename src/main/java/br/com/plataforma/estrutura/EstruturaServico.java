package br.com.plataforma.estrutura;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Referencias;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.materiais.Material;
import br.com.plataforma.materiais.MaterialLigado;
import br.com.plataforma.questoes.Questao;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Módulos, sub-módulos e itens. Não conhece HTTP nem MCP: levanta erro de domínio, a borda traduz. */
@Service
public class EstruturaServico {

    /** Os dois de sempre, quando o professor não diz quais quer. */
    public static final List<String> SUBMODULOS_PADRAO = List.of("Aulas", "Questões da apostila");

    private final ModuloRepositorio modulos;
    private final SubModuloRepositorio submodulos;
    private final ItemRepositorio itens;
    private final br.com.plataforma.agenda.LiberacaoServico liberacao;

    public EstruturaServico(ModuloRepositorio modulos, SubModuloRepositorio submodulos, ItemRepositorio itens,
            br.com.plataforma.agenda.LiberacaoServico liberacao) {
        this.modulos = modulos;
        this.submodulos = submodulos;
        this.itens = itens;
        this.liberacao = liberacao;
    }

    // --- resolução por nome --------------------------------------------------

    /** Entre os módulos da turma; sem turma, na biblioteca inteira. */
    @Transactional(readOnly = true)
    public Modulo resolverModulo(Turma turma, String referencia) {
        return turma == null
                ? Referencias.porNome(modulos.findAllByOrderByOrdemAscIdAsc(), referencia, "módulo", "a biblioteca")
                : Referencias.porNome(modulos.daTurma(turma), referencia, "módulo", "'" + turma.getNome() + "'");
    }

    @Transactional(readOnly = true)
    public SubModulo resolverSubmodulo(Modulo modulo, String referencia) {
        return Referencias.porNome(
                submodulos.findByModuloOrderByOrdemAsc(modulo), referencia, "sub-módulo",
                "'" + modulo.getNome() + "'");
    }

    @Transactional(readOnly = true)
    public Item resolverItem(SubModulo submodulo, String referencia) {
        return Referencias.porNome(
                itens.findBySubmoduloOrderByOrdemAsc(submodulo), referencia, "item",
                "'" + submodulo.getNome() + "'");
    }

    /** O que as bordas pedem: o mais fundo que vier, resolvido de cima para baixo. */
    /** Item pelo id; removido é vazio. */
    @Transactional(readOnly = true)
    public java.util.Optional<Item> item(Integer id) {
        return id == null ? java.util.Optional.empty() : itens.findById(id);
    }

    /** Módulo pelo id; removido é vazio. */
    @Transactional(readOnly = true)
    public java.util.Optional<Modulo> modulo(Integer id) {
        return id == null ? java.util.Optional.empty() : modulos.findById(id);
    }

    /** Sub-módulo pelo id; removido é vazio. */
    @Transactional(readOnly = true)
    public java.util.Optional<SubModulo> submodulo(Integer id) {
        return id == null ? java.util.Optional.empty() : submodulos.findById(id);
    }

    public record Alvos(Turma turma, Modulo modulo, SubModulo submodulo, Item item) {}

    @Transactional(readOnly = true)
    public Alvos alvos(Turma turma, String modulo, String submodulo, String item) {
        var m = modulo == null ? null : resolverModulo(turma, modulo);
        var s = submodulo == null ? null : resolverSubmodulo(m, submodulo);
        var i = item == null ? null : resolverItem(s, item);
        return new Alvos(turma, m, s, i);
    }

    // --- leitura -------------------------------------------------------------

    /** A questão da linha, para quem monta: o começo do enunciado basta para reconhecer. */
    public record QuestaoDaLinha(Integer questaoId, String resumo, Status status) {

        static QuestaoDaLinha de(Questao q) {
            return q == null ? null : new QuestaoDaLinha(q.getId(),
                    q.getEnunciado().length() > 120 ? q.getEnunciado().substring(0, 120) + "…" : q.getEnunciado(),
                    q.getStatus());
        }
    }

    /**
     * {@code turmas}: vazia é "toda turma que tem o módulo"; com nomes, só elas. Sem vídeo, a linha é
     * só o PDF de {@code material} (decisão 0013) ou a {@code questao} que o aluno responde ali.
     */
    /**
     * {@code embedUrl} e {@code duracaoSegundos} são do vídeo da linha: é com eles que a tela de
     * montar o curso mostra a prévia, sem ir ao Vimeo. A árvore é só do professor.
     */
    public record ItemNaArvore(
            Integer id, String nome, Integer ordem, Status status, Integer videoId,
            String vimeoId, List<String> turmas, MaterialLigado material, QuestaoDaLinha questao,
            String embedUrl, Integer duracaoSegundos) {

        public static ItemNaArvore de(Item i) {
            var video = i.getVideo();
            return new ItemNaArvore(i.getId(), i.getNome(), i.getOrdem(), i.getStatus(),
                    video == null ? null : video.getId(),
                    video == null ? null : video.getVimeoId(), nomes(i.getTurmas()),
                    MaterialLigado.de(i.getMaterial()), QuestaoDaLinha.de(i.getQuestao()),
                    video == null ? null : video.getEmbedUrl(),
                    video == null ? null : video.getDuracaoSegundos());
        }

        /** A mesma linha sem o endereço do player: é assim que ela sai para quem não é o professor. */
        public ItemNaArvore semPrevia() {
            return new ItemNaArvore(id, nome, ordem, status, videoId, vimeoId, turmas, material, questao, null, null);
        }
    }

    public record SubModuloNaArvore(
            Integer id, String nome, TipoSubModulo tipo, Integer ordem, List<ItemNaArvore> itens) {}

    /**
     * {@code turma}: a turma pela qual se olha; {@code turmas}: todas as que recebem o módulo.
     * {@code icone} e {@code fotoVersao} são a capa do cartão: com foto, vale a foto; sem as duas, o
     * portal escolhe o ícone pelo nome.
     */
    public record ModuloNaArvore(
            Integer id, String nome, Integer ordem, String categoria, String turma, List<String> turmas,
            List<SubModuloNaArvore> submodulos, String icone, Long fotoVersao) {

        /** O módulo com as linhas sem o endereço do player (ver {@link ItemNaArvore#semPrevia()}). */
        public ModuloNaArvore semPrevia() {
            return new ModuloNaArvore(id, nome, ordem, categoria, turma, turmas, submodulos.stream()
                    .map(s -> new SubModuloNaArvore(s.id(), s.nome(), s.tipo(), s.ordem(),
                            s.itens().stream().map(ItemNaArvore::semPrevia).toList()))
                    .toList(), icone, fotoVersao);
        }
    }

    private static List<String> nomes(List<Turma> turmas) {
        return turmas.stream().map(Turma::getNome).toList();
    }

    /** Os sub-módulos de cada módulo e as linhas de cada sub-módulo, já na ordem da tela. */
    private record Ramos(java.util.Map<Integer, List<SubModulo>> submodulos, java.util.Map<Integer, List<Item>> itens) {

        List<SubModulo> de(Modulo modulo) {
            return submodulos.getOrDefault(modulo.getId(), List.of());
        }

        List<Item> de(SubModulo submodulo) {
            return itens.getOrDefault(submodulo.getId(), List.of());
        }
    }

    /**
     * Tudo o que está debaixo destes módulos, em duas idas ao banco. Uma consulta por módulo e
     * outra por sub-módulo fazia a árvore custar centenas de consultas — e ela é pedida por toda
     * aba aberta, de minuto em minuto.
     */
    private Ramos ramos(List<Modulo> daArvore) {
        var subs = daArvore.isEmpty() ? List.<SubModulo>of() : submodulos.dosModulos(daArvore);
        var linhas = subs.isEmpty() ? List.<Item>of() : itens.dosSubmodulos(subs);
        return new Ramos(
                subs.stream().collect(java.util.stream.Collectors.groupingBy(s -> s.getModulo().getId())),
                linhas.stream().collect(java.util.stream.Collectors.groupingBy(i -> i.getSubmodulo().getId())));
    }

    /**
     * Módulos › sub-módulos › itens como uma turma os enxerga: os módulos que ela recebe e os que
     * têm aula só dela (decisão 0011).
     *
     * <p>{@code doAluno}: só o que o aluno vê — item publicado, visível para a turma e já liberado
     * pela agenda até {@code agora} (decisão 0012). Módulo e
     * sub-módulo não têm status próprio, então aparecem quando sobra item dentro, a não ser que
     * quem chama peça {@code manterVazios} (a tela do curso, que mostra ali a aula ao vivo).
     * Sem {@code doAluno}, o professor vê todos os itens, com a restrição de cada um.
     */
    @Transactional(readOnly = true)
    public List<ModuloNaArvore> arvoreDaTurma(Turma turma, boolean doAluno, boolean manterVazios,
            java.time.Instant agora) {
        var arvore = new java.util.ArrayList<ModuloNaArvore>();
        var agenda = doAluno ? liberacao.das(List.of(turma.getId()))
                : br.com.plataforma.agenda.LiberacaoServico.Liberacoes.NENHUMA;

        var daTurma = modulos.daTurma(turma);
        var ramos = ramos(daTurma);
        for (var modulo : daTurma) {
            var galhos = new java.util.ArrayList<SubModuloNaArvore>();

            for (var sub : ramos.de(modulo)) {
                var lista = ramos.de(sub).stream()
                        .filter(i -> !doAluno || (i.getStatus() == Status.PUBLICADO && i.visivelPara(turma)
                                && agenda.liberado(turma.getId(), modulo.getId(), i.getId(), agora)))
                        // A linha só de PDF ou só de questão que perdeu o conteúdo não tem o que mostrar.
                        .filter(Item::temConteudo)
                        // Questão ainda em rascunho não chega ao aluno, mesmo com a linha publicada.
                        .filter(i -> !doAluno || i.getQuestao() == null
                                || i.getQuestao().getStatus() == Status.PUBLICADO)
                        .toList();
                if (doAluno && !manterVazios && lista.isEmpty()) {
                    continue;
                }
                galhos.add(new SubModuloNaArvore(sub.getId(), sub.getNome(), sub.getTipo(), sub.getOrdem(),
                        lista.stream().map(ItemNaArvore::de).toList()));
            }

            if (doAluno && !manterVazios && galhos.isEmpty()) {
                continue;
            }
            arvore.add(new ModuloNaArvore(modulo.getId(), modulo.getNome(), modulo.getOrdem(),
                    modulo.getCategoria(), turma.getNome(), nomes(modulo.getTurmas()), galhos,
                    modulo.getIcone(), modulo.getFotoVersao()));
        }
        return arvore;
    }

    /** A biblioteca inteira, com todos os itens: é a tela "Aulas" do professor. */
    @Transactional(readOnly = true)
    public List<ModuloNaArvore> arvoreDaBiblioteca() {
        var todos = modulos.findAllByOrderByOrdemAscIdAsc();
        var ramos = ramos(todos);
        return todos.stream()
                .map(modulo -> new ModuloNaArvore(modulo.getId(), modulo.getNome(), modulo.getOrdem(),
                        modulo.getCategoria(), null, nomes(modulo.getTurmas()),
                        ramos.de(modulo).stream()
                                .map(sub -> new SubModuloNaArvore(sub.getId(), sub.getNome(), sub.getTipo(),
                                        sub.getOrdem(), ramos.de(sub).stream()
                                                .map(ItemNaArvore::de).toList()))
                                .toList(),
                        modulo.getIcone(), modulo.getFotoVersao()))
                .toList();
    }

    /** {@code icone} e {@code fotoVersao}: a capa do cartão, como em {@link ModuloNaArvore}. */
    public record ModuloDaBiblioteca(
            Integer id, String nome, Integer ordem, String categoria, List<String> turmas, String icone, Long fotoVersao) {

        public static ModuloDaBiblioteca de(Modulo m) {
            return new ModuloDaBiblioteca(m.getId(), m.getNome(), m.getOrdem(), m.getCategoria(), nomes(m.getTurmas()),
                    m.getIcone(), m.getFotoVersao());
        }
    }

    /** Todos os módulos, de todas as turmas e de nenhuma: é daqui que se atribui. */
    @Transactional(readOnly = true)
    public List<ModuloDaBiblioteca> biblioteca() {
        return modulos.findAllByOrderByOrdemAscIdAsc().stream()
                .map(ModuloDaBiblioteca::de)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Item> itensDo(SubModulo submodulo) {
        return itens.findBySubmoduloOrderByOrdemAsc(submodulo);
    }

    /** Os itens que uma proposta trouxe. Item removido depois de proposto não volta pela publicação. */
    @Transactional(readOnly = true)
    public List<Item> itensDoRascunho(Integer rascunhoId, boolean apenasPendentes) {
        return apenasPendentes
                ? itens.findByRascunhoIdAndStatusOrderByOrdemAsc(rascunhoId, Status.RASCUNHO)
                : itens.findByRascunhoIdOrderByOrdemAsc(rascunhoId);
    }

    /** Publica o item. Quem confere a aprovação humana é a publicação do rascunho. */
    @Transactional
    public void publicarItem(Identidade ident, Item item) {
        var questao = item.getQuestao();
        if (questao != null && !questao.completa()) {
            throw new RegraDeNegocio("'%s' ainda está sem as alternativas A-D e não pode ir para a aula."
                    .formatted(item.getNome()));
        }
        if (questao != null && questao.isImagemPendente()) {
            throw new RegraDeNegocio("'%s' está com figura pendente: anexe a figura antes de pôr na aula."
                    .formatted(item.getNome()));
        }
        item.publicar();
        item.tocar(ident);
        itens.save(item);
    }

    @Transactional(readOnly = true)
    public int contarModulos(Turma turma) {
        return modulos.daTurma(turma).size();
    }

    @Transactional(readOnly = true)
    public int contarItensDaTurma(Turma turma, Status status) {
        return itens.contarNaTurma(turma, status);
    }

    // --- módulo --------------------------------------------------------------

    @Transactional
    public Modulo criarModulo(Identidade ident, Turma turma, String nome, Integer ordem) {
        ident.exigirOperador();
        var limpo = exigirNome(nome, "O módulo precisa de um nome, ex.: 'K01 - Introdução à química orgânica'.");
        if (turma != null) {
            exigirNomeLivre(turma, limpo, null);
        }

        // Nasce na biblioteca; com turma, já atribuído a ela.
        var modulo = new Modulo(limpo, ordem != null ? ordem : modulos.maiorOrdem() + 1);
        if (turma != null) {
            modulo.getTurmas().add(turma);
        }
        modulo.tocar(ident);
        return modulos.save(modulo);
    }

    // --- a capa do módulo: ícone ou foto -------------------------------------------------

    /** 1 MB sobra: o portal já manda a foto recortada em quadrado e reduzida. */
    private static final int LIMITE_DA_FOTO = 1024 * 1024;

    /**
     * O ícone do cartão. Escolher um ícone — ou "automatico" — é dizer que a capa não é mais a
     * foto: ela sai junto.
     */
    @Transactional
    public Modulo definirIcone(Identidade ident, Modulo modulo, String icone) {
        ident.exigirOperador();
        modulo.mudarIcone(IconeDoModulo.validar(icone));
        modulo.marcarFoto(null, null);
        modulo.tocar(ident);
        var salvo = modulos.saveAndFlush(modulo);
        modulos.apagarFoto(salvo.getId());
        return salvo;
    }

    /** A foto do cartão. O tipo sai dos bytes, não do nome do arquivo. */
    @Transactional
    public Modulo definirFoto(Identidade ident, Modulo modulo, byte[] conteudo, java.time.Instant agora) {
        ident.exigirOperador();
        var tipo = br.com.plataforma.questoes.FigurasServico.tipoDaImagem(conteudo);
        if (conteudo.length > LIMITE_DA_FOTO) {
            throw new RegraDeNegocio("A foto tem %d KB; o limite é %d KB. Envie uma imagem menor."
                    .formatted(conteudo.length / 1024, LIMITE_DA_FOTO / 1024));
        }
        modulo.marcarFoto(tipo, agora);
        modulo.tocar(ident);
        var salvo = modulos.saveAndFlush(modulo);
        modulos.gravarFoto(salvo.getId(), conteudo);
        return salvo;
    }

    public record Foto(String tipo, byte[] conteudo) {}

    /**
     * A foto do módulo, para quem pode ver o módulo: o professor, sempre; o aluno, só o da turma
     * dele. Para quem não pode, ela não existe.
     */
    @Transactional(readOnly = true)
    public Foto foto(Identidade ident, Integer moduloId) {
        var modulo = modulos.findById(moduloId).filter(m -> m.getFotoVersao() != null).orElse(null);
        var visivel = modulo != null && (ident.eOperador() || modulos.alcancadoPor(moduloId, ident.usuarioId()));
        if (!visivel) {
            throw new br.com.plataforma.comum.NaoEncontrado("Este módulo não tem foto.");
        }
        return new Foto(modulo.getFotoTipo(), modulos.lerFoto(moduloId));
    }

    /** Duas pastas com o mesmo nome na mesma turma confundiriam aluno e professor. */
    private void exigirNomeLivre(Turma turma, String nome, Integer exceto) {
        modulos.daTurma(turma).stream()
                .filter(m -> !m.getId().equals(exceto) && m.getNome().equalsIgnoreCase(nome))
                .findFirst()
                .ifPresent(existente -> {
                    throw new RegraDeNegocio(
                            "'%s' já tem um módulo chamado '%s'.".formatted(turma.getNome(), existente.getNome()));
                });
    }

    // --- atribuição às turmas (decisão 0011) ------------------------------------------

    /** Troca a lista inteira de turmas que recebem o módulo. Vazia deixa o módulo só na biblioteca. */
    @Transactional
    public Modulo atribuirModulo(Identidade ident, Modulo modulo, List<Turma> turmas) {
        ident.exigirOperador();
        var unicas = new java.util.LinkedHashMap<Integer, Turma>();
        turmas.forEach(t -> unicas.putIfAbsent(t.getId(), t));
        unicas.values().stream().filter(t -> !modulo.eDa(t))
                .forEach(t -> exigirNomeLivre(t, modulo.getNome(), modulo.getId()));
        modulo.getTurmas().clear();
        modulo.getTurmas().addAll(unicas.values());
        modulo.tocar(ident);
        return modulos.save(modulo);
    }

    /** Deixa a aula só para estas turmas. Lista vazia devolve a aula a toda turma do módulo. */
    @Transactional
    public Item restringirItem(Identidade ident, Item item, List<Turma> turmas) {
        ident.exigirOperador();
        var unicas = new java.util.LinkedHashMap<Integer, Turma>();
        turmas.forEach(t -> unicas.putIfAbsent(t.getId(), t));
        item.getTurmas().clear();
        item.getTurmas().addAll(unicas.values());
        item.tocar(ident);
        return itens.save(item);
    }

    public record ModulosCopiados(String de, String para, int modulos, int itens) {}

    /** A turma {@code para} passa a receber o que {@code de} recebe: módulos e aulas só dela. */
    @Transactional
    public ModulosCopiados copiarModulos(Identidade ident, Turma de, Turma para) {
        ident.exigirOperador();
        var novos = modulos.daTurma(de).stream().filter(m -> m.eDa(de) && !m.eDa(para)).toList();
        for (var m : novos) {
            exigirNomeLivre(para, m.getNome(), m.getId());
            m.getTurmas().add(para);
            m.tocar(ident);
        }
        var restritos = itens.restritosA(de).stream()
                .filter(i -> i.getTurmas().stream().noneMatch(t -> t.getId().equals(para.getId())))
                .toList();
        for (var i : restritos) {
            i.getTurmas().add(para);
            i.tocar(ident);
        }
        return new ModulosCopiados(de.getNome(), para.getNome(), novos.size(), restritos.size());
    }

    @Transactional
    public Modulo editarModulo(Identidade ident, Modulo modulo, String nome, Integer ordem, String categoria) {
        ident.exigirOperador();
        if (categoria != null) {
            modulo.mudarCategoria(br.com.plataforma.comum.Categoria.limpar(categoria));
        }
        if (nome != null) {
            var limpo = exigirNome(nome, "O nome do módulo não pode ficar vazio.");
            modulo.getTurmas().forEach(t -> exigirNomeLivre(t, limpo, modulo.getId()));
            modulo.renomear(limpo);
        }
        if (ordem != null) {
            modulo.reordenar(ordem);
        }
        modulo.tocar(ident);
        return modulos.save(modulo);
    }

    @Transactional
    public ModuloRemovido removerModulo(Identidade ident, Modulo modulo) {
        ident.exigirOperador();
        var publicados = itens.contarNoModulo(modulo, Status.PUBLICADO);
        modulo.remover(ident);
        modulos.save(modulo);
        return new ModuloRemovido(modulo.getNome(), String.join(", ", nomes(modulo.getTurmas())), publicados, true);
    }

    // --- sub-módulo ----------------------------------------------------------

    @Transactional
    public SubModulo criarSubmodulo(Identidade ident, Modulo modulo, String nome, Integer ordem) {
        ident.exigirOperador();
        var limpo = exigirNome(nome, "O sub-módulo precisa de um nome, ex.: 'Aulas' ou 'Questões da apostila'.");

        submodulos.findFirstByModuloAndNomeIgnoreCase(modulo, limpo).ifPresent(existente -> {
            throw new RegraDeNegocio("'%s' já tem um sub-módulo chamado '%s'."
                    .formatted(modulo.getNome(), existente.getNome()));
        });

        var sub = new SubModulo(modulo, limpo, TipoSubModulo.VIDEO,
                ordem != null ? ordem : submodulos.maiorOrdem(modulo) + 1);
        sub.tocar(ident);
        return submodulos.save(sub);
    }

    @Transactional
    public SubModulo editarSubmodulo(Identidade ident, SubModulo submodulo, String nome, Integer ordem) {
        ident.exigirOperador();
        if (nome != null) {
            var limpo = exigirNome(nome, "O nome do sub-módulo não pode ficar vazio.");
            submodulos.findFirstByModuloAndNomeIgnoreCase(submodulo.getModulo(), limpo)
                    .filter(outro -> !outro.getId().equals(submodulo.getId()))
                    .ifPresent(outro -> {
                        throw new RegraDeNegocio("'%s' já tem um sub-módulo chamado '%s'."
                                .formatted(submodulo.getModulo().getNome(), outro.getNome()));
                    });
            submodulo.renomear(limpo);
        }
        if (ordem != null) {
            submodulo.reordenar(ordem);
        }
        submodulo.tocar(ident);
        return submodulos.save(submodulo);
    }

    @Transactional
    public SubModuloRemovido removerSubmodulo(Identidade ident, SubModulo submodulo) {
        ident.exigirOperador();
        var publicados = itens.countBySubmoduloAndStatus(submodulo, Status.PUBLICADO);
        submodulo.remover(ident);
        submodulos.save(submodulo);
        return new SubModuloRemovido(
                submodulo.getNome(), submodulo.getModulo().getNome(), publicados, true);
    }

    // --- item ----------------------------------------------------------------

    /**
     * O sub-módulo já tem este vídeo?
     *
     * <p>Existe para a importação em lote perguntar <b>antes</b> de criar. Deixar
     * {@code criarItem} estourar e capturar o erro não serve no Spring: o método transacional
     * interno marca a transação como rollback-only, e o commit do lote falha inteiro depois —
     * ainda que ninguém mais reclame.
     */
    @Transactional(readOnly = true)
    public boolean jaTemEsteVideo(SubModulo submodulo, Video video) {
        return itens.findFirstBySubmoduloAndVideo(submodulo, video).isPresent();
    }

    @Transactional
    public Item criarItem(Identidade ident, SubModulo submodulo, Video video,
            String nome, Integer ordem, Status status, Integer rascunhoId) {
        ident.exigirOperador();

        itens.findFirstBySubmoduloAndVideo(submodulo, video).ifPresent(repetido -> {
            throw new RegraDeNegocio("'%s' já tem este vídeo, como '%s'."
                    .formatted(submodulo.getNome(), repetido.getNome()));
        });

        // Sem nome explícito vale o título do Vimeo — é o que o professor reconhece.
        var escolhido = nome == null || nome.isBlank() ? video.getTitulo() : nome.strip();
        var item = new Item(submodulo, video, escolhido,
                ordem != null ? ordem : itens.maiorOrdem(submodulo) + 1,
                status == null ? Status.RASCUNHO : status, rascunhoId);
        item.tocar(ident);
        return itens.save(item);
    }

    /**
     * Uma linha de questão no fim do sub-módulo. Sem nome, vira "Questão N" — a posição entre as
     * questões dali.
     */
    @Transactional
    public Item criarItemDeQuestao(Identidade ident, SubModulo submodulo, Questao questao, String nome,
            Status status, Integer rascunhoId) {
        ident.exigirOperador();
        itens.findFirstBySubmoduloAndQuestao(submodulo, questao).ifPresent(repetido -> {
            throw new RegraDeNegocio("'%s' já tem esta questão, como '%s'."
                    .formatted(submodulo.getNome(), repetido.getNome()));
        });
        var escolhido = nome == null || nome.isBlank()
                ? "Questão " + (itens.contarQuestoes(submodulo) + 1)
                : nome.strip();
        var item = Item.deQuestao(submodulo, questao, escolhido, itens.maiorOrdem(submodulo) + 1,
                status == null ? Status.RASCUNHO : status, rascunhoId);
        item.tocar(ident);
        return itens.save(item);
    }

    /** Onde cada questão está no curso: "K01 › Questões da apostila › Q04". Rascunho também conta. */
    @Transactional(readOnly = true)
    public java.util.Map<Integer, List<String>> aulasDasQuestoes(java.util.Collection<Integer> questoes) {
        var saida = new java.util.LinkedHashMap<Integer, List<String>>();
        if (questoes.isEmpty()) {
            return saida;
        }
        for (var i : itens.comAsQuestoes(questoes)) {
            var sub = i.getSubmodulo();
            if (sub == null || sub.getModulo() == null) {
                continue;
            }
            saida.computeIfAbsent(i.getQuestao().getId(), id -> new java.util.ArrayList<>())
                    .add("%s › %s › %s".formatted(sub.getModulo().getNome(), sub.getNome(), i.getNome()));
        }
        return saida;
    }

    /** Uma linha só de PDF no sub-módulo (decisão 0013). Quem cria é o professor no portal: nasce publicada. */
    @Transactional
    public Item criarItemPdf(Identidade ident, SubModulo submodulo, Material material, String nome) {
        ident.exigirOperador();
        var escolhido = nome == null || nome.isBlank() ? material.getTitulo() : nome.strip();
        var item = new Item(submodulo, null, escolhido, itens.maiorOrdem(submodulo) + 1, Status.PUBLICADO, null);
        item.anexarMaterial(material);
        item.tocar(ident);
        return itens.save(item);
    }

    /** O PDF da linha; {@code material} null tira — menos da linha que é só o PDF. */
    @Transactional
    public Item anexarMaterial(Identidade ident, Item item, Material material) {
        ident.exigirOperador();
        if (item.getQuestao() != null) {
            throw new RegraDeNegocio("'%s' é uma questão: o PDF vai numa linha só dele.".formatted(item.getNome()));
        }
        if (material == null && item.getVideo() == null) {
            throw new RegraDeNegocio("'%s' é só o PDF: para tirar, remova a linha.".formatted(item.getNome()));
        }
        item.anexarMaterial(material);
        item.tocar(ident);
        return itens.save(item);
    }

    @Transactional
    public Item editarItem(Identidade ident, Item item, String nome, Integer ordem) {
        ident.exigirOperador();
        if (nome != null) {
            item.renomear(exigirNome(nome, "O nome do item não pode ficar vazio."));
        }
        if (ordem != null) {
            item.reordenar(ordem);
        }
        item.tocar(ident);
        return itens.save(item);
    }

    /** Tira o item de um sub-módulo e põe em outro, no fim da lista. */
    @Transactional
    public Item moverItem(Identidade ident, Item item, SubModulo destino) {
        ident.exigirOperador();
        if (destino.getId().equals(item.getSubmodulo().getId())) {
            return item;
        }
        if (item.getVideo() != null) {
            itens.findFirstBySubmoduloAndVideo(destino, item.getVideo()).ifPresent(repetido -> {
                throw new RegraDeNegocio("'%s' já tem este vídeo, como '%s'."
                        .formatted(destino.getNome(), repetido.getNome()));
            });
        }
        if (item.getQuestao() != null) {
            itens.findFirstBySubmoduloAndQuestao(destino, item.getQuestao()).ifPresent(repetido -> {
                throw new RegraDeNegocio("'%s' já tem esta questão, como '%s'."
                        .formatted(destino.getNome(), repetido.getNome()));
            });
        }
        item.mudarDeSubmodulo(destino, itens.maiorOrdem(destino) + 1);
        item.tocar(ident);
        return itens.save(item);
    }

    @Transactional
    public ItemRemovido removerItem(Identidade ident, Item item) {
        ident.exigirOperador();
        var estavaPublicado = item.getStatus() == Status.PUBLICADO;
        var submodulo = item.getSubmodulo().getNome();
        item.remover(ident);
        itens.save(item);
        return new ItemRemovido(item.getNome(), submodulo, estavaPublicado, true);
    }

    // --- a ordem, de uma vez -------------------------------------------------

    /**
     * A fila como ficou na tela, numa transação só: é o que arrastar pede. Quem a lista não cita
     * fica na posição em que estava — a tela pode estar olhando por uma turma, que não vê tudo.
     */
    @Transactional
    public void ordenarModulos(Identidade ident, List<Integer> ids) {
        ident.exigirOperador();
        var fila = encaixar(modulos.findAllByOrderByOrdemAscIdAsc(), Modulo::getId, ids, "Módulo", "a biblioteca");
        for (int k = 0; k < fila.size(); k++) {
            var m = fila.get(k);
            if (m.getOrdem() != k + 1) {
                m.reordenar(k + 1);
                m.tocar(ident);
            }
        }
        modulos.saveAll(fila);
    }

    @Transactional
    public void ordenarSubmodulos(Identidade ident, Modulo modulo, List<Integer> ids) {
        ident.exigirOperador();
        var fila = encaixar(submodulos.findByModuloOrderByOrdemAsc(modulo), SubModulo::getId, ids, "Sub-módulo",
                "'" + modulo.getNome() + "'");
        for (int k = 0; k < fila.size(); k++) {
            var sub = fila.get(k);
            if (sub.getOrdem() != k + 1) {
                sub.reordenar(k + 1);
                sub.tocar(ident);
            }
        }
        submodulos.saveAll(fila);
    }

    @Transactional
    public void ordenarItens(Identidade ident, SubModulo submodulo, List<Integer> ids) {
        ident.exigirOperador();
        var fila = encaixar(itens.findBySubmoduloOrderByOrdemAsc(submodulo), Item::getId, ids, "Linha",
                "'" + submodulo.getNome() + "'");
        for (int k = 0; k < fila.size(); k++) {
            var item = fila.get(k);
            if (item.getOrdem() != k + 1) {
                item.reordenar(k + 1);
                item.tocar(ident);
            }
        }
        itens.saveAll(fila);
    }

    /** {@code atuais} com os citados em {@code ids} trocando de lugar entre si, na ordem pedida. */
    private static <T> List<T> encaixar(List<T> atuais, java.util.function.Function<T, Integer> id,
            List<Integer> ids, String oQue, String onde) {
        var porId = new java.util.HashMap<Integer, T>();
        atuais.forEach(a -> porId.put(id.apply(a), a));
        var citados = new java.util.LinkedHashSet<>(ids);
        if (citados.size() != ids.size()) {
            throw new RegraDeNegocio("A ordem cita o mesmo id duas vezes.");
        }
        for (var i : ids) {
            if (!porId.containsKey(i)) {
                throw new RegraDeNegocio("%s %d não está em %s: a tela pode estar desatualizada, recarregue."
                        .formatted(oQue, i, onde));
            }
        }
        var proximo = ids.iterator();
        return atuais.stream().map(a -> citados.contains(id.apply(a)) ? porId.get(proximo.next()) : a).toList();
    }

    // --- o que a remoção devolve ---------------------------------------------

    /** Três formatos, um tipo: a borda devolve o que foi removido, sem campo nulo sobrando. */
    public sealed interface Removido {}

    public record ModuloRemovido(
            String modulo, String turma, int itensPublicadosQueSomemDaTela, boolean reversivel)
            implements Removido {}

    public record SubModuloRemovido(
            String submodulo, String modulo, int itensPublicadosQueSomemDaTela, boolean reversivel)
            implements Removido {}

    public record ItemRemovido(
            String item, String submodulo, boolean sumiuDaTelaDoAluno, boolean reversivel)
            implements Removido {}

    private static String exigirNome(String nome, String recado) {
        var limpo = nome == null ? "" : nome.strip();
        if (limpo.isEmpty()) {
            throw new RegraDeNegocio(recado);
        }
        return limpo;
    }
}
