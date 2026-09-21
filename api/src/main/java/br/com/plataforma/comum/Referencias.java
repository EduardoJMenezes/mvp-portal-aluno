package br.com.plataforma.comum;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Locale;

/**
 * Achar pelo que o professor falou, não por id.
 *
 * <p>Quem chama pelo MCP diz "K01 - Introdução à química orgânica", não {@code modulo_id=7}.
 * Quando não acha, o erro lista o que existe — o agente se corrige sozinho em vez de insistir
 * num id inventado.
 *
 * <p>O casamento mora aqui; a <b>mensagem</b> fica com quem chama, porque cada assunto fala de
 * um jeito ("Há: …", "Assuntos: …", "Para criar um novo, use…").
 */
public final class Referencias {

    private Referencias() {}

    /** Ou achou um, ou sobraram vários parciais, ou nenhum. */
    public record Busca<T>(T achado, List<T> ambiguos) {

        public boolean achou() {
            return achado != null;
        }
    }

    public static <T extends Nomeavel> Busca<T> buscar(List<T> candidatos, String referencia) {
        var texto = referencia == null ? "" : referencia.strip().toLowerCase(Locale.ROOT);

        if (!texto.isEmpty() && texto.length() <= 18 && texto.chars().allMatch(Character::isDigit)) {
            var id = Long.parseLong(texto);
            for (var c : candidatos) {
                if (c.getId() == id) {
                    return new Busca<>(c, List.of());
                }
            }
        }

        for (var c : candidatos) {
            if (minusculo(c).equals(texto)) {
                return new Busca<>(c, List.of());
            }
        }

        var parciais = candidatos.stream().filter(c -> minusculo(c).contains(texto)).toList();
        if (parciais.size() == 1) {
            return new Busca<>(parciais.getFirst(), List.of());
        }
        return new Busca<>(null, parciais);
    }

    /** O formato da estrutura do curso: módulo, sub-módulo e item. */
    public static <T extends Nomeavel> T porNome(
            List<T> candidatos, String referencia, String rotulo, String onde) {
        var busca = buscar(candidatos, referencia);
        if (busca.achou()) {
            return busca.achado();
        }
        if (busca.ambiguos().size() > 1) {
            throw new RegraDeNegocio(
                    "'%s' casa com mais de um %s em %s: %s. Diga qual deles."
                            .formatted(referencia, rotulo, onde, nomes(busca.ambiguos())));
        }
        var ha = candidatos.isEmpty() ? "nenhum cadastrado" : nomes(candidatos);
        throw new NaoEncontrado("%s '%s' não existe em %s. Há: %s."
                .formatted(capitalizar(rotulo), referencia, onde, ha));
    }

    /** Nomes entre aspas simples, do jeito que as mensagens do domínio listam. */
    public static String nomes(List<? extends Nomeavel> lista) {
        return lista.stream().map(c -> "'" + c.getNome() + "'").collect(joining(", "));
    }

    private static String minusculo(Nomeavel c) {
        return c.getNome().toLowerCase(Locale.ROOT);
    }

    private static String capitalizar(String texto) {
        return texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
    }
}
