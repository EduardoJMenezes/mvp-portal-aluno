package br.com.plataforma.comum;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Traduz "1-14", "15,18,22" ou "Q01-Q03" no conjunto de números.
 *
 * <p>É assim que o professor fala ao montar o curso — "da questão 1 até a 14 é o K01" —, então é
 * assim que o comando aceita.
 */
public final class Faixa {

    private Faixa() {}

    public static Set<Integer> interpretar(String texto) {
        var numeros = new LinkedHashSet<Integer>();

        for (var parte : (texto == null ? "" : texto).replace(';', ',').split(",")) {
            var limpo = parte.strip().toUpperCase(java.util.Locale.ROOT).replace("Q", "");
            if (limpo.isEmpty()) {
                continue;
            }
            if (limpo.contains("-")) {
                var corte = limpo.indexOf('-');
                var a = numero(limpo.substring(0, corte), "Faixa inválida: '%s'. Use algo como '1-14'.", limpo);
                var b = numero(limpo.substring(corte + 1), "Faixa inválida: '%s'. Use algo como '1-14'.", limpo);
                if (a > b) {
                    var troca = a;
                    a = b;
                    b = troca;
                }
                for (int n = a; n <= b; n++) {
                    numeros.add(n);
                }
            } else {
                numeros.add(numero(limpo, "Número inválido: '%s'. Use '1-14' ou '15,18,22'.", limpo));
            }
        }
        return numeros;
    }

    /** O número que o nome do item carrega: "Q04" vira 4, "Aula 1" vira 1, sem dígito vira 0. */
    public static int doNome(String nome) {
        var digitos = new StringBuilder();
        for (var c : (nome == null ? "" : nome).toCharArray()) {
            if (Character.isDigit(c)) {
                digitos.append(c);
            }
        }
        if (digitos.isEmpty() || digitos.length() > 9) {
            return 0;
        }
        return Integer.parseInt(digitos.toString());
    }

    private static int numero(String texto, String recado, String parte) {
        try {
            return Integer.parseInt(texto.strip());
        } catch (NumberFormatException e) {
            throw new RegraDeNegocio(recado.formatted(parte));
        }
    }
}
