package br.com.plataforma.comum;

/**
 * A categoria livre de cada feature (decisão 0009 do cofre): "Rodmelo", "Nacional", "Monitoria".
 * É o que o botão do menu usa para recortar a feature. Sem categoria, o item só aparece no botão
 * que mostra a feature inteira.
 */
public final class Categoria {

    public static final int MAXIMO = 60;

    private Categoria() {}

    /** Sem espaços nas pontas; vazia vira {@code null}, que é "sem categoria". */
    public static String limpar(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        var limpo = texto.strip();
        if (limpo.length() > MAXIMO) {
            throw new RegraDeNegocio("Categoria longa demais: até %d letras.".formatted(MAXIMO));
        }
        return limpo;
    }

    /** O filtro do botão casa com a categoria do item. Filtro vazio casa com tudo. */
    public static boolean casa(String filtro, String categoria) {
        return filtro == null || filtro.isBlank() || filtro.strip().equalsIgnoreCase(categoria == null ? "" : categoria);
    }
}
