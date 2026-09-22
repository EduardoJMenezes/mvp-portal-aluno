package br.com.plataforma.taxonomia;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.acervo.Video;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.Referencias;
import br.com.plataforma.comum.RegraDeNegocio;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assuntos, sub-assuntos e a etiqueta que liga um vídeo a eles. */
@Service
public class TaxonomiaServico {

    private final AssuntoRepositorio assuntos;
    private final SubAssuntoRepositorio subassuntos;
    private final VideoAssuntoRepositorio vinculos;
    private final AcervoServico acervo;

    public TaxonomiaServico(AssuntoRepositorio assuntos, SubAssuntoRepositorio subassuntos,
            VideoAssuntoRepositorio vinculos, AcervoServico acervo) {
        this.assuntos = assuntos;
        this.subassuntos = subassuntos;
        this.vinculos = vinculos;
        this.acervo = acervo;
    }

    // --- leitura -------------------------------------------------------------

    public record SubAssuntoNaLista(Integer id, String nome) {}

    public record AssuntoNaLista(Integer id, String nome, List<SubAssuntoNaLista> subassuntos) {}

    @Transactional(readOnly = true)
    public List<AssuntoNaLista> listarAssuntos() {
        return assuntos.findAllByOrderByNomeAsc().stream()
                .map(a -> new AssuntoNaLista(a.getId(), a.getNome(),
                        subassuntos.findByAssuntoOrderByNomeAsc(a).stream()
                                .map(s -> new SubAssuntoNaLista(s.getId(), s.getNome()))
                                .toList()))
                .toList();
    }

    /** As etiquetas do vídeo, sem as de assunto removido. */
    @Transactional(readOnly = true)
    public List<Etiqueta> etiquetasDoVideo(Video video) {
        return vinculos.vivosDo(video).stream().map(Etiqueta::de).toList();
    }

    /**
     * Vídeos etiquetados com este assunto — o outro lado do erro do aluno.
     *
     * <p>Busca no acervo inteiro, sem olhar turma: quem decide o que ele pode assistir é o
     * controle de acesso, e o que não pode aparece bloqueado. Filtrar aqui esconderia justamente
     * o vídeo que explica a dúvida.
     *
     * <p>Sub-assunto primeiro, porque é a recomendação que acerta o alvo; o assunto completa a
     * lista quando falta material fino.
     */
    @Transactional(readOnly = true)
    public List<Video> videosQueExplicam(Integer assuntoId, Integer subassuntoId, int limite) {
        var achados = new java.util.LinkedHashMap<Integer, Video>();
        if (subassuntoId != null) {
            vinculos.videosDoSubassunto(subassuntoId, org.springframework.data.domain.Limit.of(limite))
                    .forEach(v -> achados.putIfAbsent(v.getId(), v));
        }
        if (achados.size() < limite) {
            vinculos.videosDoAssunto(assuntoId, org.springframework.data.domain.Limit.of(limite))
                    .forEach(v -> achados.putIfAbsent(v.getId(), v));
        }
        return achados.values().stream().limit(limite).toList();
    }

    /** O rótulo que o aluno lê: o sub-assunto, se existir; senão o assunto. */
    @Transactional(readOnly = true)
    public String rotuloDa(Integer assuntoId, Integer subassuntoId) {
        if (subassuntoId != null) {
            var sub = subassuntos.findById(subassuntoId);
            if (sub.isPresent()) {
                return sub.get().getNome();
            }
        }
        return assuntos.findById(assuntoId).map(Assunto::getNome).orElse("(sem assunto)");
    }

    // --- resolução -----------------------------------------------------------

    @Transactional(readOnly = true)
    public Assunto resolverAssunto(String referencia) {
        var todos = assuntos.findAllByOrderByNomeAsc();
        var busca = Referencias.buscar(todos, referencia);
        if (busca.achou()) {
            return busca.achado();
        }
        if (busca.ambiguos().size() > 1) {
            throw new RegraDeNegocio("'%s' casa com mais de um assunto: %s. Diga qual."
                    .formatted(referencia, Referencias.nomes(busca.ambiguos())));
        }
        var nomes = todos.isEmpty() ? "nenhum cadastrado" : Referencias.nomes(todos);
        throw new NaoEncontrado(
                "Assunto '%s' não existe. Assuntos: %s. Para criar um novo, use criar_assunto."
                        .formatted(referencia, nomes));
    }

    /** Sem ramo de ambiguidade, de propósito: vários parciais caem no "não existe", como no Python. */
    @Transactional(readOnly = true)
    public SubAssunto resolverSubassunto(Assunto assunto, String referencia) {
        var todos = subassuntos.findByAssuntoOrderByNomeAsc(assunto);
        var busca = Referencias.buscar(todos, referencia);
        if (busca.achou()) {
            return busca.achado();
        }
        var nomes = todos.isEmpty() ? "nenhum cadastrado" : Referencias.nomes(todos);
        throw new NaoEncontrado("Sub-assunto '%s' não existe em '%s'. Há: %s."
                .formatted(referencia, assunto.getNome(), nomes));
    }

    // --- escrita -------------------------------------------------------------

    /** Repetir não duplica: se o nome já existe, devolve o que está lá. */
    @Transactional
    public Assunto criarAssunto(Identidade ident, String nome) {
        ident.exigirOperador();
        var limpo = exigirNome(nome, "O assunto precisa de um nome, ex.: 'Estequiometria'.");
        return assuntos.findFirstByNomeIgnoreCase(limpo).orElseGet(() -> {
            var assunto = new Assunto(limpo);
            assunto.tocar(ident);
            return assuntos.save(assunto);
        });
    }

    @Transactional
    public SubAssunto criarSubassunto(Identidade ident, Assunto assunto, String nome) {
        ident.exigirOperador();
        var limpo = exigirNome(nome, "O sub-assunto precisa de um nome, ex.: 'Pureza e rendimento'.");
        return subassuntos.findFirstByAssuntoAndNomeIgnoreCase(assunto, limpo).orElseGet(() -> {
            var sub = new SubAssunto(assunto, limpo);
            sub.tocar(ident);
            return subassuntos.save(sub);
        });
    }

    public record AssuntoEditado(Integer assuntoId, String assunto) {}

    @Transactional
    public AssuntoEditado editarAssunto(Identidade ident, String referencia, String nome) {
        ident.exigirOperador();
        var alvo = resolverAssunto(referencia);
        var novo = exigirNome(nome, "O nome do assunto não pode ficar vazio.");
        var outro = assuntos.findFirstByNomeIgnoreCase(novo);
        if (outro.isPresent() && !outro.get().getId().equals(alvo.getId())) {
            throw new RegraDeNegocio("Já existe o assunto '%s'.".formatted(outro.get().getNome()));
        }
        alvo.renomear(novo);
        alvo.tocar(ident);
        return new AssuntoEditado(alvo.getId(), alvo.getNome());
    }

    public record AssuntoRemovido(String assunto, boolean reversivel) {}

    /** Remoção lógica: vídeos e questões perdem a etiqueta até ela ser restaurada. */
    @Transactional
    public AssuntoRemovido excluirAssunto(Identidade ident, String referencia) {
        ident.exigirOperador();
        var alvo = resolverAssunto(referencia);
        alvo.remover(ident);
        return new AssuntoRemovido(alvo.getNome(), true);
    }

    public record SubAssuntoEditado(String assunto, Integer subassuntoId, String subassunto) {}

    @Transactional
    public SubAssuntoEditado editarSubassunto(Identidade ident, String assunto, String subassunto, String nome) {
        ident.exigirOperador();
        var pai = resolverAssunto(assunto);
        var alvo = resolverSubassunto(pai, subassunto);
        var novo = exigirNome(nome, "O nome do sub-assunto não pode ficar vazio.");
        var outro = subassuntos.findFirstByAssuntoAndNomeIgnoreCase(pai, novo);
        if (outro.isPresent() && !outro.get().getId().equals(alvo.getId())) {
            throw new RegraDeNegocio("'%s' já tem o sub-assunto '%s'.".formatted(pai.getNome(), outro.get().getNome()));
        }
        alvo.renomear(novo);
        alvo.tocar(ident);
        return new SubAssuntoEditado(pai.getNome(), alvo.getId(), alvo.getNome());
    }

    public record SubAssuntoRemovido(String assunto, String subassunto, boolean reversivel) {}

    @Transactional
    public SubAssuntoRemovido excluirSubassunto(Identidade ident, String assunto, String subassunto) {
        ident.exigirOperador();
        var pai = resolverAssunto(assunto);
        var alvo = resolverSubassunto(pai, subassunto);
        alvo.remover(ident);
        return new SubAssuntoRemovido(pai.getNome(), alvo.getNome(), true);
    }

    /**
     * Etiqueta o vídeo. A etiqueta vai no <b>vídeo</b>, não no item: o mesmo vídeo em 2026 e 2027
     * ensina a mesma coisa, então classificar uma vez basta.
     */
    @Transactional
    public VideoAssunto classificarVideo(
            Identidade ident, Video video, Assunto assunto, SubAssunto subassunto) {
        ident.exigirOperador();

        var existente = subassunto == null
                ? vinculos.findFirstByVideoAndAssuntoAndSubassuntoIsNull(video, assunto)
                : vinculos.findFirstByVideoAndAssuntoAndSubassunto(video, assunto, subassunto);
        if (existente.isPresent()) {
            return existente.get();
        }

        var vinculo = vinculos.save(new VideoAssunto(video, assunto, subassunto));
        acervo.tocar(ident, video);
        return vinculo;
    }

    private static String exigirNome(String nome, String recado) {
        var limpo = nome == null ? "" : nome.strip();
        if (limpo.isEmpty()) {
            throw new RegraDeNegocio(recado);
        }
        return limpo;
    }
}
