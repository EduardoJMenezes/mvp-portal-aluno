package br.com.plataforma.importacoes;

import java.util.regex.Pattern;

/**
 * O que se tira do primeiro bloco quando o Claude aponta uma faixa.
 *
 * <p>O professor manda "o enunciado é 12-18"; o bloco 12 começa com "12." ou "Questão 12:". Esse
 * pedaço é rótulo do documento, não texto da questão — sai antes de virar enunciado.
 */
final class Rotulos {

    /** "a)", "(b)", "**c)**" — o rótulo da alternativa. */
    static final Pattern ALTERNATIVA =
            Pattern.compile("^\\s*(?:\\*{1,3})?\\s*\\\\?\\(?[a-eA-E]\\\\?\\)(?:\\*{1,3})?\\s*");

    /** "12.", "12)", "Questão 12:" — a numeração da questão. */
    static final Pattern NUMERO = Pattern.compile(
            "^\\s*(?:\\*{1,3})?\\s*(?:quest[aã]o\\s*\\d{1,3}\\s*[.):\\-–]?|\\d{1,3}\\s*[.)])(?:\\*{1,3})?\\s*",
            Pattern.CASE_INSENSITIVE);

    /** "Resolução:", "Resolução comentada:". */
    static final Pattern RESOLUCAO = Pattern.compile(
            "^\\s*(?:\\*{1,3})?\\s*resolu[cç][aã]o(?:\\s+comentada)?\\s*:?\\s*(?:\\*{1,3})?\\s*",
            Pattern.CASE_INSENSITIVE);

    private Rotulos() {}
}
