package br.com.plataforma.catalogo;

import br.com.plataforma.comum.Faixa;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.estrutura.Modulo;
import br.com.plataforma.estrutura.SubModulo;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.taxonomia.Assunto;
import br.com.plataforma.taxonomia.EtiquetaComId;
import br.com.plataforma.taxonomia.SubAssunto;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O assunto de cada linha do curso, como o professor o vê e o troca ao montar.
 *
 * <p>A linha <b>não guarda</b> assunto. Ela aponta para um conteúdo — a questão, o vídeo ou o PDF —,
 * e o assunto é dele: a questão o tem no cadastro, o vídeo e o PDF numa tabela de ligação. Trocar
 * "o assunto da linha" é trocar o do conteúdo, e vale em todo lugar onde ele aparece. Assim não há
 * duas informações para a mesma coisa.
 *
 * <p>Uma linha de vídeo com PDF anexado é do vídeo; o PDF só leva assunto quando a linha é dele.
 */
@Service
public class AssuntoDasLinhas {

    private final TaxonomiaServico taxonomia;
    private final QuestoesServico questoes;
    private final EstruturaServico estrutura;

    public AssuntoDasLinhas(TaxonomiaServico taxonomia, QuestoesServico questoes, EstruturaServico estrutura) {
        this.taxonomia = taxonomia;
        this.questoes = questoes;
        this.estrutura = estrutura;
    }

    // --- ler ---------------------------------------------------------------------

    /** A árvore com o assunto de cada linha: três consultas para o curso inteiro. */
    @Transactional(readOnly = true)
    public List<EstruturaServico.ModuloNaArvore> naArvore(List<EstruturaServico.ModuloNaArvore> arvore) {
        var linhas = arvore.stream().flatMap(m -> m.submodulos().stream()).flatMap(s -> s.itens().stream()).toList();
        var dasQuestoes = taxonomia.etiquetasDasQuestoes(linhas.stream()
                .filter(i -> i.questao() != null).map(i -> i.questao().questaoId()).distinct().toList());
        var dosVideos = taxonomia.etiquetasDosVideos(linhas.stream()
                .filter(i -> i.questao() == null && i.videoId() != null).map(EstruturaServico.ItemNaArvore::videoId).distinct().toList());
        var dosMateriais = taxonomia.etiquetasDosMateriais(linhas.stream()
                .filter(i -> i.questao() == null && i.videoId() == null && i.material() != null)
                .map(i -> i.material().materialId()).distinct().toList());

        return arvore.stream().map(m -> m.comLinhas(i -> i.comAssuntos(
                i.questao() != null ? dasQuestoes.getOrDefault(i.questao().questaoId(), List.of())
                        : i.videoId() != null ? dosVideos.getOrDefault(i.videoId(), List.of())
                        : i.material() != null ? dosMateriais.getOrDefault(i.material().materialId(), List.of())
                        : List.of()))).toList();
    }

    private List<EtiquetaComId> da(Item item) {
        if (item.getQuestao() != null) {
            return taxonomia.etiquetasDasQuestoes(List.of(item.getQuestao().getId())).getOrDefault(item.getQuestao().getId(), List.of());
        }
        if (item.getVideo() != null) {
            return taxonomia.etiquetasDosVideos(List.of(item.getVideo().getId())).getOrDefault(item.getVideo().getId(), List.of());
        }
        if (item.getMaterial() != null) {
            return taxonomia.etiquetasDosMateriais(List.of(item.getMaterial().getId())).getOrDefault(item.getMaterial().getId(), List.of());
        }
        return List.of();
    }

    // --- trocar ------------------------------------------------------------------

    public record AssuntoDaLinha(Integer itemId, String nome, List<EtiquetaComId> assuntos) {}

    /** O que o professor escolheu: {@code assunto} vazio tira o assunto. Aceita id ou nome. */
    private record Escolha(Assunto assunto, SubAssunto subassunto) {}

    private Escolha resolver(String assunto, String subassunto) {
        if (assunto == null || assunto.isBlank()) {
            if (subassunto != null && !subassunto.isBlank()) {
                throw new RegraDeNegocio("Informe o assunto junto do sub-assunto.");
            }
            return new Escolha(null, null);
        }
        var a = taxonomia.resolverAssunto(assunto);
        return new Escolha(a, subassunto == null || subassunto.isBlank() ? null : taxonomia.resolverSubassunto(a, subassunto));
    }

    private void aplicar(Identidade ident, Item item, Escolha escolha, Instant agora) {
        if (item.getQuestao() != null) {
            // É o mesmo campo do cadastro da questão: "" tira, e o sub-assunto vai pelo id.
            questoes.editar(ident, String.valueOf(item.getQuestao().getId()), new QuestoesServico.Alteracao(
                    null, null, null, null, null,
                    escolha.assunto() == null ? "" : String.valueOf(escolha.assunto().getId()),
                    escolha.subassunto() == null ? null : String.valueOf(escolha.subassunto().getId()),
                    null, null, null), agora);
        } else if (item.getVideo() != null) {
            taxonomia.classificarVideo(ident, item.getVideo(), escolha.assunto(), escolha.subassunto());
        } else if (item.getMaterial() != null) {
            taxonomia.classificarMaterial(ident, item.getMaterial(), escolha.assunto(), escolha.subassunto());
        } else {
            throw new RegraDeNegocio("Esta linha não tem conteúdo para receber um assunto.");
        }
    }

    /** O assunto de uma linha: troca o do conteúdo dela. */
    @Transactional
    public AssuntoDaLinha definir(Identidade ident, Item item, String assunto, String subassunto, Instant agora) {
        ident.exigirOperador();
        aplicar(ident, item, resolver(assunto, subassunto), agora);
        return new AssuntoDaLinha(item.getId(), item.getNome(), da(item));
    }

    public record EmLote(int classificadas, int puladas, String assunto, String subassunto) {}

    private EmLote emLote(Identidade ident, List<Item> linhas, String assunto, String subassunto, boolean soSemAssunto, Instant agora) {
        var escolha = resolver(assunto, subassunto);
        if (escolha.assunto() == null) {
            throw new RegraDeNegocio("Escolha o assunto.");
        }
        var feitas = new ArrayList<Item>();
        // O mesmo vídeo ou a mesma questão pode estar em duas linhas: classifica-se uma vez.
        var vistos = new java.util.HashSet<String>();
        for (var item : linhas) {
            var chave = item.getQuestao() != null ? "q" + item.getQuestao().getId()
                    : item.getVideo() != null ? "v" + item.getVideo().getId()
                    : item.getMaterial() != null ? "m" + item.getMaterial().getId() : null;
            if (chave == null || !vistos.add(chave) || (soSemAssunto && !da(item).isEmpty())) {
                continue;
            }
            aplicar(ident, item, escolha, agora);
            feitas.add(item);
        }
        return new EmLote(feitas.size(), linhas.size() - feitas.size(), escolha.assunto().getNome(),
                escolha.subassunto() == null ? null : escolha.subassunto().getNome());
    }

    /**
     * O sub-módulo de uma vez: todas as linhas, ou as de uma faixa ("Q01-Q03"). Com
     * {@code soSemAssunto}, quem já tem assunto fica como está.
     */
    @Transactional
    public EmLote noSubmodulo(Identidade ident, SubModulo sub, String assunto, String subassunto, String faixa,
            boolean soSemAssunto, Instant agora) {
        ident.exigirOperador();
        var linhas = estrutura.itensDo(sub);
        if (faixa != null && !faixa.isBlank()) {
            var numeros = Faixa.interpretar(faixa);
            linhas = linhas.stream().filter(i -> numeros.contains(Faixa.doNome(i.getNome()))).toList();
            if (linhas.isEmpty()) {
                throw new RegraDeNegocio("Nenhuma linha casou com a faixa informada.");
            }
        }
        return emLote(ident, linhas, assunto, subassunto, soSemAssunto, agora);
    }

    /** O módulo inteiro: é o "este capítulo é de tal assunto", para depois afinar linha a linha. */
    @Transactional
    public EmLote noModulo(Identidade ident, Modulo modulo, String assunto, String subassunto, boolean soSemAssunto, Instant agora) {
        ident.exigirOperador();
        var linhas = estrutura.submodulosDo(modulo).stream().flatMap(s -> estrutura.itensDo(s).stream()).filter(Objects::nonNull).toList();
        return emLote(ident, linhas, assunto, subassunto, soSemAssunto, agora);
    }
}
