package br.com.plataforma.aulas;

import java.time.Instant;

/**
 * O que a plataforma faz no Zoom: abre a sala da aula, e nada além disso.
 *
 * <p>A conta é dividida com outra plataforma, que tem aulas rodando. Por isso <b>não existe
 * método de listagem</b> aqui, e todo id vem da nossa tabela: não há caminho no código para uma
 * reunião que a plataforma não criou. Ver docs/AULAS-AO-VIVO.md.
 */
public interface Zoom {

    record Sala(String id, String joinUrl, String senha) {}

    Sala criarAula(String titulo, Instant inicio, int minutos, String descricao, boolean gravar);

    void editarAula(String meetingId, String titulo, Instant inicio, int minutos);

    void cancelarAula(String meetingId);

    /** O link do professor expira em 2 h, então é sempre buscado na hora. */
    String linkDeInicio(String meetingId);

    /** Inscreve o aluno e devolve o link <b>dele</b>. Quem chama guarda e não pede de novo. */
    String inscrever(String meetingId, String nome, String sobrenome, String email);
}
