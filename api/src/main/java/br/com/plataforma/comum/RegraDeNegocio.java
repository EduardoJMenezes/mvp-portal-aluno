package br.com.plataforma.comum;

/**
 * Operação válida na forma, recusada pelo estado ou pelas regras.
 *
 * <p>{@code non-sealed} porque a {@code AprovacaoNecessaria} vai estender esta quando a
 * publicação for portada.
 */
public non-sealed class RegraDeNegocio extends ErroDominio {

    public RegraDeNegocio(String mensagem) {
        super(mensagem);
    }
}
