package br.com.plataforma.comum;

/** Recurso inexistente. A mensagem lista o que existe, para o modelo se corrigir. */
public final class NaoEncontrado extends ErroDominio {

    public NaoEncontrado(String mensagem) {
        super(mensagem);
    }
}
