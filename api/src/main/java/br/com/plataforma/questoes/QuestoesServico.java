package br.com.plataforma.questoes;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.acervo.Video;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.simulados.Situacao;
import br.com.plataforma.taxonomia.Assunto;
import br.com.plataforma.taxonomia.Etiqueta;
import br.com.plataforma.taxonomia.SubAssunto;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** O acervo de questões de simulado. */
@Service
public class QuestoesServico {

    private final QuestaoRepositorio questoes;
    private final QuestaoAssuntoRepositorio classificacoes;
    private final FigurasServico figuras;
    private final TaxonomiaServico taxonomia;
    private final SimuladosServico simulados;
    private final AcervoServico acervo;

    @PersistenceContext
    private EntityManager em;

    public QuestoesServico(QuestaoRepositorio questoes, QuestaoAssuntoRepositorio classificacoes,
            FigurasServico figuras, TaxonomiaServico taxonomia, SimuladosServico simulados,
            AcervoServico acervo) {
        this.questoes = questoes;
        this.classificacoes = classificacoes;
        this.figuras = figuras;
        this.taxonomia = taxonomia;
        this.simulados = simulados;
        this.acervo = acervo;
    }

    // --- o que as respostas mostram ------------------------------------------

    public record QuestaoDescrita(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Dificuldade dificuldade, Status status, List<Etiqueta> assuntos,
            List<Etiqueta> classificacao, boolean imagemPendente, Integer videoResolucaoId,
            Letra gabarito) {}

    public record FiguraNaQuestao(Integer figuraId, String parte) {}

    public record ResolucaoEmVideo(String vimeoId, String titulo) {}

    public record SimuladoDaQuestao(Integer simuladoId, String titulo, Situacao situacao) {}

    public record QuestaoDetalhada(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Dificuldade dificuldade, Status status, List<Etiqueta> assuntos,
            List<Etiqueta> classificacao, boolean imagemPendente, Integer videoResolucaoId,
            Letra gabarito, String resolucaoComentada, List<FiguraNaQuestao> figuras,
            ResolucaoEmVideo resolucao, List<SimuladoDaQuestao> simulados) {}

    @Transactional(readOnly = true)
    public QuestaoDescrita descrever(Questao q, boolean incluirGabarito) {
        var alternativas = new LinkedHashMap<Letra, String>();
        q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));

        return new QuestaoDescrita(
                q.getId(), q.getEnunciado(), alternativas, q.getDificuldade(), q.getStatus(),
                q.getVideo() == null ? List.of() : taxonomia.etiquetasDoVideo(q.getVideo()),
                etiquetasDa(q), q.isImagemPendente(),
                q.getVideo() == null ? null : q.getVideo().getId(),
                incluirGabarito ? q.getGabarito() : null);
    }

    /** A questão inteira, e em que simulados ela está — o "antes" do preview. */
    @Transactional(readOnly = true)
    public QuestaoDetalhada detalhar(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        var q = resolver(referencia);
        var base = descrever(q, true);

        return new QuestaoDetalhada(
                base.questaoId(), base.enunciado(), base.alternativas(), base.dificuldade(),
                base.status(), base.assuntos(), base.classificacao(), base.imagemPendente(),
                base.videoResolucaoId(), base.gabarito(), q.getResolucaoComentada(),
                figuras.daQuestao(q).stream()
                        .map(f -> new FiguraNaQuestao(f.getId(), f.getParte()))
                        .toList(),
                q.getVideo() == null
                        ? null
                        : new ResolucaoEmVideo(q.getVideo().getVimeoId(), q.getVideo().getTitulo()),
                simulados.simuladosDa(q).stream()
                        .map(s -> new SimuladoDaQuestao(
                                s.getId(), s.getTitulo(), SimuladosServico.situacao(s, agora)))
                        .toList());
    }

    // --- busca ---------------------------------------------------------------

    /**
     * Questões do acervo. Não filtra por turma: questão não pertence a turma nenhuma — o que
     * pertence é o simulado onde ela entra.
     */
    @Transactional(readOnly = true)
    public List<QuestaoDescrita> buscar(Identidade ident, String assunto, String statusTexto,
            String dificuldadeTexto, String busca, int limite, int deslocamento) {
        ident.exigirOperador();
        var status = vazio(statusTexto) ? null : status(statusTexto);
        var dificuldade = vazio(dificuldadeTexto) ? null : dificuldade(dificuldadeTexto);

        var jpql = new StringBuilder("select q from Questao q where 1 = 1");
        var parametros = new LinkedHashMap<String, Object>();

        if (assunto != null && !assunto.isBlank()) {
            jpql.append(" and exists (select 1 from QuestaoAssunto qa"
                    + " where qa.questao = q and qa.assunto = :assunto)");
            parametros.put("assunto", taxonomia.resolverAssunto(assunto));
        }
        if (status != null) {
            jpql.append(" and q.status = :status");
            parametros.put("status", status);
        }
        if (dificuldade != null) {
            jpql.append(" and q.dificuldade = :dificuldade");
            parametros.put("dificuldade", dificuldade);
        }
        if (busca != null && !busca.isBlank()) {
            // O texto digitado é literal: % e _ não viram curinga.
            var literal = busca.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            jpql.append(" and lower(q.enunciado) like lower(:busca) escape '\\'");
            parametros.put("busca", "%" + literal + "%");
        }
        jpql.append(" order by q.id");

        var consulta = em.createQuery(jpql.toString(), Questao.class);
        parametros.forEach(consulta::setParameter);

        return consulta.setFirstResult(Math.max(0, deslocamento)).setMaxResults(limite)
                .getResultList().stream()
                .map(q -> descrever(q, true))
                .toList();
    }

    // --- resolução -----------------------------------------------------------

    @Transactional(readOnly = true)
    public Questao resolver(String referencia) {
        var texto = referencia == null ? "" : referencia.strip();
        if (texto.length() <= 9 && !texto.isEmpty() && texto.chars().allMatch(Character::isDigit)) {
            var achada = questoes.findById(Integer.parseInt(texto));
            if (achada.isPresent()) {
                return achada.get();
            }
        }
        throw new NaoEncontrado(
                "Questão %s não existe no acervo. Use buscar_questoes para achar o id."
                        .formatted(referencia));
    }

    // --- escrita -------------------------------------------------------------

    /** O que `editar_questao` pode mudar. Nulo em um campo significa "não mexa nele". */
    public record Alteracao(
            String enunciado, Map<String, String> alternativas, String gabarito, String dificuldade,
            Boolean imagemPendente, String assunto, String subassunto, DadosDoVideo resolucao,
            String resolucaoComentada) {}

    public record DadosDoVideo(String vimeoId, String titulo, String url, String embedUrl,
            String thumbnailUrl, Integer duracaoSegundos, String pasta) {}

    @Transactional
    public Questao editar(Identidade ident, String referencia, Alteracao nova, Instant agora) {
        ident.exigirOperador();
        var q = resolver(referencia);

        var mexeNoQueOAlunoJaViu = nova.enunciado() != null || nova.alternativas() != null
                || nova.gabarito() != null || nova.imagemPendente() != null;
        if (mexeNoQueOAlunoJaViu) {
            simulados.exigirProvaFechadaParaMudancas(q, agora);
        }

        if (nova.enunciado() != null) {
            if (nova.enunciado().isBlank()) {
                throw new RegraDeNegocio("Enunciado vazio.");
            }
            q.mudarEnunciado(nova.enunciado().strip());
        }

        if (nova.gabarito() != null) {
            q.mudarGabarito(letra(nova.gabarito()));
        }

        if (nova.alternativas() != null) {
            // Parcial: o que não veio fica como está.
            var juntas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> juntas.put(a.getLetra(), a.getTexto()));
            nova.alternativas().forEach((k, v) -> juntas.put(letra(k), v == null ? "" : v.strip()));
            exigirCincoAlternativas(juntas);
            q.ajustarAlternativas(juntas);
        }

        if (nova.dificuldade() != null) {
            q.mudarDificuldade(dificuldade(nova.dificuldade()));
        }

        if (nova.imagemPendente() != null) {
            q.marcarImagemPendente(nova.imagemPendente());
        }

        if (nova.assunto() != null) {
            // Vínculo é ligação: trocar a etiqueta não deixa entulho.
            q.getAssuntos().clear();
            em.flush();
            if (!nova.assunto().isBlank()) {
                classificar(ident, q, taxonomia.resolverAssunto(nova.assunto()),
                        nova.subassunto() == null ? null : nova.subassunto());
            }
        } else if (nova.subassunto() != null) {
            throw new RegraDeNegocio("Informe o assunto junto do sub-assunto.");
        }

        if (nova.resolucao() != null) {
            q.mudarVideo(videoDe(ident, nova.resolucao()));
        }

        if (nova.resolucaoComentada() != null) {
            var texto = nova.resolucaoComentada().strip();
            q.mudarResolucaoComentada(texto.isEmpty() ? null : texto);
        }

        q.tocar(ident);
        return questoes.save(q);
    }

    public record QuestaoRemovida(Integer questaoId, String enunciado, boolean reversivel) {}

    /**
     * Remoção lógica: a questão sai do acervo, e as provas que já a usaram, não.
     *
     * <p>Com simulado ainda por acontecer, não remove — a prova ficaria com um buraco.
     */
    @Transactional
    public QuestaoRemovida remover(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        var q = resolver(referencia);
        simulados.exigirNenhumaProvaPendente(q, agora);

        q.remover(ident);
        questoes.save(q);
        return new QuestaoRemovida(q.getId(), resumo(q.getEnunciado(), 80), true);
    }

    // --- questão nova, em rascunho -------------------------------------------

    /** O que o Claude transcreve de um print ou de um PDF. */
    public record DadosDaQuestaoNova(
            String enunciado, Map<String, String> alternativas, String gabarito, String assunto,
            String subassunto, String dificuldade, DadosDoVideo resolucao, Boolean imagemPendente,
            String resolucaoComentada, Integer numero) {}

    /**
     * Uma questão nova, em rascunho.
     *
     * <p>A figura que ainda não veio fica marcada no texto como {@code ![](figura:pendente)}, e a
     * marca já basta para a questão nascer pendente — ninguém precisa lembrar de avisar.
     */
    @Transactional
    public Questao criarNova(Identidade ident, Integer rascunhoId, DadosDaQuestaoNova dados,
            DadosDoVideo resolucaoDaPasta) {
        ident.exigirOperador();

        var enunciado = dados.enunciado() == null ? "" : dados.enunciado().strip();
        if (enunciado.isEmpty()) {
            throw new RegraDeNegocio("Enunciado vazio.");
        }

        var letras = new LinkedHashMap<Letra, String>();
        (dados.alternativas() == null ? Map.<String, String>of() : dados.alternativas())
                .forEach((k, v) -> letras.put(letra(k), v == null ? "" : v.strip()));
        exigirCincoAlternativas(letras);

        var escolhida = dados.resolucao() != null ? dados.resolucao() : resolucaoDaPasta;
        var video = escolhida == null ? null : videoDe(ident, escolhida);

        var comentada = dados.resolucaoComentada() == null ? "" : dados.resolucaoComentada().strip();
        var textos = new ArrayList<String>(letras.values());
        textos.add(enunciado);
        textos.add(comentada);
        var marcada = textos.stream().anyMatch(x -> x.contains("figura:pendente"));

        var questao = new Questao(enunciado, letra(dados.gabarito()),
                dificuldade(dados.dificuldade()), comentada.isEmpty() ? null : comentada,
                Status.RASCUNHO, rascunhoId, ident.usuarioId());
        questao.marcarImagemPendente(Boolean.TRUE.equals(dados.imagemPendente()) || marcada);
        questao.mudarVideo(video);
        questao.ajustarAlternativas(letras);
        questao.tocar(ident);
        var salva = questoes.save(questao);

        if (dados.assunto() != null && !dados.assunto().isBlank()) {
            classificar(ident, salva, taxonomia.resolverAssunto(dados.assunto()), dados.subassunto());
        }
        return salva;
    }

    /** Resolve uma questão do acervo pelo id, exigindo que esteja pronta para entrar numa prova. */
    @Transactional(readOnly = true)
    public Questao questaoPublicada(String referencia) {
        var disponiveis = em.createQuery(
                "select q from Questao q where q.status = :s order by q.id", Questao.class)
                .setParameter("s", Status.PUBLICADO).getResultList();

        var texto = referencia == null ? "" : referencia.strip();
        var achada = disponiveis.stream().filter(q -> String.valueOf(q.getId()).equals(texto)).findFirst();
        if (achada.isEmpty()) {
            var lista = disponiveis.isEmpty() ? "(nenhuma)" : disponiveis.stream()
                    .map(q -> "%d (%s…)".formatted(q.getId(), resumo(q.getEnunciado(), 30)))
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new RegraDeNegocio(
                    "Questão '%s' não está no acervo publicado. Disponíveis: %s."
                            .formatted(referencia, lista));
        }
        var questao = achada.get();
        if (questao.getAlternativas().size() < Letra.values().length) {
            throw new RegraDeNegocio(
                    ("A questão %s ainda está sem as alternativas A-E e não pode ir para um simulado.")
                            .formatted(referencia));
        }
        return questao;
    }

    /** Marca como publicada. Chamado pela publicação do rascunho, que é quem autoriza. */
    @Transactional
    public void publicar(Questao questao) {
        questao.publicar();
        questoes.save(questao);
    }

    public static String resumo(String texto, int tamanho) {
        return texto.length() > tamanho ? texto.substring(0, tamanho) : texto;
    }

    // --- figuras na questão ---------------------------------------------------

    /**
     * Os dois métodos abaixo existem para um caminho só: o recorte de print.
     *
     * <p>Por isso a regra do recorte mora neles. O professor ainda vai ver a questão inteira no
     * preview antes de aprovar — é isso que torna aceitável o Claude apontar um retângulo e o
     * servidor recortar sem ninguém conferir pixel a pixel. Em questão já publicada essa rede não
     * existe mais: o aluno já está lendo.
     */
    private static void exigirRascunho(Questao q) {
        if (q.getStatus() != Status.RASCUNHO) {
            throw new RegraDeNegocio(
                    "A questão %d já foi publicada: recorte de print só entra em questão de rascunho."
                            .formatted(q.getId()));
        }
    }

    public record FiguraNaQuestaoResposta(
            Integer questaoId, Integer figuraId, ParteDaQuestao parte, String tipo, int bytes,
            boolean imagemPendente) {}

    /**
     * Anexa uma figura à questão e a põe no texto.
     *
     * <p>Onde houver a marca {@code ![](figura:pendente)}, a primeira marca da parte recebe a
     * figura; numa alternativa, a da letra informada. Sem marca, ela entra no fim. Anexar tira a
     * pendência quando não sobra marca nenhuma.
     *
     * <p>A figura da prova trava quando o simulado abre; a da resolução, não.
     */
    @Transactional
    public FiguraNaQuestaoResposta anexarFigura(Identidade ident, String questaoRef, byte[] conteudo,
            String nome, ParteDaQuestao parte, String alternativa, Instant agora) {
        ident.exigirOperador();
        var q = resolver(questaoRef);
        exigirRascunho(q);
        if (parte != ParteDaQuestao.RESOLUCAO) {
            simulados.exigirProvaFechadaParaMudancas(q, agora);
        }

        var tipo = FigurasServico.tipoDaImagem(conteudo);
        var figuraId = figuras.guardar(conteudo, tipo, nome);
        var referencia = "figura:" + figuraId;

        switch (parte) {
            case RESOLUCAO -> {
                var atual = q.getResolucaoComentada() == null ? "" : q.getResolucaoComentada();
                q.mudarResolucaoComentada(atual.contains(FigurasServico.PENDENTE)
                        ? atual.replaceFirst(java.util.regex.Pattern.quote(FigurasServico.PENDENTE),
                                referencia)
                        : (atual + "\n\n![](" + referencia + ")").strip());
            }
            case ALTERNATIVA -> {
                var letra = alternativa == null ? "" : alternativa.strip().toUpperCase(Locale.ROOT);
                var alvo = q.getAlternativas().stream()
                        .filter(a -> a.getTexto().contains(FigurasServico.PENDENTE)
                                && (letra.isEmpty() || letra.equals(a.getLetra().name())))
                        .findFirst().orElse(null);
                if (alvo == null) {
                    var onde = letra.isEmpty()
                            ? "Nenhuma alternativa tem"
                            : "A alternativa %s não tem".formatted(letra);
                    throw new RegraDeNegocio(onde + " a marca ![](figura:pendente). Ponha a marca "
                            + "na alternativa certa (editar_questao) antes de anexar.");
                }
                alvo.mudarTexto(alvo.getTexto().replaceFirst(
                        java.util.regex.Pattern.quote(FigurasServico.PENDENTE), referencia));
            }
            case ENUNCIADO -> q.mudarEnunciado(
                    q.getEnunciado().contains(FigurasServico.PENDENTE)
                            ? q.getEnunciado().replaceFirst(
                                    java.util.regex.Pattern.quote(FigurasServico.PENDENTE), referencia)
                            : q.getEnunciado() + "\n\n![](" + referencia + ")");
        }

        figuras.exigir(figuraId).ligarA(q, parte.name());
        q.marcarImagemPendente(aindaTemMarca(q));
        q.tocar(ident);
        questoes.save(q);

        return new FiguraNaQuestaoResposta(q.getId(), figuraId, parte, tipo, conteudo.length,
                q.isImagemPendente());
    }

    /**
     * Troca o arquivo de uma figura sem mexer no texto.
     *
     * <p>{@code questaoRef} não é redundante: quem chama é um modelo que acabou de manusear vários
     * ids de uma vez. Sem conferir, um id trocado apagaria em silêncio a figura de outra questão —
     * e ninguém olharia, porque a questão errada não está na tela de quem pediu.
     */
    @Transactional
    public FiguraNaQuestaoResposta trocarFigura(
            Identidade ident, Integer figuraId, String questaoRef, byte[] conteudo, Instant agora) {
        ident.exigirOperador();
        var figura = figuras.exigir(figuraId);
        if (figura.getQuestao() == null) {
            throw new NaoEncontrado(
                    "A figura %d não existe em nenhuma questão.".formatted(figuraId));
        }
        var q = figura.getQuestao();
        if (!q.getId().equals(resolver(questaoRef).getId())) {
            throw new RegraDeNegocio(
                    "A figura %d não é da questão %s.".formatted(figuraId, questaoRef));
        }
        exigirRascunho(q);
        var parte = ParteDaQuestao.valueOf(figura.getParte());
        if (parte != ParteDaQuestao.RESOLUCAO) {
            simulados.exigirProvaFechadaParaMudancas(q, agora);
        }

        var tipo = FigurasServico.tipoDaImagem(conteudo);
        figuras.trocarBytes(figuraId, conteudo, tipo);
        q.tocar(ident);
        questoes.save(q);

        return new FiguraNaQuestaoResposta(q.getId(), figuraId, parte, tipo, conteudo.length,
                q.isImagemPendente());
    }

    private static boolean aindaTemMarca(Questao q) {
        if (q.getEnunciado().contains(FigurasServico.PENDENTE)) {
            return true;
        }
        if (q.getResolucaoComentada() != null
                && q.getResolucaoComentada().contains(FigurasServico.PENDENTE)) {
            return true;
        }
        return q.getAlternativas().stream()
                .anyMatch(a -> a.getTexto().contains(FigurasServico.PENDENTE));
    }

    // --- apoio ---------------------------------------------------------------

    public List<Etiqueta> etiquetasDa(Questao q) {
        return classificacoes.vivosDa(q).stream()
                .map(qa -> new Etiqueta(qa.getAssunto().getNome(),
                        qa.getSubassunto() == null ? null : qa.getSubassunto().getNome()))
                .toList();
    }

    /**
     * Etiqueta a questão. Assunto inexistente é erro, não criação silenciosa: um typo viraria um
     * assunto novo e a taxonomia apodreceria sozinha.
     */
    @Transactional
    public void classificar(Identidade ident, Questao q, Assunto assunto, String subassunto) {
        ident.exigirOperador();
        SubAssunto alvo = subassunto == null || subassunto.isBlank()
                ? null
                : taxonomia.resolverSubassunto(assunto, subassunto);

        var jaTem = classificacoes.vivosDa(q).stream().anyMatch(qa ->
                qa.getAssunto().getId().equals(assunto.getId())
                        && java.util.Objects.equals(
                                qa.getSubassunto() == null ? null : qa.getSubassunto().getId(),
                                alvo == null ? null : alvo.getId()));
        if (jaTem) {
            return;
        }
        q.getAssuntos().add(new QuestaoAssunto(q, assunto, alvo));
        q.tocar(ident);
    }

    Video videoDe(Identidade ident, DadosDoVideo dados) {
        if (dados.vimeoId() == null || dados.vimeoId().isBlank()) {
            return null;
        }
        var id = dados.vimeoId().strip();
        var titulo = dados.titulo() == null || dados.titulo().isBlank()
                ? "Vídeo " + id
                : dados.titulo().strip();
        return acervo.registrar(ident, id, titulo, dados.url(), dados.embedUrl(),
                dados.thumbnailUrl(), dados.duracaoSegundos(), dados.pasta());
    }

    static Letra letra(String texto) {
        try {
            return Letra.valueOf(String.valueOf(texto).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Gabarito '%s' inválido. Use uma letra de A a E.".formatted(texto));
        }
    }

    static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    static Status status(String texto) {
        try {
            return Status.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio(
                    "Status '%s' inválido. Use RASCUNHO ou PUBLICADO.".formatted(texto));
        }
    }

    static Dificuldade dificuldade(String texto) {
        if (texto == null || texto.isBlank()) {
            return Dificuldade.MEDIA;
        }
        try {
            return Dificuldade.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio(
                    "Dificuldade '%s' inválida. Use FACIL, MEDIA, DIFICIL.".formatted(texto));
        }
    }

    static void exigirCincoAlternativas(Map<Letra, String> alternativas) {
        var faltando = new ArrayList<String>();
        for (var letra : Letra.values()) {
            if (!alternativas.containsKey(letra)) {
                faltando.add(letra.name());
            }
        }
        if (!faltando.isEmpty()) {
            throw new RegraDeNegocio("Faltam as alternativas %s. A questão precisa de A a E."
                    .formatted(String.join(", ", faltando)));
        }
    }
}
