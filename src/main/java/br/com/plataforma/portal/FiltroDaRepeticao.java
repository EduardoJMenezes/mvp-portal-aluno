package br.com.plataforma.portal;

import br.com.plataforma.comum.Identidade;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * A mesma escrita não roda duas vezes só porque chegou duas vezes.
 *
 * <p>O portal já trava o botão enquanto a ação roda, mas isso é o navegador: duas abas, um clique
 * que escapou ou um pedido repetido no caminho ainda chegam aqui em dobro. Este filtro reconhece a
 * repetição (as regras estão em {@link Repeticoes}) e devolve a resposta do primeiro, com o
 * cabeçalho {@value #CABECALHO}, sem rodar o segundo.
 *
 * <p>Fica depois do {@link FiltroDaSessao}: precisa saber quem pede. Só olha escrita em
 * {@code /api/**} com corpo JSON (ou sem corpo) de até {@value #MAXIMO} bytes. Envio de arquivo
 * fica de fora — o navegador sorteia o separador das partes, e dois envios do mesmo arquivo nunca
 * têm o mesmo corpo.
 */
public class FiltroDaRepeticao extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Repetida";
    static final int MAXIMO = 256 * 1024;
    private static final Set<String> ESCRITAS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** A compra é a única escrita sem sessão que vale proteger: quem a repete é reconhecido pelo IP. */
    private static final Pattern COMPRA = Pattern.compile("/api/vendas/planos/[^/]+/comprar");

    private final Repeticoes repeticoes;
    private final Duration espera;

    public FiltroDaRepeticao(Repeticoes repeticoes) {
        this(repeticoes, Duration.ofSeconds(30));
    }

    /** {@code espera}: quanto o pedido repetido aguarda o primeiro terminar antes de desistir. */
    FiltroDaRepeticao(Repeticoes repeticoes, Duration espera) {
        this.repeticoes = repeticoes;
        this.espera = espera;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest pedido) {
        return !ESCRITAS.contains(pedido.getMethod()) || !FiltroDaSessao.caminho(pedido).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        var quem = quem(pedido);
        var tipo = pedido.getContentType();
        if (quem == null || (tipo != null && !tipo.toLowerCase().startsWith("application/json"))) {
            cadeia.doFilter(pedido, resposta);
            return;
        }
        // O corpo é lido aqui para ser comparado, e devolvido inteiro a quem vem depois.
        var entrada = pedido.getInputStream();
        var corpo = entrada.readNBytes(MAXIMO + 1);
        if (corpo.length > MAXIMO) {
            cadeia.doFilter(new PedidoRelido(pedido, new SequenceInputStream(new ByteArrayInputStream(corpo), entrada)), resposta);
            return;
        }

        var consulta = pedido.getQueryString();
        var chave = new Repeticoes.Chave(quem, pedido.getMethod(),
                FiltroDaSessao.caminho(pedido) + (consulta == null ? "" : "?" + consulta));
        var meu = new Repeticoes.Pedido(digest(corpo));
        var dono = repeticoes.entrar(chave, meu);
        if (dono != meu) {
            repetir(dono, resposta);
            return;
        }

        var guardando = new ContentCachingResponseWrapper(resposta);
        Repeticoes.Guardada guardada = null;
        try {
            cadeia.doFilter(new PedidoRelido(pedido, new ByteArrayInputStream(corpo)), guardando);
            var saida = guardando.getContentAsByteArray();
            guardada = new Repeticoes.Guardada(guardando.getStatus(), guardando.getContentType(),
                    saida.length <= MAXIMO ? saida : null);
        } finally {
            // Sempre: quem estiver esperando por este pedido não pode ficar preso se ele estourar.
            repeticoes.terminar(chave, meu, guardada);
            guardando.copyBodyToResponse();
        }
    }

    /** A resposta do primeiro pedido, de novo. Se ele ainda roda, espera por ela. */
    private void repetir(Repeticoes.Pedido primeiro, HttpServletResponse resposta) throws IOException {
        Repeticoes.Guardada guardada;
        try {
            guardada = primeiro.resposta.get(espera.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            resposta.setHeader(CABECALHO, "1");
            Respostas.erro(resposta, 409, "Esta ação já está em andamento. Aguarde ela terminar.");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        resposta.setHeader(CABECALHO, "1");
        if (guardada == null || guardada.corpo() == null) {
            Respostas.erro(resposta, 409, "Esta ação acabou de ser pedida. Atualize a página para ver como ficou.");
            return;
        }
        resposta.setStatus(guardada.status());
        if (guardada.tipo() != null) {
            resposta.setContentType(guardada.tipo());
        }
        resposta.setContentLength(guardada.corpo().length);
        resposta.getOutputStream().write(guardada.corpo());
    }

    /** Quem pede, do jeito que separa uma pessoa da outra; {@code null} quando não dá para saber. */
    private static String quem(HttpServletRequest pedido) {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof Identidade ident) {
            return ident.canal() + ":" + ident.usuarioId();
        }
        return COMPRA.matcher(FiltroDaSessao.caminho(pedido)).matches() ? "ip:" + AutenticacaoPortal.ip(pedido) : null;
    }

    private static String digest(byte[] corpo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O pedido com o corpo de volta: quem lê depois recebe os mesmos bytes que chegaram. */
    private static final class PedidoRelido extends HttpServletRequestWrapper {

        private final InputStream corpo;

        PedidoRelido(HttpServletRequest pedido, InputStream corpo) {
            super(pedido);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private boolean acabou;

                @Override
                public int read() throws IOException {
                    var b = corpo.read();
                    acabou = b < 0;
                    return b;
                }

                @Override
                public int read(byte[] destino, int inicio, int quantos) throws IOException {
                    var lidos = corpo.read(destino, inicio, quantos);
                    acabou = lidos < 0;
                    return lidos;
                }

                @Override
                public boolean isFinished() {
                    return acabou;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener ouvinte) {
                    throw new UnsupportedOperationException("leitura assíncrona não é usada no portal");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            var codificacao = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    codificacao == null ? StandardCharsets.UTF_8 : Charset.forName(codificacao)));
        }
    }
}
