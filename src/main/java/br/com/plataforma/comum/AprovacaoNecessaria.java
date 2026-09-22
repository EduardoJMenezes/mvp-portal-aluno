package br.com.plataforma.comum;

/**
 * Publicação barrada por falta de aprovação humana explícita.
 *
 * <p>A separação existe porque isto não é erro de uso: é o backend fazendo valer a regra
 * fundamental — a IA propõe, o humano aprova, o backend publica. A borda MCP usa este tipo como
 * gatilho para pedir a confirmação ao usuário.
 */
public final class AprovacaoNecessaria extends RegraDeNegocio {

    public AprovacaoNecessaria(String mensagem) {
        super(mensagem);
    }
}
