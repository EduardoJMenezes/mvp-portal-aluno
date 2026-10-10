package br.com.plataforma.questoes;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.acervo.Video;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.estrutura.EstruturaServico;
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

/** O acervo de questões: as do simulado e as que viram linha de aula. */
@Service
public class QuestoesServico {

    /** A busca que é só um número, com ou sem "#": o número da questão no banco. */
    private static final java.util.regex.Pattern NUMERO_DA_QUESTAO = java.util.regex.Pattern.compile("#?\\s*(\\d{1,9})");

    private final QuestaoRepositorio questoes;
    private final QuestaoAssuntoRepositorio classificacoes;
    private final FigurasServico figuras;
    private final TaxonomiaServico taxonomia;
    private final SimuladosServico simulados;
    private final AcervoServico acervo;
    private final AcessoServico acesso;
    private final EstruturaServico estrutura;

    @PersistenceContext
    private EntityManager em;

    public QuestoesServico(QuestaoRepositorio questoes, QuestaoAssuntoRepositorio classificacoes,
            FigurasServico figuras, TaxonomiaServico taxonomia, SimuladosServico simulados,
            AcervoServico acervo, AcessoServico acesso, EstruturaServico estrutura) {
        this.questoes = questoes;
        this.classificacoes = classificacoes;
        this.figuras = figuras;
        this.taxonomia = taxonomia;
        this.simulados = simulados;
        this.acervo = acervo;
        this.acesso = acesso;
        this.estrutura = estrutura;
    }

    // --- o que as respostas mostram ------------------------------------------

    /**
     * {@code aulas}: as linhas de aula em que a questão está ("K01 › Questões da apostila › Q04").
     * Lá o aluno vê o gabarito ao responder — quem monta uma prova com ela precisa saber.
     */
    public record QuestaoDescrita(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Dificuldade dificuldade, Status status, List<Etiqueta> assuntos,
            List<Etiqueta> classificacao, boolean imagemPendente, Integer videoResolucaoId,
            Letra gabarito, List<String> aulas) {}

    public record FiguraNaQuestao(Integer figuraId, String parte) {}

    public record ResolucaoEmVideo(String vimeoId, String titulo) {}

    public record SimuladoDaQuestao(Integer simuladoId, String titulo, Situacao situacao) {}

    public record QuestaoDetalhada(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas,
            Dificuldade dificuldade, Status status, List<Etiqueta> assuntos,
            List<Etiqueta> classificacao, boolean imagemPendente, Integer videoResolucaoId,
            Letra gabarito, String resolucaoComentada, List<FiguraNaQuestao> figuras,
            ResolucaoEmVideo resolucao, List<SimuladoDaQuestao> simulados, List<String> aulas,
            Map<Letra, String> comentarios, int respostas, List<Mudanca> historico) {}

    /** Uma correção feita na questão já publicada. {@code antes}: como o aluno a lia até ali. */
    public record Mudanca(String quando, String quem, String resumo, String antes) {}

    @Transactional(readOnly = true)
    public QuestaoDescrita descrever(Questao q, boolean incluirGabarito) {
        return descrever(q, incluirGabarito, estrutura.aulasDasQuestoes(List.of(q.getId())));
    }

    private QuestaoDescrita descrever(Questao q, boolean incluirGabarito, Map<Integer, List<String>> aulas) {
        var alternativas = new LinkedHashMap<Letra, String>();
        q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));

        return new QuestaoDescrita(
                q.getId(), q.getEnunciado(), alternativas, q.getDificuldade(), q.getStatus(),
                q.getVideo() == null ? List.of() : taxonomia.etiquetasDoVideo(q.getVideo()),
                etiquetasDa(q), q.isImagemPendente(),
                q.getVideo() == null ? null : q.getVideo().getId(),
                incluirGabarito ? q.getGabarito() : null,
                aulas.getOrDefault(q.getId(), List.of()));
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
                        .toList(),
                base.aulas(), q.comentarios(), respostasDadas(q), historico(q));
    }

    /** Quantas vezes a questão já foi respondida, em simulado e em aula. */
    private int respostasDadas(Questao q) {
        return ((Number) em.createNativeQuery("""
                SELECT (SELECT count(*) FROM exam_answers WHERE questao_id = :q)
                     + (SELECT count(*) FROM item_answers WHERE questao_id = :q)""")
                .setParameter("q", q.getId()).getSingleResult()).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Mudanca> historico(Questao q) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT c.alterado_em, u.nome, c.resumo, c.antes
                FROM question_changes c LEFT JOIN users u ON u.id = c.alterado_por_id
                WHERE c.questao_id = :q ORDER BY c.alterado_em DESC, c.id DESC LIMIT 20""")
                .setParameter("q", q.getId()).getResultList();
        return linhas.stream()
                .map(l -> new Mudanca(br.com.plataforma.comum.Relogio.emBrasilia(instante(l[0])),
                        l[1] == null ? "alguém" : (String) l[1], (String) l[2], (String) l[3]))
                .toList();
    }

    private static Instant instante(Object valor) {
        return switch (valor) {
            case Instant i -> i;
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("data inesperada: " + valor.getClass());
        };
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
            // "#46" ou "46" também é o número da questão, e ela vem na frente das que só têm o
            // número no enunciado: quem já sabe qual é não precisa de um trecho do texto.
            var numero = NUMERO_DA_QUESTAO.matcher(busca.strip());
            if (numero.matches()) {
                jpql.append(" and (q.id = :numero or lower(q.enunciado) like lower(:busca) escape '\\')");
                parametros.put("numero", Integer.valueOf(numero.group(1)));
            } else {
                jpql.append(" and lower(q.enunciado) like lower(:busca) escape '\\'");
            }
            parametros.put("busca", "%" + literal + "%");
        }
        jpql.append(parametros.containsKey("numero") ? " order by case when q.id = :numero then 0 else 1 end, q.id"
                : " order by q.id");

        var consulta = em.createQuery(jpql.toString(), Questao.class);
        parametros.forEach(consulta::setParameter);

        var achadas = consulta.setFirstResult(Math.max(0, deslocamento)).setMaxResults(limite)
                .getResultList();
        var aulas = estrutura.aulasDasQuestoes(achadas.stream().map(Questao::getId).toList());
        return achadas.stream().map(q -> descrever(q, true, aulas)).toList();
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
            String resolucaoComentada, Map<String, String> comentarios) {}

    public record DadosDoVideo(String vimeoId, String titulo, String url, String embedUrl,
            String thumbnailUrl, Integer duracaoSegundos, String pasta) {}

    @Transactional
    public Questao editar(Identidade ident, String referencia, Alteracao nova, Instant agora) {
        ident.exigirOperador();
        var q = resolver(referencia);

        // Não há trava por simulado aberto: questão com erro se corrige na hora. O que muda do que o
        // aluno lê deixa rastro, e o acerto de quem já respondeu é refeito (ver `registrarCorrecao`).
        var publicada = q.getStatus() == Status.PUBLICADO;
        var gabaritoAntes = q.getGabarito();
        var antes = retrato(q);

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
            q.ajustarAlternativas(publicada ? exigirAoMenosDuas(juntas) : exigirAlternativas(juntas));
        }
        if (nova.gabarito() != null || nova.alternativas() != null) {
            exigirGabaritoEntreAsAlternativas(q.getGabarito(), q.getAlternativas().stream()
                    .map(Alternativa::getLetra).toList());
        }

        // O comentário é como a resolução: o aluno só o lê depois de responder, então muda mesmo
        // com a prova aberta.
        if (nova.comentarios() != null) {
            q.comentarAlternativas(comentarios(nova.comentarios()));
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
        var salva = questoes.save(q);
        if (publicada) {
            registrarCorrecao(ident, salva, antes, gabaritoAntes, agora);
        }
        return salva;
    }

    /** A questão como o aluno a lê: gabarito, enunciado e alternativas, em texto. */
    private static Map<String, String> retrato(Questao q) {
        var partes = new LinkedHashMap<String, String>();
        partes.put("Gabarito", q.getGabarito().name());
        partes.put("Enunciado", q.getEnunciado());
        q.getAlternativas().stream().sorted(java.util.Comparator.comparing(Alternativa::getLetra))
                .forEach(a -> partes.put(a.getLetra().name(), a.getTexto()));
        return partes;
    }

    private static String emTexto(Map<String, String> retrato) {
        var sb = new StringBuilder();
        retrato.forEach((chave, valor) -> sb.append(chave.length() == 1 ? chave + ") " : chave + ": ").append(valor).append('\n'));
        return sb.toString().stripTrailing();
    }

    /**
     * Mudou o que o aluno lê numa questão publicada: grava o rastro e, se o gabarito é outro, refaz
     * o acerto de quem já respondeu, no simulado e na aula. Nota, posição e desempenho por assunto
     * são somados dessas respostas, então se ajustam sozinhos. O aluno não é avisado.
     */
    private void registrarCorrecao(Identidade ident, Questao q, Map<String, String> antes, Letra gabaritoAntes,
            Instant agora) {
        em.flush();
        var depois = retrato(q);
        if (depois.equals(antes)) {
            return;
        }
        var mudou = new ArrayList<String>();
        if (gabaritoAntes != q.getGabarito()) {
            mudou.add("gabarito de %s para %s".formatted(gabaritoAntes, q.getGabarito()));
        }
        if (!antes.get("Enunciado").equals(depois.get("Enunciado"))) {
            mudou.add("enunciado alterado");
        }
        for (var letra : Letra.values()) {
            var era = antes.get(letra.name());
            var ficou = depois.get(letra.name());
            if (era != null && ficou == null) {
                mudou.add("alternativa %s removida".formatted(letra));
            } else if (era == null && ficou != null) {
                mudou.add("alternativa %s incluída".formatted(letra));
            } else if (era != null && !era.equals(ficou)) {
                mudou.add("alternativa %s alterada".formatted(letra));
            }
        }

        var recalculadas = 0;
        if (gabaritoAntes != q.getGabarito()) {
            for (var tabela : List.of("exam_answers", "item_answers")) {
                recalculadas += em.createNativeQuery(
                        "UPDATE " + tabela + " SET correta = (alternativa_marcada = :g)"
                                + " WHERE questao_id = :q AND correta <> (alternativa_marcada = :g)")
                        .setParameter("g", q.getGabarito().name()).setParameter("q", q.getId()).executeUpdate();
            }
        }
        var resumo = Character.toUpperCase(String.join("; ", mudou).charAt(0)) + String.join("; ", mudou).substring(1)
                + (recalculadas == 0 ? "." : "; %d %s.".formatted(recalculadas,
                        recalculadas == 1 ? "resposta mudou de acerto" : "respostas mudaram de acerto"));
        em.createNativeQuery("""
                INSERT INTO question_changes (questao_id, alterado_por_id, alterado_em, resumo, antes, depois, respostas_recalculadas)
                VALUES (:q, :quem, :quando, :resumo, :antes, :depois, :n)""")
                .setParameter("q", q.getId()).setParameter("quem", ident.usuarioId())
                .setParameter("quando", java.time.OffsetDateTime.ofInstant(agora, java.time.ZoneOffset.UTC))
                .setParameter("resumo", resumo.length() > 500 ? resumo.substring(0, 500) : resumo)
                .setParameter("antes", emTexto(antes)).setParameter("depois", emTexto(depois))
                .setParameter("n", recalculadas).executeUpdate();
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
            String resolucaoComentada, Integer numero, Map<String, String> comentarios) {

        /** Sem comentário por alternativa: é como a importação monta a questão. */
        public DadosDaQuestaoNova(String enunciado, Map<String, String> alternativas, String gabarito,
                String assunto, String subassunto, String dificuldade, DadosDoVideo resolucao,
                Boolean imagemPendente, String resolucaoComentada, Integer numero) {
            this(enunciado, alternativas, gabarito, assunto, subassunto, dificuldade, resolucao,
                    imagemPendente, resolucaoComentada, numero, null);
        }
    }

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
        exigirAlternativas(letras);
        exigirGabaritoEntreAsAlternativas(letra(dados.gabarito()), letras.keySet());

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
        if (dados.comentarios() != null) {
            questao.comentarAlternativas(comentarios(dados.comentarios()));
        }
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
        if (!questao.completa()) {
            throw new RegraDeNegocio(
                    ("A questão %s ainda está sem as alternativas A-D e não pode ir para um simulado.")
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

        var tipo = FigurasServico.tipoDaImagem(conteudo);
        figuras.trocarBytes(figuraId, conteudo, tipo);
        q.tocar(ident);
        questoes.save(q);

        return new FiguraNaQuestaoResposta(q.getId(), figuraId, parte, tipo, conteudo.length,
                q.isImagemPendente());
    }

    public record Imagem(String tipo, byte[] conteudo) {}

    /**
     * A figura, para quem pode vê-la.
     *
     * <p>Operador, sempre. Aluno, só depois de começar uma prova publicada que tenha a questão —
     * antes disso, a figura adiantaria a prova. A da resolução, só depois que essa prova fechar,
     * como o vídeo de resolução.
     *
     * <p>A questão que está numa aula do aluno segue a regra da aula: enunciado e alternativas
     * aparecem com a linha, e a resolução, depois que ele responde.
     */
    @Transactional(readOnly = true)
    public Imagem figura(Identidade ident, Integer figuraId, Instant agora) {
        var f = figuras.exigir(figuraId);
        if (!ident.eOperador()) {
            if (f.getQuestao() == null) {
                throw new NaoAutorizado("Esta figura ainda não faz parte de nenhuma questão.");
            }
            if (liberadaPelaAula(ident, f, agora)) {
                return new Imagem(f.getTipo(), figuras.bytesDe(figuraId));
            }
            var fechamentos = em.createQuery("""
                    select s.fechaEm from Tentativa t join t.simulado s, SimuladoQuestao sq
                     where sq.simulado = s and t.aluno.id = :aluno and sq.questao.id = :questao
                       and s.status = br.com.plataforma.comum.Status.PUBLICADO
                       and s.removidoEm is null""", Instant.class)
                    .setParameter("aluno", ident.usuarioId())
                    .setParameter("questao", f.getQuestao().getId())
                    .getResultList();
            if (fechamentos.isEmpty()) {
                throw new NaoAutorizado("Esta figura é de uma prova que você não começou.");
            }
            if (ParteDaQuestao.RESOLUCAO.name().equals(f.getParte())
                    && fechamentos.stream().noneMatch(x -> x != null && !x.isAfter(agora))) {
                throw new NaoAutorizado("A resolução aparece quando o simulado fechar.");
            }
        }
        return new Imagem(f.getTipo(), figuras.bytesDe(figuraId));
    }

    private boolean liberadaPelaAula(Identidade ident, Figura f, Instant agora) {
        var questao = f.getQuestao();
        if (questao.getStatus() != Status.PUBLICADO) {
            return false;
        }
        var linhas = em.createQuery("select i.id from Item i where i.questao.id = :questao", Integer.class)
                .setParameter("questao", questao.getId()).getResultList();
        if (acesso.itensLiberados(ident, linhas, agora).isEmpty()) {
            return false;
        }
        if (!ParteDaQuestao.RESOLUCAO.name().equals(f.getParte())) {
            return true;
        }
        return em.createQuery("""
                select count(r) from RespostaDeExercicio r
                 where r.questaoId = :questao and r.alunoId = :aluno""", Long.class)
                .setParameter("questao", questao.getId())
                .setParameter("aluno", ident.usuarioId())
                .getSingleResult() > 0;
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

    /**
     * De A a D são obrigatórias; a E é opcional, e vazia é o mesmo que não ter. Devolve o mesmo
     * mapa, já sem a E vazia.
     */
    /** O comentário não é enunciado: é um texto breve, para o aluno ler logo depois de errar. */
    static final int TAMANHO_DO_COMENTARIO = 1000;

    private static Map<Letra, String> comentarios(Map<String, String> crus) {
        var saida = new LinkedHashMap<Letra, String>();
        crus.forEach((k, v) -> {
            var texto = v == null ? "" : v.strip();
            if (texto.length() > TAMANHO_DO_COMENTARIO) {
                throw new RegraDeNegocio("O comentário da alternativa %s tem %d caracteres; o limite é %d."
                        .formatted(letra(k), texto.length(), TAMANHO_DO_COMENTARIO));
            }
            saida.put(letra(k), texto);
        });
        return saida;
    }

    static Map<Letra, String> exigirAlternativas(Map<Letra, String> alternativas) {
        var e = alternativas.get(Letra.E);
        if (e != null && e.isBlank()) {
            alternativas.remove(Letra.E);
        }
        var faltando = Questao.OBRIGATORIAS.stream()
                .filter(letra -> !alternativas.containsKey(letra)).map(Letra::name).toList();
        if (!faltando.isEmpty()) {
            throw new RegraDeNegocio("Faltam as alternativas %s. A questão precisa de A a D; a E é opcional."
                    .formatted(String.join(", ", faltando)));
        }
        return alternativas;
    }

    /**
     * Na questão já publicada, alternativa em branco é alternativa removida, qualquer que seja a
     * letra. As outras não mudam de letra: tirar a C deixa A, B, D e E, e a resposta de quem marcou
     * a D continua sendo a D.
     */
    static Map<Letra, String> exigirAoMenosDuas(Map<Letra, String> alternativas) {
        alternativas.values().removeIf(String::isBlank);
        if (alternativas.size() < Questao.MINIMO_DEPOIS_DE_PUBLICADA) {
            throw new RegraDeNegocio("A questão precisa de pelo menos duas alternativas.");
        }
        return alternativas;
    }

    static void exigirGabaritoEntreAsAlternativas(Letra gabarito, java.util.Collection<Letra> letras) {
        if (!letras.contains(gabarito)) {
            throw new RegraDeNegocio("O gabarito é %s, mas a questão não tem a alternativa %s."
                    .formatted(gabarito, gabarito));
        }
    }
}
