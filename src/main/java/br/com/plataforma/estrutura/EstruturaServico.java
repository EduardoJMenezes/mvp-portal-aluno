package br.com.plataforma.estrutura;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.Referencias;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
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

    public EstruturaServico(
            ModuloRepositorio modulos, SubModuloRepositorio submodulos, ItemRepositorio itens) {
        this.modulos = modulos;
        this.submodulos = submodulos;
        this.itens = itens;
    }

    // --- resolução por nome --------------------------------------------------

    @Transactional(readOnly = true)
    public Modulo resolverModulo(Turma turma, String referencia) {
        return Referencias.porNome(
                modulos.findByTurmaOrderByOrdemAsc(turma), referencia, "módulo", "'" + turma.getNome() + "'");
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

    public record ItemNaArvore(
            Integer id, String nome, Integer ordem, Status status, Integer videoId,
            String vimeoId) {}

    public record SubModuloNaArvore(
            Integer id, String nome, TipoSubModulo tipo, Integer ordem, List<ItemNaArvore> itens) {}

    public record ModuloNaArvore(
            Integer id, String nome, Integer ordem, String turma, List<SubModuloNaArvore> submodulos) {}

    /**
     * Módulos › sub-módulos › itens de uma turma.
     *
     * <p>{@code apenasPublicados} é o que a tela do aluno pede: módulo e sub-módulo não têm status
     * próprio, então aparecem quando sobra item publicado dentro — e somem quando não sobra.
     */
    @Transactional(readOnly = true)
    public List<ModuloNaArvore> arvoreDaTurma(Turma turma, boolean apenasPublicados) {
        var arvore = new java.util.ArrayList<ModuloNaArvore>();

        for (var modulo : modulos.findByTurmaOrderByOrdemAsc(turma)) {
            var galhos = new java.util.ArrayList<SubModuloNaArvore>();

            for (var sub : submodulos.findByModuloOrderByOrdemAsc(modulo)) {
                var lista = apenasPublicados
                        ? itens.findBySubmoduloAndStatusOrderByOrdemAsc(sub, Status.PUBLICADO)
                        : itens.findBySubmoduloOrderByOrdemAsc(sub);
                if (apenasPublicados && lista.isEmpty()) {
                    continue;
                }
                galhos.add(new SubModuloNaArvore(sub.getId(), sub.getNome(), sub.getTipo(), sub.getOrdem(),
                        lista.stream()
                                .map(i -> new ItemNaArvore(
                                        i.getId(), i.getNome(), i.getOrdem(), i.getStatus(), i.getVideo().getId(),
                                        i.getVideo().getVimeoId()))
                                .toList()));
            }

            if (apenasPublicados && galhos.isEmpty()) {
                continue;
            }
            arvore.add(new ModuloNaArvore(
                    modulo.getId(), modulo.getNome(), modulo.getOrdem(), turma.getNome(), galhos));
        }
        return arvore;
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
        item.publicar();
        item.tocar(ident);
        itens.save(item);
    }

    @Transactional(readOnly = true)
    public int contarModulos(Turma turma) {
        return modulos.countByTurma(turma);
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

        modulos.findFirstByTurmaAndNomeIgnoreCase(turma, limpo).ifPresent(existente -> {
            throw new RegraDeNegocio(
                    "'%s' já tem um módulo chamado '%s'.".formatted(turma.getNome(), existente.getNome()));
        });

        var modulo = new Modulo(turma, limpo, ordem != null ? ordem : modulos.maiorOrdem(turma) + 1);
        modulo.tocar(ident);
        return modulos.save(modulo);
    }

    @Transactional
    public Modulo editarModulo(Identidade ident, Modulo modulo, String nome, Integer ordem) {
        ident.exigirOperador();
        if (nome != null) {
            modulo.renomear(exigirNome(nome, "O nome do módulo não pode ficar vazio."));
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
        return new ModuloRemovido(modulo.getNome(), modulo.getTurma().getNome(), publicados, true);
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
            submodulo.renomear(exigirNome(nome, "O nome do sub-módulo não pode ficar vazio."));
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
        itens.findFirstBySubmoduloAndVideo(destino, item.getVideo()).ifPresent(repetido -> {
            throw new RegraDeNegocio("'%s' já tem este vídeo, como '%s'."
                    .formatted(destino.getNome(), repetido.getNome()));
        });
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
