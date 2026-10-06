package br.com.plataforma.rascunhos;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.acervo.AcervoServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.SubModulo;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.MontagemDaProva;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladoQuestao;
import br.com.plataforma.simulados.SimuladosServico;
import br.com.plataforma.taxonomia.Etiqueta;
import br.com.plataforma.taxonomia.TaxonomiaServico;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As propostas que esperam o "pode" de um humano.
 *
 * <p>Nada aqui publica: publicar é do {@link PublicacaoServico}, que confere a aprovação gravada
 * no banco.
 */
@Service
public class RascunhosServico {

    private final RascunhoRepositorio rascunhos;
    private final ContasServico contas;
    private final QuestoesServico questoes;
    private final SimuladosServico simulados;
    private final MontagemDaProva montagem;
    private final EstruturaServico estrutura;
    private final TaxonomiaServico taxonomia;
    private final AcervoServico acervo;

    @PersistenceContext
    private EntityManager em;

    public RascunhosServico(RascunhoRepositorio rascunhos, ContasServico contas,
            QuestoesServico questoes, SimuladosServico simulados, MontagemDaProva montagem,
            EstruturaServico estrutura, TaxonomiaServico taxonomia, AcervoServico acervo) {
        this.rascunhos = rascunhos;
        this.contas = contas;
        this.questoes = questoes;
        this.simulados = simulados;
        this.montagem = montagem;
        this.estrutura = estrutura;
        this.taxonomia = taxonomia;
        this.acervo = acervo;
    }

    // --- criar ---------------------------------------------------------------

    Rascunho abrir(Identidade ident, TipoRascunho tipo, Turma turma, SubModulo submodulo) {
        var autor = contas.buscar(ident.usuarioId())
                .orElseThrow(() -> new NaoEncontrado("Esta conta não existe mais."));
        return rascunhos.save(new Rascunho(tipo, turma, submodulo, "", ident.canal(), autor));
    }

    /**
     * Propõe uma questão para o acervo de simulado.
     *
     * <p>Sem turma: questão não pertence a turma nenhuma — quem pertence é o simulado onde ela
     * entra.
     */
    @Transactional
    public Rascunho criarQuestaoRascunho(Identidade ident, QuestoesServico.DadosDaQuestaoNova dados) {
        ident.exigirOperador();
        var rascunho = abrir(ident, TipoRascunho.QUESTOES, null, null);
        var questao = questoes.criarNova(ident, rascunho.getId(), dados, null);

        var etiqueta = dados.assunto() == null || dados.assunto().isBlank()
                ? ""
                : " — " + dados.assunto();
        rascunho.mudarResumo("1 questão de simulado%s: %s"
                .formatted(etiqueta, QuestoesServico.resumo(questao.getEnunciado(), 60)));
        return rascunho;
    }

    /**
     * Monta o simulado <b>e as questões novas dele</b> num rascunho só.
     *
     * <p>Um simulado de 15 questões é um preview e um ok, não dezesseis rascunhos.
     */
    @Transactional
    public Rascunho criarSimuladoRascunho(Identidade ident, String titulo, List<Turma> turmas,
            List<MontagemDaProva.Entrada> entradas, Instant abreEm, Instant fechaEm,
            Integer duracaoMinutos, Map<Integer, QuestoesServico.DadosDoVideo> resolucoes) {
        ident.exigirOperador();
        if (entradas == null || entradas.isEmpty()) {
            throw new RegraDeNegocio("Informe ao menos uma questão para o simulado.");
        }
        if (turmas.isEmpty()) {
            throw new RegraDeNegocio("Informe ao menos uma turma para o simulado.");
        }

        // O rascunho aponta uma turma só; o simulado de várias vive em `turmas`.
        var rascunho = abrir(ident, TipoRascunho.SIMULADO, turmas.size() == 1 ? turmas.getFirst() : null, null);
        var simulado = simulados.criar(ident, titulo, turmas, abreEm, fechaEm, duracaoMinutos,
                rascunho.getId());
        em.flush();

        var prova = montagem.montar(ident, entradas, rascunho.getId(), Map.of(), resolucoes);
        simulados.trocarQuestoes(ident, simulado, prova);

        var novas = prova.stream()
                .filter(q -> rascunho.getId().equals(q.getRascunhoId()))
                .count();
        rascunho.mudarResumo("Simulado '%s' com %d questões (%d novas) — %s".formatted(
                simulado.getTitulo(), entradas.size(), novas,
                turmas.stream().map(Turma::getNome).collect(joining(", "))));
        return rascunho;
    }

    /** Uma questão a caminho da aula: do acervo (pelo id) ou nova, e o nome da linha ("Q04"). */
    public record QuestaoParaAula(MontagemDaProva.Entrada entrada, String nome) {}

    /**
     * Põe questões num sub-módulo, em rascunho: uma linha por questão, e as questões novas nascem
     * no mesmo rascunho — a apostila de um capítulo é um preview e um ok.
     *
     * <p>Questão do acervo precisa estar publicada: a linha não pode depender de outro rascunho.
     * Sem nome, a linha de uma questão numerada vira "Q04"; sem número, "Questão N".
     */
    @Transactional
    public Rascunho criarQuestoesNaAula(Identidade ident, Turma turma, SubModulo submodulo,
            List<QuestaoParaAula> entradas, Map<Integer, QuestoesServico.DadosDoVideo> resolucoes) {
        ident.exigirOperador();
        if (entradas == null || entradas.isEmpty()) {
            throw new RegraDeNegocio("Informe ao menos uma questão para a aula.");
        }
        var rascunho = abrir(ident, TipoRascunho.ITENS, turma, submodulo);
        em.flush();

        var novas = 0;
        for (int n = 0; n < entradas.size(); n++) {
            var entrada = entradas.get(n);
            try {
                Questao questao;
                String numerada = null;
                switch (entrada.entrada()) {
                    case MontagemDaProva.Nova nova -> {
                        var numero = nova.dados().numero();
                        questao = questoes.criarNova(ident, rascunho.getId(), nova.dados(),
                                numero == null || resolucoes == null ? null : resolucoes.get(numero));
                        numerada = numero == null ? null : "Q%02d".formatted(numero);
                        novas++;
                    }
                    case MontagemDaProva.PorId porId -> {
                        questao = questoes.resolver(porId.questaoId());
                        if (questao.getStatus() != Status.PUBLICADO) {
                            throw new RegraDeNegocio(
                                    "a questão %d ainda é rascunho; publique o rascunho dela antes."
                                            .formatted(questao.getId()));
                        }
                    }
                }
                estrutura.criarItemDeQuestao(ident, submodulo, questao,
                        entrada.nome() == null || entrada.nome().isBlank() ? numerada : entrada.nome(),
                        Status.RASCUNHO, rascunho.getId());
            } catch (RegraDeNegocio | NaoEncontrado e) {
                throw new RegraDeNegocio("Questão %d da lista: %s".formatted(n + 1, e.getMessage()));
            }
        }

        var modulo = submodulo.getModulo() == null ? "?" : submodulo.getModulo().getNome();
        rascunho.mudarResumo("%d questão(ões) (%d novas) para %s / %s › %s".formatted(entradas.size(), novas,
                turma == null ? "biblioteca" : turma.getNome(), modulo, submodulo.getNome()));
        return rascunho;
    }

    /** Apaga o rascunho e tudo que nasceu nele. Só a publicação sabe se pode. */
    @Transactional
    public void apagar(Rascunho r) {
        var id = r.getId();
        for (var sql : new String[] {
                "DELETE FROM exam_questions WHERE simulado_id IN (SELECT id FROM exams WHERE rascunho_id = :r)",
                "DELETE FROM exam_classes WHERE simulado_id IN (SELECT id FROM exams WHERE rascunho_id = :r)",
                "DELETE FROM exams WHERE rascunho_id = :r",
                "DELETE FROM items WHERE rascunho_id = :r",
                "DELETE FROM images WHERE questao_id IN (SELECT id FROM questions WHERE rascunho_id = :r)",
                "DELETE FROM question_options WHERE questao_id IN (SELECT id FROM questions WHERE rascunho_id = :r)",
                "DELETE FROM question_subjects WHERE questao_id IN (SELECT id FROM questions WHERE rascunho_id = :r)",
                "DELETE FROM questions WHERE rascunho_id = :r",
                "UPDATE imports SET rascunho_id = NULL WHERE rascunho_id = :r",
                "DELETE FROM drafts WHERE id = :r"}) {
            em.createNativeQuery(sql).setParameter("r", id).executeUpdate();
        }
        em.clear();
    }

    /** Um vídeo do Vimeo como o adaptador o descreve, pronto para virar item. */
    public record VideoParaImportar(
            String vimeoId, String titulo, String nome, String url, String embedUrl,
            String thumbnailUrl, Integer duracaoSegundos, String pasta, String assunto,
            String subassunto) {}

    public record ItensImportados(Rascunho rascunho, List<String> erros) {}

    /**
     * Cria, em rascunho, um item por vídeo informado.
     *
     * <p>{@code nome} é opcional: sem ele vale o título do vídeo, que é o que o professor
     * reconhece. {@code assunto} etiqueta o <b>vídeo</b> — não o item —, porque a etiqueta é do
     * conteúdo e atravessa turmas e anos.
     *
     * <p>Módulo e sub-módulo precisam existir: criá-los no meio de uma importação esconderia do
     * professor a decisão de como o curso está organizado.
     */
    @Transactional
    public ItensImportados importarVideosComoItens(Identidade ident, Turma turma, String modulo,
            String submodulo, List<VideoParaImportar> videos) {
        ident.exigirOperador();
        if (videos == null || videos.isEmpty()) {
            throw new RegraDeNegocio("Nenhum vídeo informado para importar.");
        }
        var alvos = estrutura.alvos(turma, modulo, submodulo, null);

        var rascunho = abrir(ident, TipoRascunho.ITENS, turma, alvos.submodulo());
        em.flush();

        var erros = new java.util.ArrayList<String>();
        var criados = 0;
        for (var entrada : videos) {
            if (entrada.vimeoId() == null || entrada.vimeoId().isBlank()) {
                erros.add("item sem vimeo_id: " + entrada.titulo());
                continue;
            }
            var titulo = entrada.titulo() == null || entrada.titulo().isBlank()
                    ? "Vídeo " + entrada.vimeoId()
                    : entrada.titulo().strip();
            var video = acervo.registrar(ident, entrada.vimeoId().strip(), titulo, entrada.url(),
                    entrada.embedUrl(), entrada.thumbnailUrl(), entrada.duracaoSegundos(),
                    entrada.pasta());

            if (estrutura.jaTemEsteVideo(alvos.submodulo(), video)) {
                erros.add("%s: '%s' já tem este vídeo.".formatted(
                        entrada.vimeoId(), alvos.submodulo().getNome()));
                continue;
            }

            var nome = entrada.nome() != null && !entrada.nome().isBlank()
                    ? entrada.nome()
                    : (entrada.titulo() != null && !entrada.titulo().isBlank()
                            ? entrada.titulo() : video.getTitulo());
            estrutura.criarItem(ident, alvos.submodulo(), video, nome, null,
                    Status.RASCUNHO, rascunho.getId());

            if (entrada.assunto() != null && !entrada.assunto().isBlank()) {
                var assunto = taxonomia.resolverAssunto(entrada.assunto());
                var sub = entrada.subassunto() == null || entrada.subassunto().isBlank()
                        ? null : taxonomia.resolverSubassunto(assunto, entrada.subassunto());
                taxonomia.classificarVideo(ident, video, assunto, sub);
            }
            criados++;
        }

        if (criados == 0) {
            throw new RegraDeNegocio("Nenhum item pôde ser criado. " + String.join(" | ", erros));
        }
        rascunho.mudarResumo("%d vídeo(s) para %s / %s › %s".formatted(criados, turma == null ? "biblioteca" : turma.getNome(),
                alvos.modulo().getNome(), alvos.submodulo().getNome()));
        return new ItensImportados(rascunho, erros);
    }

    // --- ler -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Rascunho exigir(Integer id) {
        return rascunhos.findById(id)
                .orElseThrow(() -> new NaoEncontrado("Rascunho %d não existe.".formatted(id)));
    }

    public record ResumoDoRascunho(
            Integer rascunhoId, TipoRascunho tipo, Status status, String resumo, String turma,
            String modulo, String submodulo, String criadoPor, String origem, String criadoEm,
            String aprovadoPor, String aprovadoVia, String publicadoEm) {}

    public ResumoDoRascunho resumo(Rascunho r) {
        var sub = r.getSubmodulo();
        return new ResumoDoRascunho(r.getId(), r.getTipo(), r.getStatus(), r.getResumo(),
                r.getTurma() == null ? null : r.getTurma().getNome(),
                sub == null || sub.getModulo() == null ? null : sub.getModulo().getNome(),
                sub == null ? null : sub.getNome(),
                r.getCriadoPor().getNome(), r.getOrigem().name(),
                r.getCriadoEm() == null ? null : r.getCriadoEm().toString(),
                r.getAprovadoPor() == null ? null : r.getAprovadoPor().getNome(),
                r.getAprovadoVia() == null ? null : r.getAprovadoVia().name(),
                r.getPublicadoEm() == null ? null : r.getPublicadoEm().toString());
    }

    @Transactional(readOnly = true)
    public List<ResumoDoRascunho> listar(Identidade ident, String status) {
        ident.exigirOperador();
        var lista = status == null || status.isBlank()
                ? rascunhos.findAllByOrderByCriadoEmDesc()
                : rascunhos.findByStatusOrderByCriadoEmDesc(statusDe(status));
        return lista.stream().map(this::resumo).toList();
    }

    /** {@code video} null: a linha é de questão, e {@code questaoId} diz qual. */
    public record ItemDoRascunho(
            Integer itemId, String nome, Integer ordem, Status status, VideoDoItem video,
            List<Etiqueta> assuntos, Integer questaoId) {}

    public record VideoDoItem(String vimeoId, String titulo) {}

    public record QuestaoDoRascunho(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas, Letra gabarito,
            boolean completa, String resolucaoComentada,
            br.com.plataforma.questoes.Dificuldade dificuldade, List<Etiqueta> classificacao,
            boolean imagemPendente, VideoDoItem video) {}

    /** {@code aulas}: onde a questão também está no curso. */
    public record QuestaoNaProva(
            Integer ordem, Integer questaoId, boolean nova, String enunciado, Letra gabarito,
            boolean imagemPendente, String resolucao, List<String> aulas) {}

    /**
     * {@code avisos} não impedem publicar: são o que o professor precisa saber antes do ok — hoje,
     * as questões da prova que também estão em aula.
     */
    public record SimuladoDoRascunho(
            Integer simuladoId, String titulo, List<String> turmas, String abreEm, String fechaEm,
            Integer duracaoMinutos, List<QuestaoNaProva> questoes,
            List<String> pendenciasParaPublicar, List<String> avisos) {}

    public record RascunhoDetalhado(
            Integer rascunhoId, TipoRascunho tipo, Status status, String resumo, String turma,
            String modulo, String submodulo, String criadoPor, String origem, String criadoEm,
            String aprovadoPor, String aprovadoVia, String publicadoEm,
            List<ItemDoRascunho> itens, List<QuestaoDoRascunho> questoes,
            SimuladoDoRascunho simulado, boolean publicado) {}

    @Transactional(readOnly = true)
    public RascunhoDetalhado detalhar(Identidade ident, Integer id, Instant agora) {
        ident.exigirOperador();
        var r = exigir(id);
        var base = resumo(r);

        var itens = estrutura.itensDoRascunho(r.getId(), false).stream()
                .map(i -> new ItemDoRascunho(i.getId(), i.getNome(), i.getOrdem(), i.getStatus(),
                        i.getVideo() == null ? null
                                : new VideoDoItem(i.getVideo().getVimeoId(), i.getVideo().getTitulo()),
                        i.getVideo() != null ? taxonomia.etiquetasDoVideo(i.getVideo())
                                : i.getQuestao() != null ? questoes.etiquetasDa(i.getQuestao()) : List.of(),
                        i.getQuestao() == null ? null : i.getQuestao().getId()))
                .toList();

        var daProposta = questoesDoRascunho(r.getId());
        var descritas = daProposta.stream().map(q -> {
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            return new QuestaoDoRascunho(q.getId(), q.getEnunciado(), alternativas,
                    q.getAlternativas().isEmpty() ? null : q.getGabarito(),
                    q.completa(),
                    q.getResolucaoComentada(), q.getDificuldade(), questoes.etiquetasDa(q),
                    q.isImagemPendente(),
                    q.getVideo() == null
                            ? null
                            : new VideoDoItem(q.getVideo().getVimeoId(), q.getVideo().getTitulo()));
        }).toList();

        var s = simuladoDoRascunho(r.getId());
        var aulas = s == null ? Map.<Integer, List<String>>of() : estrutura.aulasDasQuestoes(
                s.getQuestoes().stream().map(sq -> sq.getQuestao().getId()).toList());
        var simulado = s == null ? null : new SimuladoDoRascunho(
                s.getId(), s.getTitulo(), s.getTurmas().stream().map(Turma::getNome).toList(),
                br.com.plataforma.comum.Relogio.emBrasilia(s.getAbreEm()),
                br.com.plataforma.comum.Relogio.emBrasilia(s.getFechaEm()),
                s.getDuracaoMinutos(),
                s.getQuestoes().stream().map(sq -> new QuestaoNaProva(
                        sq.getOrdem(), sq.getQuestao().getId(),
                        r.getId().equals(sq.getQuestao().getRascunhoId()),
                        sq.getQuestao().getEnunciado(), sq.getQuestao().getGabarito(),
                        sq.getQuestao().isImagemPendente(),
                        sq.getQuestao().getVideo() == null ? null : sq.getQuestao().getVideo().getTitulo(),
                        aulas.getOrDefault(sq.getQuestao().getId(), List.of())))
                        .toList(),
                simulados.pendenciasParaPublicar(s, agora),
                avisosDeReuso(s, aulas));

        return new RascunhoDetalhado(base.rascunhoId(), base.tipo(), base.status(), base.resumo(),
                base.turma(), base.modulo(), base.submodulo(), base.criadoPor(), base.origem(),
                base.criadoEm(), base.aprovadoPor(), base.aprovadoVia(), base.publicadoEm(),
                itens, descritas, simulado, r.getStatus() == Status.PUBLICADO);
    }

    /** Não bloqueia: só conta ao professor que o gabarito destas questões já aparece numa aula. */
    static List<String> avisosDeReuso(Simulado s, Map<Integer, List<String>> aulas) {
        return s.getQuestoes().stream()
                .filter(sq -> aulas.containsKey(sq.getQuestao().getId()))
                .map(sq -> "A questão %d da prova também está em aula (%s): lá o aluno vê o gabarito ao responder."
                        .formatted(sq.getOrdem(), String.join("; ", aulas.get(sq.getQuestao().getId()))))
                .toList();
    }

    // --- o que a proposta trouxe ---------------------------------------------

    @Transactional(readOnly = true)
    public List<Questao> questoesDoRascunho(Integer rascunhoId) {
        return em.createQuery("select q from Questao q where q.rascunhoId = :id order by q.id",
                Questao.class).setParameter("id", rascunhoId).getResultList();
    }

    @Transactional(readOnly = true)
    public Simulado simuladoDoRascunho(Integer rascunhoId) {
        return em.createQuery("select s from Simulado s where s.rascunhoId = :id", Simulado.class)
                .setParameter("id", rascunhoId).getResultList().stream().findFirst().orElse(null);
    }

    /** As questões do simulado deste rascunho, para a edição poder reaproveitá-las pelo id. */
    @Transactional(readOnly = true)
    public Map<Integer, Questao> questoesAtuaisDe(Simulado s) {
        var atuais = new LinkedHashMap<Integer, Questao>();
        s.getQuestoes().stream().map(SimuladoQuestao::getQuestao)
                .forEach(q -> atuais.put(q.getId(), q));
        return atuais;
    }

    static Status statusDe(String texto) {
        try {
            return Status.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Status '%s' inválido. Use RASCUNHO ou PUBLICADO.".formatted(texto));
        }
    }
}
