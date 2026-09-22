package br.com.plataforma.comum;

/** Identidade válida, mas sem permissão para esta operação ou recurso. */
public final class NaoAutorizado extends ErroDominio {

    public NaoAutorizado(String mensagem) {
        super(mensagem);
    }
}
