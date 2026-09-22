package br.com.plataforma.portal;

import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.contas.Senhas;
import br.com.plataforma.materiais.MateriaisServico;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.ProvaDoAluno;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Portal do aluno. Nada aqui filtra por turma no cliente: as rotas devolvem exatamente o que os
 * serviços deixam a identidade ver (seção 11).
 */
@RestController
@RequestMapping("/api/aluno")
public class AlunoPortal {

    private final CatalogoServico catalogo;
    private final ProvaDoAluno prova;
    private final QuestoesServico questoes;
    private final MateriaisServico materiais;
    private final AulasServico aulas;
    private final ObjectMapper json;

    public AlunoPortal(CatalogoServico catalogo, ProvaDoAluno prova, QuestoesServico questoes,
            MateriaisServico materiais, AulasServico aulas, ObjectMapper json) {
        this.catalogo = catalogo;
        this.prova = prova;
        this.questoes = questoes;
        this.materiais = materiais;
        this.aulas = aulas;
        this.json = json;
    }

    /**
     * A árvore do curso, com ETag: é a rota mais chamada do portal, e quem volta recebe um "não
     * mudou" de 200 bytes em vez de 48 KB.
     */
    @GetMapping("/conteudo")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> conteudo(@AuthenticationPrincipal Identidade ident, HttpServletRequest pedido) {
        var corpo = json.writeValueAsBytes(catalogo.conteudoDoAluno(ident, Instant.now()));
        var etiqueta = "\"" + Senhas.sha256(new String(corpo, java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32) + "\"";
        var resposta = ResponseEntity.status(etiqueta.equals(pedido.getHeader("If-None-Match")) ? 304 : 200)
                .header(HttpHeaders.ETAG, etiqueta)
                // `no-cache` não é "não guarde": é "guarde e pergunte antes de usar".
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .contentType(MediaType.APPLICATION_JSON);
        return etiqueta.equals(pedido.getHeader("If-None-Match")) ? resposta.build() : resposta.body(corpo);
    }

    // --- simulados -----------------------------------------------------------

    @GetMapping("/simulados")
    public List<ProvaDoAluno.SimuladoDoAluno> simulados(@AuthenticationPrincipal Identidade ident) {
        return prova.listar(ident, Instant.now());
    }

    @GetMapping("/simulados/{simulado}")
    public ProvaDoAluno.Prova abrir(@AuthenticationPrincipal Identidade ident, @PathVariable String simulado) {
        return prova.abrir(ident, simulado, Instant.now());
    }

    public record RespostaIn(Integer questaoId, String alternativa) {}

    @PostMapping("/simulados/{simulado}/responder")
    public ProvaDoAluno.Registrada responder(@AuthenticationPrincipal Identidade ident,
            @PathVariable String simulado, @RequestBody RespostaIn dados) {
        return prova.responder(ident, simulado, dados.questaoId(), dados.alternativa(), Instant.now());
    }

    @PostMapping("/simulados/{simulado}/entregar")
    public ProvaDoAluno.Entregue entregar(@AuthenticationPrincipal Identidade ident, @PathVariable String simulado) {
        return prova.entregar(ident, simulado, Instant.now());
    }

    @GetMapping("/simulados/{simulado}/resultado")
    public ProvaDoAluno.Resultado resultado(@AuthenticationPrincipal Identidade ident, @PathVariable String simulado) {
        return prova.resultado(ident, simulado, Instant.now());
    }

    @GetMapping("/desempenho")
    public ProvaDoAluno.Historico desempenho(@AuthenticationPrincipal Identidade ident) {
        return prova.historico(ident, Instant.now());
    }

    /** A figura de uma questão, com a sessão — não por URL pública: antes de a prova abrir, ela adiantaria a questão. */
    @GetMapping("/figuras/{figuraId}")
    public ResponseEntity<byte[]> figura(@AuthenticationPrincipal Identidade ident, @PathVariable Integer figuraId) {
        var imagem = questoes.figura(ident, figuraId, Instant.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, imagem.tipo())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(imagem.conteudo());
    }

    // --- materiais -----------------------------------------------------------

    @GetMapping("/materiais")
    public List<MateriaisServico.Resumo> materiais(@AuthenticationPrincipal Identidade ident) {
        return materiais.listar(ident);
    }

    private static ResponseEntity.BodyBuilder cabecalhosDoArquivo(int status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                // Sem download e sem cópia no disco do navegador: o material sai do portal só
                // enquanto a sessão está aberta.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff");
    }

    /**
     * O PDF, em faixas de bytes. Cada faixa passa pela sessão: o endereço não é link que se
     * repassa. É assim que o leitor abre a página 180 de uma apostila de 323 sem baixar o resto.
     */
    @GetMapping("/materiais/{material}/arquivo")
    public ResponseEntity<?> arquivo(@AuthenticationPrincipal Identidade ident, @PathVariable String material,
            HttpServletRequest pedido) {
        var m = materiais.abrirArquivo(ident, material);
        long total = m.getTamanho();
        var faixa = faixa(pedido.getHeader("Range"), total);
        var tipo = MediaType.parseMediaType(MateriaisServico.TIPO);

        if (faixa == null) {
            // O content-length é o que faz o leitor passar a pedir faixas em vez de arrastar o arquivo inteiro.
            var corpo = new org.springframework.core.io.InputStreamResource(new java.io.InputStream() {
                private long lido = 0;
                private byte[] pedaco = new byte[0];
                private int posicao = 0;

                @Override
                public int read() throws java.io.IOException {
                    var b = new byte[1];
                    return read(b, 0, 1) == -1 ? -1 : b[0] & 0xFF;
                }

                @Override
                public int read(byte[] destino, int de, int quantos) {
                    if (posicao >= pedaco.length) {
                        if (lido >= total) {
                            return -1;
                        }
                        pedaco = materiais.fatia(m.getId(), lido, (int) Math.min(1 << 20, total - lido));
                        posicao = 0;
                        if (pedaco.length == 0) {
                            return -1;
                        }
                        lido += pedaco.length;
                    }
                    var n = Math.min(quantos, pedaco.length - posicao);
                    System.arraycopy(pedaco, posicao, destino, de, n);
                    posicao += n;
                    return n;
                }
            });
            return cabecalhosDoArquivo(200).contentType(tipo).contentLength(total).body(corpo);
        }
        var inicio = faixa[0];
        var fim = faixa[1];
        if (inicio > fim || inicio >= total) {
            return cabecalhosDoArquivo(416).header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
        }
        return cabecalhosDoArquivo(206).contentType(tipo)
                .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(inicio, fim, total))
                .body(materiais.fatia(m.getId(), inicio, (int) (fim - inicio + 1)));
    }

    /** Traduz o {@code Range} em [início, fim]. Uma faixa só — é o que o leitor pede. */
    static long[] faixa(String cabecalho, long total) {
        if (cabecalho == null || !cabecalho.strip().startsWith("bytes=")) {
            return null;
        }
        var texto = cabecalho.strip().substring(6).split(",")[0].strip();
        var corte = texto.indexOf('-');
        if (corte < 0) {
            return null;
        }
        var de = texto.substring(0, corte).strip();
        var ate = texto.substring(corte + 1).strip();
        try {
            long inicio;
            long fim;
            if (!de.isEmpty()) {
                inicio = Long.parseLong(de);
                fim = ate.isEmpty() ? total - 1 : Long.parseLong(ate);
            } else if (!ate.isEmpty()) {
                inicio = Math.max(0, total - Long.parseLong(ate));
                fim = total - 1;
            } else {
                return null;
            }
            return new long[] {inicio, Math.min(fim, total - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @GetMapping("/materiais/{material}/anotacoes")
    public MateriaisServico.Anotacoes anotacoes(@AuthenticationPrincipal Identidade ident, @PathVariable String material) {
        return materiais.anotacoes(ident, material);
    }

    @PutMapping("/materiais/{material}/anotacoes/{pagina}")
    public MateriaisServico.AnotacaoGravada salvarAnotacao(@AuthenticationPrincipal Identidade ident,
            @PathVariable String material, @PathVariable int pagina, @RequestBody Map<String, Object> dados) {
        return materiais.salvarAnotacao(ident, material, pagina, dados, Instant.now());
    }

    // --- aulas ao vivo -------------------------------------------------------

    @GetMapping("/aulas")
    public List<AulasServico.Resumo> aulas(@AuthenticationPrincipal Identidade ident) {
        return aulas.listar(ident, Instant.now());
    }

    /** O link <b>daquele</b> aluno, conferindo acesso e horário. Nasce aqui, no clique. */
    @PostMapping("/aulas/{aula}/entrar")
    public AulasServico.Entrada entrar(@AuthenticationPrincipal Identidade ident, @PathVariable String aula) {
        return aulas.entrar(ident, aula, Instant.now());
    }
}
