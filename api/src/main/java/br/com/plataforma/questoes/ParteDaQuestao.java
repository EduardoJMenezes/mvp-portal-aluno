package br.com.plataforma.questoes;

/** Onde a figura aparece — e, por isso, quando o aluno pode vê-la. */
public enum ParteDaQuestao {
    ENUNCIADO,
    ALTERNATIVA,
    /** A da resolução só depois que o simulado fecha. */
    RESOLUCAO
}
