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
        rascunho.mudarResumo("%d vídeo(s) para %s / %s › %s".formatted(criados, turma.getNome(),
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
                sub == null ? null : sub.getModulo().getNome(),
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

    public record ItemDoRascunho(
            Integer itemId, String nome, Integer ordem, Status status, VideoDoItem video,
            List<Etiqueta> assuntos) {}

    public record VideoDoItem(String vimeoId, String titulo) {}

    public record QuestaoDoRascunho(
            Integer questaoId, String enunciado, Map<Letra, String> alternativas, Letra gabarito,
            boolean completa, String resolucaoComentada,
            br.com.plataforma.questoes.Dificuldade dificuldade, List<Etiqueta> classificacao,
            boolean imagemPendente, VideoDoItem video) {}

    public record QuestaoNaProva(
            Integer ordem, Integer questaoId, boolean nova, String enunciado, Letra gabarito,
            boolean imagemPendente, String resolucao) {}

    public record SimuladoDoRascunho(
            Integer simuladoId, String titulo, List<String> turmas, String abreEm, String fechaEm,
            Integer duracaoMinutos, List<QuestaoNaProva> questoes,
            List<String> pendenciasParaPublicar) {}

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
                        new VideoDoItem(i.getVideo().getVimeoId(), i.getVideo().getTitulo()),
                        taxonomia.etiquetasDoVideo(i.getVideo())))
                .toList();

        var daProposta = questoesDoRascunho(r.getId());
        var descritas = daProposta.stream().map(q -> {
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            return new QuestaoDoRascunho(q.getId(), q.getEnunciado(), alternativas,
                    q.getAlternativas().isEmpty() ? null : q.getGabarito(),
                    q.getAlternativas().size() == Letra.values().length,
                    q.getResolucaoComentada(), q.getDificuldade(), questoes.etiquetasDa(q),
                    q.isImagemPendente(),
                    q.getVideo() == null
                            ? null
                            : new VideoDoItem(q.getVideo().getVimeoId(), q.getVideo().getTitulo()));
        }).toList();

        var s = simuladoDoRascunho(r.getId());
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
                        sq.getQuestao().getVideo() == null ? null : sq.getQuestao().getVideo().getTitulo()))
                        .toList(),
                simulados.pendenciasParaPublicar(s, agora));

        return new RascunhoDetalhado(base.rascunhoId(), base.tipo(), base.status(), base.resumo(),
                base.turma(), base.modulo(), base.submodulo(), base.criadoPor(), base.origem(),
                base.criadoEm(), base.aprovadoPor(), base.aprovadoVia(), base.publicadoEm(),
                itens, descritas, simulado, r.getStatus() == Status.PUBLICADO);
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
