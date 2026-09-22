package br.com.plataforma.materiais;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Pessoa;
import br.com.plataforma.contas.Usuario;
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
 * Materiais em PDF e o que cada aluno risca em cima deles.
 *
 * <p>Três regras: quem alcança o material é o backend que decide (§11); a anotação é sempre a de
 * quem está pedindo, e não há como passar o id de outra pessoa; o arquivo sai em fatias, com
 * {@code substring} sobre a coluna sem compressão.
 */
@Service
public class MateriaisServico {

    public static final int LIMITE_DO_ARQUIVO = 60 * 1024 * 1024;
    public static final int LIMITE_DA_ANOTACAO = 200 * 1024;
    public static final String TIPO = "application/pdf";

    private final MaterialRepositorio materiais;
    private final MaterialAnotacaoRepositorio anotacoes;
    private final ContasServico contas;
    private final tools.jackson.databind.ObjectMapper json;

    @PersistenceContext
    private EntityManager em;

    public MateriaisServico(MaterialRepositorio materiais, MaterialAnotacaoRepositorio anotacoes,
            ContasServico contas, tools.jackson.databind.ObjectMapper json) {
        this.materiais = materiais;
        this.anotacoes = anotacoes;
        this.contas = contas;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Material exigir(String referencia) {
        var texto = referencia == null ? "" : referencia.strip();
        var achado = texto.chars().allMatch(Character::isDigit) && !texto.isEmpty() && texto.length() <= 9
                ? materiais.findById(Integer.parseInt(texto)) : java.util.Optional.<Material>empty();
        return achado.orElseThrow(() -> {
            var disponiveis = materiais.findAllByOrderByIdAsc().stream()
                    .map(m -> "#%d %s".formatted(m.getId(), m.getTitulo())).collect(joining(", "));
            return new NaoEncontrado("Material '%s' não existe. Materiais: %s."
                    .formatted(referencia, disponiveis.isEmpty() ? "(nenhum)" : disponiveis));
        });
    }

    /** O material chega a quem pergunta? Publicado, e endereçado à turma ou à pessoa. */
    private boolean alcanca(Identidade ident, Material m) {
        if (m.getStatus() != Status.PUBLICADO) {
            return false;
        }
        if (m.getAlunos().stream().anyMatch(a -> a.getId().equals(ident.usuarioId()))) {
            return true;
        }
        var minhas = contas.turmasDoAluno(ident.usuarioId());
        return m.getTurmas().stream().anyMatch(t -> minhas.contains(t.getId()));
    }

    private void exigirAcesso(Identidade ident, Material m) {
        if (ident.eOperador() || alcanca(ident, m)) {
            return;
        }
        throw new NaoAutorizado("'%s' não está liberado para %s.".formatted(m.getTitulo(), ident.nome()));
    }

    public record Resumo(
            Integer materialId, String titulo, String arquivo, Integer tamanho, Status status,
            List<String> turmas, List<Pessoa> alunos, String criadoEm, String publicadoEm,
            Integer paginasAnotadas) {}

    private static Resumo resumo(Material m, Integer anotadas) {
        return new Resumo(m.getId(), m.getTitulo(), m.getArquivoNome(), m.getTamanho(), m.getStatus(),
                m.getTurmas().stream().map(Turma::getNome).toList(),
                m.getAlunos().stream().map(Pessoa::de).toList(),
                m.getCriadoEm() == null ? null : m.getCriadoEm().toString(),
                m.getPublicadoEm() == null ? null : m.getPublicadoEm().toString(), anotadas);
    }

    // --- leitura -------------------------------------------------------------

    /** Operador vê todos, inclusive os rascunhos; aluno, só o que o alcança. */
    @Transactional(readOnly = true)
    public List<Resumo> listar(Identidade ident) {
        var anotadas = new LinkedHashMap<Integer, Integer>();
        anotacoes.paginasPorMaterial(ident.usuarioId())
                .forEach(l -> anotadas.put((Integer) l[0], ((Number) l[1]).intValue()));
        return materiais.findAllByOrderByCriadoEmDesc().stream()
                .filter(m -> ident.eOperador() || alcanca(ident, m))
                .map(m -> resumo(m, anotadas.getOrDefault(m.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Resumo detalhar(Identidade ident, String referencia) {
        var m = exigir(referencia);
        exigirAcesso(ident, m);
        return resumo(m, null);
    }

    /** O material, já conferido o acesso — quem serve o arquivo pede por aqui. */
    @Transactional(readOnly = true)
    public Material abrirArquivo(Identidade ident, String referencia) {
        var m = exigir(referencia);
        exigirAcesso(ident, m);
        return m;
    }

    /**
     * Uma faixa de bytes do PDF — o que o leitor pede para abrir uma página. {@code substring} sobre
     * a coluna sem compressão faz o Postgres ler só o pedaço.
     */
    @Transactional(readOnly = true)
    public byte[] fatia(Integer materialId, long inicio, int tamanho) {
        if (tamanho <= 0) {
            return new byte[0];
        }
        var lido = em.createNativeQuery(
                "SELECT substring(conteudo from :inicio for :tamanho) FROM materials WHERE id = :id")
                .setParameter("inicio", (int) inicio + 1)
                .setParameter("tamanho", tamanho)
                .setParameter("id", materialId)
                .getResultList();
        return lido.isEmpty() || lido.getFirst() == null ? new byte[0] : (byte[]) lido.getFirst();
    }

    // --- o professor publicando ----------------------------------------------

    /** Recebe o PDF e o guarda em rascunho: nenhum aluno vê antes de publicar. */
    @Transactional
    public Resumo criar(Identidade ident, String titulo, byte[] conteudo, String arquivoNome,
            List<Turma> turmas, List<String> alunos) {
        ident.exigirOperador();
        var limpo = titulo == null ? "" : titulo.strip();
        if (limpo.isEmpty()) {
            throw new RegraDeNegocio("O material precisa de um título.");
        }
        validarPdf(conteudo);
        var nome = arquivoNome == null ? "" : arquivoNome.strip();

        var id = ((Number) em.createNativeQuery("""
                INSERT INTO materials (titulo, arquivo_nome, tipo, tamanho, conteudo, status,
                                       criado_por_id, alterado_por_id, alterado_em)
                VALUES (:titulo, :nome, :tipo, :tamanho, :conteudo, 'RASCUNHO', :quem, :quem, now())
                RETURNING id""")
                .setParameter("titulo", limpo)
                .setParameter("nome", nome.isEmpty() ? null : nome.substring(0, Math.min(nome.length(), 200)))
                .setParameter("tipo", TIPO)
                .setParameter("tamanho", conteudo.length)
                .setParameter("conteudo", conteudo)
                .setParameter("quem", ident.usuarioId())
                .getSingleResult()).intValue();
        var m = materiais.findById(id).orElseThrow();
        enderecar(m, turmas, alunos);
        return resumo(m, null);
    }

    /** Título, quem alcança, e publicar ou tirar do ar. */
    @Transactional
    public Resumo editar(Identidade ident, String referencia, String titulo, String status,
            List<Turma> turmas, List<String> alunos, Instant agora) {
        ident.exigirOperador();
        var m = exigir(referencia);
        if (titulo != null) {
            if (titulo.isBlank()) {
                throw new RegraDeNegocio("O material precisa de um título.");
            }
            m.mudarTitulo(titulo.strip());
        }
        if (turmas != null || alunos != null) {
            enderecar(m, turmas, alunos);
        }
        if (status != null) {
            var alvo = status.strip().toUpperCase(Locale.ROOT);
            if (!alvo.equals("RASCUNHO") && !alvo.equals("PUBLICADO")) {
                throw new RegraDeNegocio("Situação '%s' inválida. Use RASCUNHO ou PUBLICADO.".formatted(status));
            }
            if (alvo.equals("PUBLICADO") && m.getTurmas().isEmpty() && m.getAlunos().isEmpty()) {
                throw new RegraDeNegocio(("'%s' não chegaria a ninguém: escolha uma turma ou um aluno "
                        + "antes de publicar.").formatted(m.getTitulo()));
            }
            m.mudarStatus(Status.valueOf(alvo), agora);
        }
        m.tocar(ident);
        return resumo(m, null);
    }

    public record Removido(Integer materialId, String titulo, boolean reversivel) {}

    /** Remoção lógica: some da tela do aluno, e o que ele riscou continua guardado. */
    @Transactional
    public Removido remover(Identidade ident, String referencia) {
        ident.exigirOperador();
        var m = exigir(referencia);
        m.remover(ident);
        return new Removido(m.getId(), m.getTitulo(), true);
    }

    private static void validarPdf(byte[] conteudo) {
        if (conteudo == null || conteudo.length == 0) {
            throw new RegraDeNegocio("Arquivo vazio.");
        }
        if (conteudo.length > LIMITE_DO_ARQUIVO) {
            throw new RegraDeNegocio("O arquivo tem %.1f MB; o limite é %d MB."
                    .formatted(conteudo.length / 1024.0 / 1024, LIMITE_DO_ARQUIVO / 1024 / 1024));
        }
        var cabecalho = new String(conteudo, 0, Math.min(5, conteudo.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!cabecalho.startsWith("%PDF-")) {
            throw new RegraDeNegocio("Só entra PDF aqui.");
        }
    }

    /** Troca a lista de quem alcança. Vazia dos dois lados é ninguém — e aí não publica. */
    private void enderecar(Material m, List<Turma> turmas, List<String> alunos) {
        if (turmas != null) {
            var unicas = new LinkedHashMap<Integer, Turma>();
            turmas.forEach(t -> unicas.putIfAbsent(t.getId(), t));
            m.getTurmas().clear();
            m.getTurmas().addAll(unicas.values());
        }
        if (alunos != null) {
            var unicos = new LinkedHashMap<Integer, Usuario>();
            alunos.forEach(ref -> {
                var u = contas.resolverAluno(ref);
                unicos.putIfAbsent(u.getId(), u);
            });
            m.getAlunos().clear();
            m.getAlunos().addAll(unicos.values());
        }
        em.flush();
    }

    // --- o que o aluno risca -------------------------------------------------

    public record Anotacoes(Integer materialId, Map<String, Object> paginas) {}

    /** As páginas que <b>quem está pedindo</b> já riscou. Nunca as de outra pessoa. */
    @Transactional(readOnly = true)
    public Anotacoes anotacoes(Identidade ident, String referencia) {
        var m = exigir(referencia);
        exigirAcesso(ident, m);
        var paginas = new LinkedHashMap<String, Object>();
        for (var linha : anotacoes.findByMaterialIdAndUsuarioIdOrderByPaginaAsc(m.getId(), ident.usuarioId())) {
            var tracos = linha.getDados() == null ? null : linha.getDados().get("tracos");
            if (tracos instanceof List<?> lista && !lista.isEmpty()) {
                paginas.put(String.valueOf(linha.getPagina()), linha.getDados());
            }
        }
        return new Anotacoes(m.getId(), paginas);
    }

    public record AnotacaoGravada(Integer materialId, int pagina, int tracos) {}

    /** Grava uma página. É o que o salvamento automático chama, e ele chama muito. */
    @Transactional
    public AnotacaoGravada salvarAnotacao(Identidade ident, String referencia, int pagina,
            Map<String, Object> dados, Instant agora) {
        var m = exigir(referencia);
        exigirAcesso(ident, m);
        if (pagina < 1) {
            throw new RegraDeNegocio("Página inválida.");
        }
        var tracos = dados == null ? null : dados.get("tracos");
        if (!(tracos instanceof List<?> lista)) {
            throw new RegraDeNegocio("A anotação vai como {\"v\": 1, \"tracos\": [...]}.");
        }
        if (json.writeValueAsBytes(lista).length > LIMITE_DA_ANOTACAO) {
            throw new RegraDeNegocio(("A anotação desta página passou de %d KB. Apague alguns traços "
                    + "antes de continuar.").formatted(LIMITE_DA_ANOTACAO / 1024));
        }
        var linha = anotacoes.findByMaterialIdAndUsuarioIdAndPagina(m.getId(), ident.usuarioId(), pagina)
                .orElseGet(() -> anotacoes.save(new MaterialAnotacao(m.getId(), ident.usuarioId(), pagina)));
        linha.gravar(Map.of("v", 1, "tracos", lista), agora);
        return new AnotacaoGravada(m.getId(), pagina, lista.size());
    }
}
