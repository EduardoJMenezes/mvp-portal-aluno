package br.com.plataforma.questoes;

import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As figuras das questões.
 *
 * <p>O texto aponta para elas no lugar exato onde aparecem — {@code ![](figura:123)} —, e é assim
 * que uma questão tem quantas figuras precisar.
 *
 * <p>Os bytes entram e saem por consulta nativa de propósito: mapeá-los na entidade traria a
 * imagem inteira em toda listagem de questão. Mesmo motivo do PDF da apostila.
 */
@Service
public class FigurasServico {

    public static final Pattern REFERENCIA = Pattern.compile("figura:(\\d+)");

    /** A marca que a transcrição deixa no lugar da figura que não deu para transcrever. */
    public static final String PENDENTE = "figura:pendente";

    private static final long LIMITE_DA_IMAGEM = 10L * 1024 * 1024;

    private final FiguraRepositorio figuras;

    @PersistenceContext
    private EntityManager em;

    public FigurasServico(FiguraRepositorio figuras) {
        this.figuras = figuras;
    }

    /** Guarda a imagem e devolve o id que o texto vai referenciar. */
    @Transactional
    public Integer guardar(byte[] conteudo, String tipo, String nome) {
        var recortado = nome == null ? null : nome.substring(0, Math.min(nome.length(), 200));
        return ((Number) em.createNativeQuery(
                "INSERT INTO images (conteudo, tipo, nome) VALUES (:conteudo, :tipo, :nome) RETURNING id")
                .setParameter("conteudo", conteudo)
                .setParameter("tipo", tipo)
                .setParameter("nome", recortado)
                .getSingleResult()).intValue();
    }

    @Transactional(readOnly = true)
    public byte[] bytesDe(Integer figuraId) {
        var achadas = em.createNativeQuery("SELECT conteudo FROM images WHERE id = :id")
                .setParameter("id", figuraId).getResultList();
        if (achadas.isEmpty()) {
            throw new NaoEncontrado("Figura %d não existe.".formatted(figuraId));
        }
        return (byte[]) achadas.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Figura> daQuestao(Questao questao) {
        return figuras.findByQuestaoOrderByIdAsc(questao);
    }

    /** Liga cada figura citada no texto da questão à parte onde ela aparece. */
    @Transactional
    public void ligarAs(Questao questao) {
        var partes = new LinkedHashMap<Integer, String>();
        anotar(partes, questao.getEnunciado(), "ENUNCIADO");
        anotar(partes, questao.getResolucaoComentada(), "RESOLUCAO");
        questao.getAlternativas().forEach(a -> anotar(partes, a.getTexto(), "ALTERNATIVA"));

        partes.forEach((id, parte) -> figuras.findById(id).ifPresent(f -> f.ligarA(questao, parte)));
    }

    /** Os ids de figura citados nos textos, em ordem — é o que a revisão mostra ao Claude. */
    public static List<Integer> citadas(String... textos) {
        var ids = new TreeSet<Integer>();
        for (var texto : textos) {
            if (texto == null) {
                continue;
            }
            var achadas = REFERENCIA.matcher(texto);
            while (achadas.find()) {
                ids.add(Integer.valueOf(achadas.group(1)));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * O tipo da figura, lido dos <b>bytes</b>. Recusa o que não for imagem de verdade.
     *
     * <p>Pela assinatura, não pela extensão: quem envia escolhe o nome do arquivo, não o
     * conteúdo dele.
     */
    public static String tipoDaImagem(byte[] conteudo) {
        if (conteudo == null || conteudo.length == 0) {
            throw new RegraDeNegocio("Arquivo vazio.");
        }
        if (conteudo.length > LIMITE_DA_IMAGEM) {
            throw new RegraDeNegocio(
                    "A imagem tem %.1f MB; o limite é %d MB. Recorte só a figura da questão."
                            .formatted(conteudo.length / 1024.0 / 1024, LIMITE_DA_IMAGEM / 1024 / 1024));
        }
        if (comeca(conteudo, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (comeca(conteudo, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (comeca(conteudo, 'R', 'I', 'F', 'F') && conteudo.length > 11
                && comeca(java.util.Arrays.copyOfRange(conteudo, 8, 12), 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (comeca(conteudo, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        throw new RegraDeNegocio("Formato não aceito. Envie PNG, JPEG, WEBP ou GIF.");
    }

    private static boolean comeca(byte[] conteudo, int... assinatura) {
        if (conteudo.length < assinatura.length) {
            return false;
        }
        for (int n = 0; n < assinatura.length; n++) {
            if ((conteudo[n] & 0xFF) != assinatura[n]) {
                return false;
            }
        }
        return true;
    }

    /** A figura pelo id, ou o erro que diz a verdade: ela não existe — nem solta, nem na questão. */
    @Transactional
    public Figura exigir(Integer figuraId) {
        return figuras.findById(figuraId).orElseThrow(() ->
                new NaoEncontrado("Figura %d não existe.".formatted(figuraId)));
    }

    /** Troca os bytes sem mexer no texto: `figura:ID` segue apontando para ela. */
    @Transactional
    public void trocarBytes(Integer figuraId, byte[] conteudo, String tipo) {
        em.createNativeQuery("UPDATE images SET conteudo = :conteudo, tipo = :tipo WHERE id = :id")
                .setParameter("conteudo", conteudo)
                .setParameter("tipo", tipo)
                .setParameter("id", figuraId)
                .executeUpdate();
    }

    private static void anotar(Map<Integer, String> partes, String texto, String parte) {
        if (texto == null) {
            return;
        }
        var achadas = REFERENCIA.matcher(texto);
        while (achadas.find()) {
            partes.putIfAbsent(Integer.valueOf(achadas.group(1)), parte);
        }
    }
}
