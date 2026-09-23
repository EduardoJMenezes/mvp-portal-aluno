package br.com.plataforma.importacoes;

import br.com.plataforma.comum.Faixa;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.questoes.FigurasServico;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import br.com.plataforma.simulados.MontagemDaProva;
import br.com.plataforma.simulados.Simulado;
import br.com.plataforma.simulados.SimuladoQuestao;
import br.com.plataforma.simulados.SimuladosServico;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O arquivo que o professor envia pelo link: o .docx do simulado ou os prints.
 *
 * <p>Ler o formato é do adaptador — o parser de .docx, o LibreOffice e o Pillow moram lá, onde as
 * bibliotecas estão. Aqui fica o que é estado: o link, as figuras, o rascunho e o relatório.
 */
@Service
public class ImportacoesServico {

    /** O link vale um dia. Passou, o professor pede outro — é mais barato que um link eterno. */
    private static final Duration VALIDADE_DO_LINK = Duration.ofMinutes(30);

    private static final Pattern REFERENCIA_DE_FIGURA = Pattern.compile("figura:(\\d+)");
    private static final SecureRandom SORTEIO = new SecureRandom();

    /** Um link, uma leva. O limite protege a memória de quem lê os prints — e o Claude, que os vê. */
    private static final int LIMITE_DE_PRINTS = 50;

    private final ImportacaoRepositorio importacoes;
    private final ContasServico contas;
    private final CatalogoServico catalogo;
    private final FigurasServico figuras;
    private final RascunhosServico rascunhos;
    private final SimuladosServico simulados;
    private final MontagemDaProva montagem;

    public ImportacoesServico(ImportacaoRepositorio importacoes, ContasServico contas,
            CatalogoServico catalogo,
            FigurasServico figuras, RascunhosServico rascunhos, SimuladosServico simulados,
            MontagemDaProva montagem) {
        this.importacoes = importacoes;
        this.contas = contas;
        this.catalogo = catalogo;
        this.figuras = figuras;
        this.rascunhos = rascunhos;
        this.simulados = simulados;
        this.montagem = montagem;
    }

    // --- o link --------------------------------------------------------------

    public record LinkDeEnvio(Integer importacaoId, String token, String expiraEm) {}

    /**
     * O link de envio: uso único, com prazo, e preso a quem pediu.
     *
     * <p>O banco guarda só o hash do token. Quem lê a tabela não consegue usar o link.
     */
    @Transactional
    public LinkDeEnvio criarLink(Identidade ident, Map<String, Object> parametros, Instant agora) {
        ident.exigirOperador();
        var dono = contas.buscar(ident.usuarioId())
                .orElseThrow(() -> new NaoEncontrado("Esta conta não existe mais."));

        var bytes = new byte[32];
        SORTEIO.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        var importacao = importacoes.save(
                new Importacao(dono, hashDe(token), agora.plus(VALIDADE_DO_LINK), parametros));
        return new LinkDeEnvio(importacao.getId(), token, Relogio.emBrasilia(importacao.getExpiraEm()));
    }

    /** O mesmo SHA-256 de {@code api_tokens}: barato, determinístico e serve de índice. */
    static String hashDe(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            var texto = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                texto.append("%02x".formatted(b));
            }
            return texto.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", e);
        }
    }

    /**
     * O que a página de envio mostra antes de receber o arquivo.
     *
     * <p>Os campos são os que a página sempre recebeu — quem manda no formato é ela, não esta
     * porta: {@code formato} vem em minúscula porque é assim que o JavaScript o compara, e
     * {@code situacao} é o que a tela precisa saber (esperando, já usado ou vencido), não o
     * {@code status} cru da tabela.
     */
    public record SituacaoDoLink(
            Integer importacaoId, String situacao, String formato, String titulo,
            List<String> turmas, String pastaResolucao, String pedidoPor, String expiraEm,
            boolean valido, String mensagem) {}

    @Transactional(readOnly = true)
    public SituacaoDoLink situacaoDoLink(String token, Instant agora) {
        var importacao = importacoes.findFirstByTokenHash(hashDe(token))
                .orElseThrow(() -> new NaoEncontrado("Este link de envio não existe."));
        var expirou = !importacao.getExpiraEm().isAfter(agora);
        var jaUsado = importacao.getStatus() == StatusImportacao.PROCESSADA;
        var parametros = importacao.getParametros();

        return new SituacaoDoLink(
                importacao.getId(),
                jaUsado ? "RECEBIDO" : expirou ? "EXPIRADO" : "AGUARDANDO",
                formatoDe(importacao).toLowerCase(Locale.ROOT),
                texto(parametros.get("titulo")),
                textos(parametros.get("turmas")),
                texto(parametros.get("pasta_resolucao")),
                importacao.getCriadoPor().getNome(),
                Relogio.emBrasilia(importacao.getExpiraEm()),
                !expirou && !jaUsado,
                expirou ? "Este link expirou. Peça outro no chat."
                        : jaUsado ? "Este link já foi usado." : null);
    }

    /**
     * Sempre em maiúscula.
     *
     * <p>O portal em Python grava {@code "prints"} e {@code "docx"}; os comandos daqui gravam
     * {@code "PRINTS"} e {@code "DOCX"}. As duas formas convivem na tabela — inclusive em
     * produção, em linhas criadas antes da migração —, e normalizar na leitura é o que faz a
     * página de envio aceitar um link venha ele de onde vier.
     */
    static String formatoDe(Importacao importacao) {
        var formato = importacao.getParametros().get("formato");
        return formato == null ? "DOCX" : String.valueOf(formato).toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public Importacao aguardando(String token, String formato, Instant agora) {
        var importacao = importacoes.findFirstByTokenHash(hashDe(token))
                .orElseThrow(() -> new NaoEncontrado("Este link de envio não existe."));
        if (importacao.getStatus() == StatusImportacao.PROCESSADA) {
            throw new RegraDeNegocio("Este link já foi usado.");
        }
        if (!importacao.getExpiraEm().isAfter(agora)) {
            throw new RegraDeNegocio("Este link expirou em %s. Peça outro no chat."
                    .formatted(Relogio.emBrasilia(importacao.getExpiraEm())));
        }
        if (!formatoDe(importacao).equals(formato)) {
            throw new RegraDeNegocio("Este link é para %s, não para %s."
                    .formatted(formatoDe(importacao), formato));
        }
        return importacao;
    }

    // --- figuras -------------------------------------------------------------

    /** Delegadas: figura é do pacote das questões, que é quem sabe onde ela é citada. */
    @Transactional
    public Integer guardarFigura(byte[] conteudo, String tipo, String nome) {
        return figuras.guardar(conteudo, tipo, nome);
    }

    @Transactional(readOnly = true)
    public byte[] bytesDaFigura(Integer figuraId) {
        return figuras.bytesDe(figuraId);
    }

    public record FiguraNoTexto(String tipo, String parte) {}

    /** Onde a figura aparece e de que formato ela é — o rótulo que o Claude lê antes da imagem. */
    @Transactional(readOnly = true)
    public FiguraNoTexto ondeAparece(Integer figuraId) {
        var f = figuras.exigir(figuraId);
        return new FiguraNoTexto(f.getTipo(), f.getParte());
    }

    @Transactional
    public void ligarFiguras(Questao questao) {
        figuras.ligarAs(questao);
    }

    // --- o que o adaptador entrega depois de ler o .docx ---------------------

    /** Uma questão que o parser fechou, já com os ids das figuras no texto. */
    public record QuestaoLida(
            Integer numero, QuestoesServico.DadosDaQuestaoNova dados,
            List<String> avisos, List<Integer> blocos) {}

    /** Uma que o parser não fechou: fica para o Claude completar na revisão. */
    public record QuestaoNaoFechada(Integer numero, List<String> avisos, List<Integer> blocos) {}

    /**
     * Uma figura extraída do .docx, ainda sem id.
     *
     * <p>{@code chave} é como o parser a chama no texto — {@code ![](figura:f3)} — porque quando
     * ele leu o documento ainda não havia id nenhum. Guardar e trocar a chave pelo id é daqui.
     */
    public record FiguraDoDocx(String chave, String nome, String tipo, String conteudoBase64) {}

    public record DocxLido(
            String arquivoNome, String titulo, List<QuestaoLida> questoes,
            List<QuestaoNaoFechada> incompletas, List<Map<String, Object>> blocos,
            List<String> avisos, List<FiguraDoDocx> figuras,
            Map<Integer, QuestoesServico.DadosDoVideo> resolucoes) {}

    private static final Pattern CHAVE_DE_FIGURA = Pattern.compile("figura:(f\\d+)");

    /**
     * Guarda as figuras do documento e troca cada chave pelo id que ela ganhou.
     *
     * <p>Chave sem figura vira {@code figura:pendente}: é o EMF ou WMF que o LibreOffice não
     * converteu. A questão nasce com imagem pendente e o professor vê a falta no preview — melhor
     * do que a importação inteira falhar por causa de um formato dos anos 90.
     */
    private java.util.function.UnaryOperator<String> trocarChavesPorIds(List<FiguraDoDocx> doDocx) {
        var ids = new LinkedHashMap<String, Integer>();
        for (var f : doDocx == null ? List.<FiguraDoDocx>of() : doDocx) {
            var bytes = Base64.getDecoder().decode(f.conteudoBase64());
            ids.put(f.chave(), figuras.guardar(bytes, f.tipo(), f.nome()));
        }
        return texto -> texto == null ? null : CHAVE_DE_FIGURA.matcher(texto).replaceAll(achada -> {
            var id = ids.get(achada.group(1));
            return id == null ? FigurasServico.PENDENTE : "figura:" + id;
        });
    }

    public record DocxRegistrado(
            Integer importacaoId, String titulo, int questoesLidas, int questoesCompletas,
            Integer rascunhoId, String mensagem) {}

    /**
     * Grava o que o adaptador leu do .docx: o rascunho do simulado e o relatório.
     *
     * <p>A identidade é a do <b>dono do link</b>, no canal DOCX — quem enviou o arquivo foi o
     * professor, pela página, não quem quer que esteja chamando agora.
     */
    @Transactional
    public DocxRegistrado registrarDocx(String token, DocxLido lido, Instant agora) {
        var importacao = aguardando(token, "DOCX", agora);
        if (lido.questoes() == null || lido.questoes().isEmpty()) {
            throw new RegraDeNegocio(
                    "Não reconheci nenhuma questão completa neste arquivo (número, alternativas a) a e) "
                            + "e gabarito). Se ele não segue esse formato, monte o simulado pelo chat.");
        }

        var dono = importacao.getCriadoPor();
        var ident = new Identidade(dono.getId(), dono.getNome(), dono.getEmail(), dono.getPapel(),
                br.com.plataforma.comum.Canal.DOCX);

        var parametros = importacao.getParametros();
        var titulo = primeiroNaoVazio(String.valueOf(parametros.getOrDefault("titulo", "")),
                lido.titulo(), semExtensao(lido.arquivoNome()), "Simulado");

        var comIds = trocarChavesPorIds(lido.figuras());
        var entradas = lido.questoes().stream()
                .map(q -> (MontagemDaProva.Entrada) new MontagemDaProva.Nova(comIds(q.dados(), comIds)))
                .toList();

        var rascunho = rascunhos.criarSimuladoRascunho(ident, titulo,
                turmasDosParametros(parametros), entradas,
                SimuladosServico.lerDataHora(texto(parametros.get("abre_em"))),
                SimuladosServico.lerDataHora(texto(parametros.get("fecha_em"))),
                inteiro(parametros.get("duracao_minutos")),
                lido.resolucoes() == null ? Map.of() : lido.resolucoes());

        var simulado = rascunhos.simuladoDoRascunho(rascunho.getId());
        var criadas = simulado.getQuestoes().stream().map(SimuladoQuestao::getQuestao).toList();
        criadas.forEach(figuras::ligarAs);

        var porNumero = new LinkedHashMap<Integer, Integer>();
        for (int n = 0; n < lido.questoes().size() && n < criadas.size(); n++) {
            porNumero.put(lido.questoes().get(n).numero(), criadas.get(n).getId());
        }

        var linhas = new ArrayList<Map<String, Object>>();
        lido.questoes().forEach(q -> linhas.add(linhaDoRelatorio(
                q.numero(), porNumero.get(q.numero()), q.avisos(), q.blocos())));
        if (lido.incompletas() != null) {
            lido.incompletas().forEach(q -> linhas.add(
                    linhaDoRelatorio(q.numero(), null, q.avisos(), q.blocos())));
        }
        linhas.sort((a, b) -> inteiro(a.get("numero")) - inteiro(b.get("numero")));

        var relatorio = new LinkedHashMap<String, Object>();
        relatorio.put("titulo", titulo);
        relatorio.put("avisos", lido.avisos() == null ? List.of() : lido.avisos());
        relatorio.put("questoes", linhas);

        importacao.processada(recortar(lido.arquivoNome(), 200), rascunho.getId(),
                blocosComIds(lido.blocos(), comIds), relatorio, agora);

        return new DocxRegistrado(importacao.getId(), titulo, linhas.size(), criadas.size(),
                rascunho.getId(),
                "Recebido! Volte ao chat e avise que enviou — o Claude mostra o que foi lido.");
    }

    private static QuestoesServico.DadosDaQuestaoNova comIds(
            QuestoesServico.DadosDaQuestaoNova d, java.util.function.UnaryOperator<String> troca) {
        var alternativas = new LinkedHashMap<String, String>();
        if (d.alternativas() != null) {
            d.alternativas().forEach((letra, texto) -> alternativas.put(letra, troca.apply(texto)));
        }
        return new QuestoesServico.DadosDaQuestaoNova(troca.apply(d.enunciado()), alternativas,
                d.gabarito(), d.assunto(), d.subassunto(), d.dificuldade(), d.resolucao(),
                d.imagemPendente(), troca.apply(d.resolucaoComentada()), d.numero());
    }

    /** Os blocos ficam guardados para a revisão no chat: a chave ali também vira id. */
    private static List<Map<String, Object>> blocosComIds(
            List<Map<String, Object>> blocos, java.util.function.UnaryOperator<String> troca) {
        if (blocos == null) {
            return List.of();
        }
        return blocos.stream().map(bloco -> {
            var copia = new LinkedHashMap<>(bloco);
            copia.computeIfPresent("texto", (chave, texto) -> troca.apply(String.valueOf(texto)));
            return (Map<String, Object>) copia;
        }).toList();
    }

    private List<br.com.plataforma.catalogo.Turma> turmasDosParametros(Map<String, Object> parametros) {
        return catalogo.resolverTurmas(textos(parametros.get("turmas")));
    }

    static Map<String, Object> linhaDoRelatorio(
            Integer numero, Integer questaoId, List<String> avisos, List<Integer> blocos) {
        var linha = new LinkedHashMap<String, Object>();
        linha.put("numero", numero);
        linha.put("questao_id", questaoId);
        linha.put("avisos", avisos == null ? List.of() : avisos);
        linha.put("blocos", blocos == null ? List.of() : blocos);
        return linha;
    }

    static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    static String recortar(String texto, int tamanho) {
        return texto == null ? null : texto.substring(0, Math.min(texto.length(), tamanho));
    }

    static String semExtensao(String nome) {
        if (nome == null) {
            return null;
        }
        var ponto = nome.lastIndexOf('.');
        return ponto > 0 ? nome.substring(0, ponto) : nome;
    }

    static String primeiroNaoVazio(String... candidatos) {
        for (var c : candidatos) {
            if (c != null && !c.isBlank() && !"null".equals(c)) {
                return c.strip();
            }
        }
        return "Simulado";
    }

    // --- os prints -----------------------------------------------------------

    public record PrintRecebido(String nome, String tipo, String conteudoBase64) {}

    public record PrintsRegistrados(Integer importacaoId, int prints, String mensagem) {}

    /** Guarda os prints na ordem em que chegaram. Ler fica com o Claude, na conversa. */
    @Transactional
    public PrintsRegistrados registrarPrints(String token, List<PrintRecebido> arquivos, Instant agora) {
        var importacao = aguardando(token, "PRINTS", agora);
        if (arquivos == null || arquivos.isEmpty()) {
            throw new RegraDeNegocio("Nenhum print chegou. Cole ou escolha as imagens das questões.");
        }
        if (arquivos.size() > LIMITE_DE_PRINTS) {
            throw new RegraDeNegocio(("Mande até %d prints por link; peça outro no chat para o "
                    + "resto.").formatted(LIMITE_DE_PRINTS));
        }

        var ids = new ArrayList<Integer>();
        for (int n = 0; n < arquivos.size(); n++) {
            var arquivo = arquivos.get(n);
            var nome = arquivo.nome() == null || arquivo.nome().isBlank()
                    ? "print " + (n + 1) : arquivo.nome();
            ids.add(figuras.guardar(Base64.getDecoder().decode(arquivo.conteudoBase64()),
                    arquivo.tipo(), nome));
        }

        var relatorio = new LinkedHashMap<String, Object>();
        relatorio.put("prints", ids);
        importacao.processada("%d print(s)".formatted(ids.size()), null, null, relatorio, agora);

        return new PrintsRegistrados(importacao.getId(), ids.size(),
                "Recebido! Volte ao chat e avise que enviou — o Claude monta as questões a partir "
                        + "dos prints.");
    }

    public record PrintsDaImportacao(Integer importacaoId, int totalPrints, List<Integer> figuras) {}

    /** Quais figuras são os prints desta importação, na ordem. O adaptador busca os bytes. */
    @Transactional(readOnly = true)
    public PrintsDaImportacao printsDa(Identidade ident, Integer importacaoId) {
        var importacao = processada(ident, importacaoId);
        var relatorio = importacao.getRelatorio() == null ? Map.<String, Object>of() : importacao.getRelatorio();
        if (!relatorio.containsKey("prints")) {
            throw new RegraDeNegocio(
                    "A importação %d é de um .docx: revise com revisar_importacao."
                            .formatted(importacaoId));
        }
        var ids = indices(relatorio.get("prints"));
        return new PrintsDaImportacao(importacaoId, ids.size(), ids);
    }

    // --- a revisão no chat ---------------------------------------------------

    @Transactional(readOnly = true)
    public Importacao processada(Identidade ident, Integer importacaoId) {
        ident.exigirOperador();
        var importacao = importacoes.findById(importacaoId)
                .orElseThrow(() -> new NaoEncontrado(
                        "Importação %d não existe.".formatted(importacaoId)));
        if (importacao.getStatus() != StatusImportacao.PROCESSADA) {
            throw new RegraDeNegocio(
                    ("O arquivo da importação %d ainda não chegou (o link vale até %s). Confirme "
                            + "com o professor se ele enviou.")
                            .formatted(importacaoId, Relogio.emBrasilia(importacao.getExpiraEm())));
        }
        return importacao;
    }

    @Transactional(readOnly = true)
    public Simulado simuladoDa(Importacao importacao) {
        var simulado = rascunhos.simuladoDoRascunho(importacao.getRascunhoId());
        if (simulado == null) {
            throw new RegraDeNegocio("O simulado desta importação foi removido.");
        }
        return simulado;
    }

    public record QuestaoRevisada(
            Integer ordem, Integer numeroNoDocumento, Integer questaoId, String enunciado,
            Map<Letra, String> alternativas, Letra gabarito, String resolucaoComentada,
            boolean imagemPendente, List<br.com.plataforma.taxonomia.Etiqueta> classificacao,
            List<String> avisos, List<Integer> figuras) {}

    public record BlocoDoDocumento(Integer indice, String texto) {}

    public record QuestaoIncompleta(Integer numero, List<String> avisos, List<BlocoDoDocumento> blocos) {}

    public record Revisao(
            Integer importacaoId, Integer rascunhoId, Integer simuladoId, String titulo,
            String arquivo, int totalQuestoes, String mostrando, List<String> avisosGerais,
            List<QuestaoRevisada> questoes, List<QuestaoIncompleta> incompletas,
            List<String> pendenciasParaPublicar) {}

    /** O preview: as questões como estão agora no rascunho, os avisos e as figuras citadas. */
    @Transactional(readOnly = true)
    public Revisao revisar(Identidade ident, Integer importacaoId, int de, Integer ate,
            java.util.function.Function<Questao, List<br.com.plataforma.taxonomia.Etiqueta>> etiquetas,
            Instant agora) {
        var importacao = processada(ident, importacaoId);
        var simulado = simuladoDa(importacao);
        var relatorio = importacao.getRelatorio() == null ? Map.<String, Object>of() : importacao.getRelatorio();
        var linhas = linhasDoRelatorio(relatorio);
        var ultimo = ate == null ? simulado.getQuestoes().size() : ate;

        var porQuestao = new LinkedHashMap<Integer, Map<String, Object>>();
        linhas.stream().filter(l -> l.get("questao_id") != null)
                .forEach(l -> porQuestao.put(inteiro(l.get("questao_id")), l));

        var questoes = new ArrayList<QuestaoRevisada>();
        for (var sq : simulado.getQuestoes()) {
            if (sq.getOrdem() < de || sq.getOrdem() > ultimo) {
                continue;
            }
            var q = sq.getQuestao();
            var alternativas = new LinkedHashMap<Letra, String>();
            q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
            var leitura = porQuestao.getOrDefault(q.getId(), Map.of());

            var textos = new ArrayList<String>(alternativas.values());
            textos.add(q.getEnunciado());
            textos.add(q.getResolucaoComentada());

            questoes.add(new QuestaoRevisada(sq.getOrdem(), inteiro(leitura.get("numero")), q.getId(),
                    q.getEnunciado(), alternativas, q.getGabarito(), q.getResolucaoComentada(),
                    q.isImagemPendente(), etiquetas.apply(q),
                    avisosUteis(leitura), FigurasServico.citadas(textos.toArray(String[]::new))));
        }

        var blocos = blocosPorIndice(importacao);
        var incompletas = linhas.stream().filter(l -> l.get("questao_id") == null)
                .map(l -> new QuestaoIncompleta(inteiro(l.get("numero")), textos(l.get("avisos")),
                        indices(l.get("blocos")).stream()
                                .map(i -> new BlocoDoDocumento(i, blocos.getOrDefault(i, "")))
                                .toList()))
                .toList();

        return new Revisao(importacao.getId(), importacao.getRascunhoId(), simulado.getId(),
                simulado.getTitulo(), importacao.getArquivoNome(), simulado.getQuestoes().size(),
                "%d a %d".formatted(de, Math.min(ultimo, simulado.getQuestoes().size())),
                textos(relatorio.get("avisos")), questoes, incompletas,
                simulados.pendenciasParaPublicar(simulado, agora));
    }

    /** O texto do documento, montado a partir das faixas de blocos que o Claude apontou. */
    @Transactional(readOnly = true)
    public String blocos(Importacao importacao, String faixa, Pattern rotuloAremover) {
        var textos = blocosPorIndice(importacao);
        var indices = Faixa.interpretar(faixa).stream().sorted().toList();
        var faltando = indices.stream().filter(i -> !textos.containsKey(i)).toList();
        if (indices.isEmpty() || !faltando.isEmpty()) {
            throw new RegraDeNegocio("Blocos %s não existem neste documento."
                    .formatted(faltando.isEmpty() ? faixa : faltando.toString()));
        }
        var partes = new ArrayList<String>();
        for (var i : indices) {
            partes.add(textos.get(i));
        }
        if (rotuloAremover != null && !partes.isEmpty()) {
            partes.set(0, rotuloAremover.matcher(partes.getFirst()).replaceFirst(""));
        }
        return partes.stream().filter(p -> !p.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    @Transactional
    public void registrarQuestaoCompletada(Importacao importacao, int numero, Integer questaoId) {
        var relatorio = new LinkedHashMap<>(importacao.getRelatorio());
        var linhas = linhasDoRelatorio(relatorio).stream()
                .map(l -> {
                    if (!Integer.valueOf(numero).equals(inteiro(l.get("numero")))) {
                        return l;
                    }
                    var nova = new LinkedHashMap<String, Object>(l);
                    nova.put("questao_id", questaoId);
                    nova.put("avisos", List.of("Completada na revisão, a partir dos blocos."));
                    return (Map<String, Object>) nova;
                })
                .toList();
        relatorio.put("questoes", linhas);
        importacao.processada(importacao.getArquivoNome(), importacao.getRascunhoId(),
                importacao.getBlocos(), relatorio, importacao.getRecebidoEm());
    }

    public record QuestaoCompletada(
            Integer numero, Integer questaoId, Integer ordem, int totalQuestoes) {}

    /**
     * Monta, a partir dos blocos do documento, a questão que as regras não fecharam.
     *
     * <p>{@code enunciado} e {@code resolucao} são faixas de blocos ("12-18"); {@code alternativas}
     * é uma faixa de cinco blocos, um por letra, ou uma faixa por letra.
     */
    @Transactional
    public QuestaoCompletada completarQuestao(Identidade ident, Integer importacaoId, int numero,
            String enunciado, Object alternativas, String gabarito, String resolucao, Instant agora) {
        var importacao = processada(ident, importacaoId);
        var simulado = simuladoDa(importacao);
        var linhas = linhasDoRelatorio(importacao.getRelatorio());

        var linha = linhas.stream()
                .filter(l -> Integer.valueOf(numero).equals(inteiro(l.get("numero"))))
                .findFirst().orElse(null);
        if (linha == null) {
            var faltam = linhas.stream().filter(l -> l.get("questao_id") == null)
                    .map(l -> inteiro(l.get("numero"))).toList();
            throw new NaoEncontrado("A questão %d não foi lida neste documento. Incompletas: %s."
                    .formatted(numero, faltam.isEmpty() ? "nenhuma" : faltam.toString()));
        }
        if (linha.get("questao_id") != null) {
            throw new RegraDeNegocio(
                    "A questão %d já está no rascunho; ajuste com editar_questao.".formatted(numero));
        }

        var faixas = faixasDasAlternativas(alternativas);
        var textosDasLetras = new LinkedHashMap<String, String>();
        faixas.forEach((letra, faixa) ->
                textosDasLetras.put(letra, blocos(importacao, faixa, Rotulos.ALTERNATIVA)));

        var dados = new QuestoesServico.DadosDaQuestaoNova(
                blocos(importacao, enunciado, Rotulos.NUMERO), textosDasLetras, gabarito,
                null, null, null, null, null,
                resolucao == null || resolucao.isBlank()
                        ? null : blocos(importacao, resolucao, Rotulos.RESOLUCAO),
                numero);

        var numeroPorQuestao = new LinkedHashMap<Integer, Integer>();
        linhas.stream().filter(l -> l.get("questao_id") != null)
                .forEach(l -> numeroPorQuestao.put(inteiro(l.get("questao_id")), inteiro(l.get("numero"))));

        var atuais = simulado.getQuestoes().stream().map(SimuladoQuestao::getQuestao).toList();
        var posicao = posicaoDe(simulado, numeroPorQuestao, numero);

        var entradas = new ArrayList<MontagemDaProva.Entrada>();
        for (int n = 0; n < atuais.size(); n++) {
            if (n == posicao) {
                entradas.add(new MontagemDaProva.Nova(dados));
            }
            entradas.add(new MontagemDaProva.PorId(String.valueOf(atuais.get(n).getId())));
        }
        if (posicao >= atuais.size()) {
            entradas.add(new MontagemDaProva.Nova(dados));
        }

        var jaEstavam = atuais.stream().map(Questao::getId).toList();
        var prova = montagem.montar(ident, entradas, simulado.getRascunhoId(),
                rascunhos.questoesAtuaisDe(simulado), Map.of());
        simulados.trocarQuestoes(ident, simulado, prova);

        var nova = prova.stream().filter(q -> !jaEstavam.contains(q.getId())).findFirst()
                .orElseThrow(() -> new RegraDeNegocio("A questão não entrou na prova."));
        figuras.ligarAs(nova);
        registrarQuestaoCompletada(importacao, numero, nova.getId());

        return new QuestaoCompletada(numero, nova.getId(), posicao + 1, prova.size());
    }

    /** Uma faixa de cinco blocos, ou uma faixa por letra. */
    @SuppressWarnings("unchecked")
    static Map<String, String> faixasDasAlternativas(Object alternativas) {
        var faixas = new LinkedHashMap<String, String>();
        if (alternativas instanceof Map<?, ?> mapa) {
            mapa.forEach((letra, faixa) -> faixas.put(
                    String.valueOf(letra).strip().toUpperCase(Locale.ROOT), String.valueOf(faixa)));
            return faixas;
        }
        var indices = Faixa.interpretar(String.valueOf(alternativas)).stream().sorted().toList();
        if (indices.size() != Letra.values().length) {
            throw new RegraDeNegocio(
                    "Uma faixa só de alternativas precisa ter exatamente cinco blocos, de A a E.");
        }
        for (int n = 0; n < indices.size(); n++) {
            faixas.put(Letra.values()[n].name(), String.valueOf(indices.get(n)));
        }
        return faixas;
    }

    // --- leitura do relatório, que é JSON solto ------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> linhasDoRelatorio(Map<String, Object> relatorio) {
        var questoes = relatorio.get("questoes");
        return questoes instanceof List<?> lista ? (List<Map<String, Object>>) lista : List.of();
    }

    static Map<Integer, String> blocosPorIndice(Importacao importacao) {
        var textos = new LinkedHashMap<Integer, String>();
        if (importacao.getBlocos() != null) {
            importacao.getBlocos().forEach(b ->
                    textos.put(inteiro(b.get("indice")), String.valueOf(b.getOrDefault("texto", ""))));
        }
        return textos;
    }

    static Integer inteiro(Object valor) {
        return valor == null ? null : ((Number) valor).intValue();
    }

    @SuppressWarnings("unchecked")
    static List<String> textos(Object valor) {
        return valor instanceof List<?> lista
                ? lista.stream().map(String::valueOf).toList()
                : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<Integer> indices(Object valor) {
        return valor instanceof List<?> lista
                ? lista.stream().map(ImportacoesServico::inteiro).toList()
                : List.of();
    }

    /** O aviso de "não fechou" some na revisão: ele já virou a lista de incompletas. */
    static List<String> avisosUteis(Map<String, Object> leitura) {
        return textos(leitura.get("avisos")).stream()
                .filter(a -> !a.startsWith("Não fechou"))
                .toList();
    }

    static Letra letraDe(String texto) {
        return Letra.valueOf(String.valueOf(texto).strip().toUpperCase(Locale.ROOT));
    }

    static int posicaoDe(Simulado simulado, Map<Integer, Integer> numeroPorQuestao, int numero) {
        var atuais = simulado.getQuestoes().stream().map(SimuladoQuestao::getQuestao).toList();
        for (int n = 0; n < atuais.size(); n++) {
            var doOutro = numeroPorQuestao.getOrDefault(atuais.get(n).getId(), 0);
            if (doOutro > numero) {
                return n;
            }
        }
        return atuais.size();
    }
}
