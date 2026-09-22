package br.com.plataforma.vimeo;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Faixa;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importação de uma pasta do Vimeo como capítulo da plataforma: ler a pasta, montar o plano
 * (número inferido do título, conflitos e avisos) e, só se o professor mandar, gravar como
 * rascunho. Publicar continua exigindo aprovação humana, que a integração não dispensa.
 */
@Service
public class ImportacaoVimeo {

    private final Vimeo vimeo;
    private final EstruturaServico estrutura;
    private final AcervoServico acervo;
    private final RascunhosServico rascunhos;

    public ImportacaoVimeo(Vimeo vimeo, EstruturaServico estrutura, AcervoServico acervo,
            RascunhosServico rascunhos) {
        this.vimeo = vimeo;
        this.estrutura = estrutura;
        this.acervo = acervo;
        this.rascunhos = rascunhos;
    }

    public boolean real() {
        return vimeo.real();
    }

    // --- o plano -------------------------------------------------------------

    /** Um vídeo do Vimeo, já com o número que ele teria na plataforma. */
    public record ItemDoPlano(String vimeoId, String titulo, Integer numero, String confianca,
            Integer duracaoSegundos, String url, String embedUrl, String thumbnailUrl, boolean publicavel,
            String privacidade, String transcricao, List<String> avisos) {

        public RascunhosServico.VideoParaImportar paraImportacao(String assunto, String subassunto) {
            return new RascunhosServico.VideoParaImportar(vimeoId, titulo, titulo, url, embedUrl,
                    thumbnailUrl, duracaoSegundos, null, assunto, subassunto);
        }

        public QuestoesServico.DadosDoVideo comoResolucao() {
            return new QuestoesServico.DadosDoVideo(vimeoId, titulo, url, embedUrl, thumbnailUrl,
                    duracaoSegundos, null);
        }

        public Resumo resumo() {
            return new Resumo(numero, titulo, vimeoId, duracaoSegundos, confianca, avisos);
        }
    }

    public record Resumo(Integer numero, String titulo, String vimeoId, Integer duracaoSegundos,
            String confiancaDoNumero, List<String> avisos) {}

    public record Plano(String pastaId, String pastaNome, List<ItemDoPlano> itens) {}

    private static List<String> avisosDoVideo(Vimeo.Video v, Integer numero) {
        var avisos = new ArrayList<String>();
        if (numero == null) {
            avisos.add("não consegui ler o número da questão no título");
        }
        if (!v.publicavel()) {
            avisos.add("ainda não está pronto no Vimeo (status %s)".formatted(v.status()));
        }
        if ("private".equals(v.privacidadeEmbed())) {
            avisos.add("o embed está desativado no Vimeo; o aluno não conseguiria assistir");
        }
        if ("whitelist".equals(v.privacidadeEmbed())) {
            avisos.add("o embed é restrito a domínios: confirme que o domínio do portal está liberado");
        }
        return avisos;
    }

    /** Lê a pasta no Vimeo e ordena pelo número do título (a API devolve fora de ordem). */
    public Plano lerPlano(String pastaId) {
        var pasta = vimeo.obterPasta(pastaId);
        var itens = new ArrayList<ItemDoPlano>();
        for (var v : vimeo.listarVideosDaPasta(pastaId)) {
            var inferido = Nomes.inferirNumero(v.titulo());
            itens.add(new ItemDoPlano(v.id() == null ? "" : v.id(), v.titulo() == null ? "(sem título)" : v.titulo(),
                    inferido.numero(), inferido.confianca(), v.duracaoSegundos(), v.url(), v.embedUrl(),
                    v.thumbnailUrl(), v.publicavel(), v.privacidadeView() + "/" + v.privacidadeEmbed(),
                    v.transcricaoStatus(), avisosDoVideo(v, inferido.numero())));
        }
        itens.sort(Comparator.comparing((ItemDoPlano i) -> i.numero() == null)
                .thenComparing(i -> i.numero() == null ? 0 : i.numero())
                .thenComparing(ItemDoPlano::titulo));
        return new Plano(String.valueOf(pastaId), pasta.nome(), itens);
    }

    public record PastaNaLista(String id, String nome, String dentroDe, Integer videos,
            Integer videosComSubpastas, boolean temSubpasta) {}

    public record Pastas(int totalNoVimeo, int mostrando, List<PastaNaLista> pastas) {}

    /** As pastas do Vimeo com a hierarquia — de onde sai o id que a importação pede. */
    public Pastas listarPastas(String busca, int limite) {
        var pastas = vimeo.listarPastas();
        var nomes = new LinkedHashMap<String, String>();
        pastas.forEach(p -> nomes.put(p.uri(), p.nome()));
        var termo = busca == null ? "" : busca.toLowerCase(Locale.ROOT);
        var filtradas = pastas.stream()
                .filter(p -> termo.isEmpty() || (p.nome() == null ? "" : p.nome().toLowerCase(Locale.ROOT)).contains(termo))
                .toList();
        return new Pastas(pastas.size(), Math.min(filtradas.size(), limite), filtradas.stream().limit(limite)
                .map(p -> new PastaNaLista(p.id(), p.nome(), p.paiUri() == null ? null : nomes.get(p.paiUri()),
                        p.totalVideos(), p.totalVideosComSubpastas(), p.temSubpasta()))
                .toList());
    }

    /**
     * O vídeo da resolução como o player precisa: com o {@code embed_url} do Vimeo. {@code null} é
     * "não mexa"; vazio é "sem resolução". Sem Vimeo real, segue só com o id.
     */
    public QuestoesServico.DadosDoVideo resolucao(String vimeoId) {
        if (vimeoId == null) {
            return null;
        }
        var id = vimeoId.strip();
        if (id.isEmpty() || !vimeo.real()) {
            return new QuestoesServico.DadosDoVideo(id, null, null, null, null, null, null);
        }
        try {
            var v = vimeo.obterVideo(id);
            return new QuestoesServico.DadosDoVideo(v.id() == null ? id : v.id(),
                    v.titulo() == null ? "Vídeo " + id : v.titulo(), v.url(), v.embedUrl(), v.thumbnailUrl(),
                    v.duracaoSegundos(), null);
        } catch (br.com.plataforma.comum.ServicoExterno | br.com.plataforma.comum.NaoEncontrado e) {
            throw new RegraDeNegocio("Vídeo %s do Vimeo: %s".formatted(id, e.getMessage()));
        }
    }

    /** Questão nova que cita {@code vimeo_id} ganha o vídeo inteiro, lido do Vimeo. */
    public List<Object> questoesComResolucao(List<Object> questoes) {
        if (questoes == null) {
            return null;
        }
        var saida = new ArrayList<Object>();
        for (var q : questoes) {
            if (q instanceof Map<?, ?> mapa && mapa.get("vimeo_id") != null
                    && !String.valueOf(mapa.get("vimeo_id")).isBlank()) {
                var copia = new LinkedHashMap<String, Object>();
                mapa.forEach((k, v) -> copia.put(String.valueOf(k), v));
                copia.put("resolucao", resolucao(String.valueOf(mapa.get("vimeo_id"))));
                saida.add(copia);
            } else {
                saida.add(q);
            }
        }
        return saida;
    }

    /** Os vídeos de resolução de uma pasta, pelo número lido do título: a questão 7 recebe o "Q07". */
    public static Map<String, Object> resolucoesPorNumero(Plano plano) {
        var saida = new LinkedHashMap<String, Object>();
        for (var item : plano.itens()) {
            if (item.numero() != null) {
                saida.putIfAbsent(String.valueOf(item.numero()), item.comoResolucao());
            }
        }
        return saida;
    }

    // --- distribuir ----------------------------------------------------------

    public record Destino(String faixa, String modulo, String submodulo, String assunto, String subassunto) {}

    public record Grupo(Destino destino, List<ItemDoPlano> itens, List<Integer> naoEncontrados) {}

    public record Distribuicao(List<Grupo> grupos, List<ItemDoPlano> semDestino) {}

    /**
     * Casa cada vídeo com o destino cuja faixa contém o número dele. Um destino sem faixa recolhe o
     * que sobrou; sem nenhum destino assim, o que sobra fica de fora e a tela pergunta em vez de
     * chutar.
     */
    public static Distribuicao distribuir(Plano plano, List<Destino> destinos) {
        if (destinos == null || destinos.isEmpty()) {
            throw new RegraDeNegocio("Informe ao menos um destino, ex.: faixa '1-14' para o módulo 'K01 - ...' "
                    + "e sub-módulo 'Questões da apostila'.");
        }
        var coringas = destinos.stream().filter(d -> d.faixa() == null || d.faixa().isBlank()).toList();
        if (coringas.size() > 1) {
            throw new RegraDeNegocio("Só um destino pode ficar sem faixa — ele recolhe o que sobrar.");
        }
        var usados = new HashSet<String>();
        var grupos = new ArrayList<Grupo>();
        for (var destino : destinos) {
            if (destino.faixa() == null || destino.faixa().isBlank()) {
                continue;
            }
            Set<Integer> numeros = Faixa.interpretar(destino.faixa());
            var escolhidos = plano.itens().stream()
                    .filter(i -> i.numero() != null && numeros.contains(i.numero()) && !usados.contains(i.vimeoId()))
                    .toList();
            escolhidos.forEach(i -> usados.add(i.vimeoId()));
            var achados = escolhidos.stream().map(ItemDoPlano::numero).collect(java.util.stream.Collectors.toSet());
            var faltando = numeros.stream().filter(n -> !achados.contains(n)).sorted().toList();
            grupos.add(new Grupo(destino, escolhidos, faltando));
        }
        var sobraram = plano.itens().stream().filter(i -> !usados.contains(i.vimeoId())).toList();
        if (!coringas.isEmpty()) {
            grupos.add(new Grupo(coringas.getFirst(), sobraram, List.of()));
            sobraram = List.of();
        }
        return new Distribuicao(grupos, sobraram);
    }

    // --- avaliar e aplicar ---------------------------------------------------

    public record PastaDoPlano(String id, String nome) {}

    public record DestinoAvaliado(String modulo, String submodulo, String faixa, String assunto,
            String subassunto, int itensQueSeraoCriados, List<String> jaNesteSubmodulo,
            List<Integer> numerosDaFaixaSemVideo, List<Resumo> itens) {}

    public record Avaliacao(PastaDoPlano pasta, String turma, int videosNaPasta, List<String> videosJaNoAcervo,
            List<DestinoAvaliado> destinos, List<Resumo> semDestino, String observacao) {}

    /** O que aconteceria se importássemos. Não grava nada. */
    @Transactional(readOnly = true)
    public Avaliacao avaliar(Identidade ident, Plano plano, Turma turma, List<Destino> destinos) {
        ident.exigirOperador();
        var d = distribuir(plano, destinos);
        var ids = plano.itens().stream().map(ItemDoPlano::vimeoId).filter(v -> !v.isEmpty()).toList();
        var jaNoAcervo = ids.isEmpty() ? List.<String>of() : acervo.quaisJaExistem(ids).stream().sorted().toList();

        var saida = new ArrayList<DestinoAvaliado>();
        for (var grupo : d.grupos()) {
            var destino = grupo.destino();
            var modulo = estrutura.resolverModulo(turma, destino.modulo());
            var sub = estrutura.resolverSubmodulo(modulo, destino.submodulo());
            var jaNoSub = estrutura.itensDo(sub).stream().map(i -> i.getVideo().getVimeoId()).collect(
                    java.util.stream.Collectors.toSet());
            saida.add(new DestinoAvaliado(modulo.getNome(), sub.getNome(),
                    destino.faixa() == null || destino.faixa().isBlank() ? "(o que sobrar)" : destino.faixa(),
                    destino.assunto(), destino.subassunto(),
                    (int) grupo.itens().stream().filter(i -> !jaNoSub.contains(i.vimeoId())).count(),
                    grupo.itens().stream().map(ItemDoPlano::vimeoId).filter(jaNoSub::contains).sorted().toList(),
                    grupo.naoEncontrados(), grupo.itens().stream().map(ItemDoPlano::resumo).toList()));
        }
        var observacao = "Nada foi gravado. Confirme com o professor e chame importar_pasta_vimeo_como_rascunho "
                + "para criar o rascunho."
                + (d.semDestino().isEmpty() ? "" : " Atenção: %d vídeo(s) ficaram sem destino — diga a faixa "
                        + "deles ou informe um destino sem faixa.".formatted(d.semDestino().size()));
        return new Avaliacao(new PastaDoPlano(plano.pastaId(), plano.pastaNome()), turma.getNome(),
                plano.itens().size(), jaNoAcervo, saida,
                d.semDestino().stream().map(ItemDoPlano::resumo).toList(), observacao);
    }

    public record Aplicacao(PastaDoPlano pastaVimeo, String turma,
            List<RascunhosServico.RascunhoDetalhado> rascunhos, List<Resumo> semDestino, String aviso) {}

    /** Grava o plano como RASCUNHO, um por destino. Continua sem publicar nada. */
    @Transactional
    public Aplicacao aplicar(Identidade ident, Plano plano, Turma turma, List<Destino> destinos, Instant agora) {
        ident.exigirOperador();
        if (plano.itens().isEmpty()) {
            throw new RegraDeNegocio("A pasta %s não tem vídeos para importar.".formatted(plano.pastaId()));
        }
        var d = distribuir(plano, destinos);
        var criados = new ArrayList<RascunhosServico.RascunhoDetalhado>();
        for (var grupo : d.grupos()) {
            if (grupo.itens().isEmpty()) {
                continue;
            }
            var destino = grupo.destino();
            var resultado = rascunhos.importarVideosComoItens(ident, turma, destino.modulo(), destino.submodulo(),
                    grupo.itens().stream().map(i -> i.paraImportacao(destino.assunto(), destino.subassunto())).toList());
            criados.add(rascunhos.detalhar(ident, resultado.rascunho().getId(), agora));
        }
        if (criados.isEmpty()) {
            throw new RegraDeNegocio("Nenhum vídeo casou com os destinos informados. Confira as faixas contra os "
                    + "números lidos dos títulos.");
        }
        return new Aplicacao(new PastaDoPlano(plano.pastaId(), plano.pastaNome()), turma.getNome(), criados,
                d.semDestino().stream().map(ItemDoPlano::resumo).toList(),
                "Nada foi publicado. Cada rascunho precisa da aprovação do professor."
                        + (d.semDestino().isEmpty() ? "" : " %d vídeo(s) ficaram de fora por não casarem com nenhuma faixa."
                                .formatted(d.semDestino().size())));
    }
}
