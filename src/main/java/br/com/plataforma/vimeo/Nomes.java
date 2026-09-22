package br.com.plataforma.vimeo;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Leitura do número da questão no título do vídeo do Vimeo.
 *
 * <p>A API não expõe ordem manual de pasta, e o acervo real devolve os vídeos fora de ordem. Quem
 * carrega a ordem é o título: {@code Q04}, {@code Q30}, {@code Q52}. Esse número é o da
 * <b>apostila</b>, e é assim que o professor e o aluno se referem à questão.
 */
public final class Nomes {

    private static final List<Map.Entry<String, Pattern>> PADROES = List.of(
            Map.entry("Q + número", Pattern.compile("\\bQ\\s*0*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("questão + número", Pattern.compile("\\bquest[aã]o\\s*0*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("prefixo numérico", Pattern.compile("^\\s*0*(\\d{1,4})\\s*[-–—.)]")));

    private Nomes() {}

    /** O número lido de um título, e de que jeito: a confiança é o que o preview mostra ao professor. */
    public record NumeroInferido(Integer numero, String padrao, String confianca) {}

    public static NumeroInferido inferirNumero(String titulo) {
        var texto = titulo == null ? "" : titulo.strip();
        if (texto.isEmpty()) {
            return new NumeroInferido(null, null, "nenhuma");
        }
        for (var p : PADROES) {
            var m = p.getValue().matcher(texto);
            if (m.find()) {
                // Título que é só o código ("Q04") não deixa dúvida; título longo com um número no
                // meio pode ser outra coisa.
                var confianca = texto.length() <= 12 || !p.getKey().equals("Q + número") ? "alta" : "baixa";
                return new NumeroInferido(Integer.parseInt(m.group(1)), p.getKey(), confianca);
            }
        }
        return new NumeroInferido(null, null, "nenhuma");
    }
}
