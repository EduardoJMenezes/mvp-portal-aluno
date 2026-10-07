package br.com.plataforma.estrutura;

import br.com.plataforma.comum.RegraDeNegocio;
import java.util.List;
import java.util.Locale;

/**
 * Os ícones que um módulo pode levar no cartão do aluno.
 *
 * <p>Vocabulário fechado, como {@code Papel} e {@code Status}: o nome guardado é o da lista, e o
 * portal sabe desenhar cada um (frontend/lib/icones.ts tem a mesma lista, com o desenho). Ícone
 * novo entra nos dois lugares.
 */
public final class IconeDoModulo {

    public static final List<String> CATALOGO = List.of(
            "atomo", "frasco", "bequer", "tubo-de-ensaio", "pipeta", "microscopio", "calculadora",
            "tabela", "ligacoes", "hexagono", "cubo", "funil", "gota", "chama", "termometro", "raio",
            "bateria", "balanca", "velocimetro", "orbita", "radiacao", "dna", "folha", "reciclagem",
            "camadas", "grafico", "livro", "prancheta", "capelo", "trofeu", "estrela", "lampada");

    /** O que apaga a escolha e devolve o módulo ao ícone escolhido pelo nome. */
    public static final String AUTOMATICO = "automatico";

    private IconeDoModulo() {}

    /** O nome do catálogo, ou {@code null} para "automático". Qualquer outra coisa é erro que lista o que existe. */
    public static String validar(String texto) {
        var limpo = texto == null ? "" : texto.strip().toLowerCase(Locale.ROOT);
        if (limpo.isEmpty() || limpo.equals(AUTOMATICO)) {
            return null;
        }
        if (!CATALOGO.contains(limpo)) {
            throw new RegraDeNegocio("Ícone '%s' não existe. Ícones: %s — ou '%s', para o portal escolher pelo nome do módulo."
                    .formatted(texto, String.join(", ", CATALOGO), AUTOMATICO));
        }
        return limpo;
    }
}
