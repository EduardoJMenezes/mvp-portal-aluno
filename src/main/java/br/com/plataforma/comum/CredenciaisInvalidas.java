package br.com.plataforma.comum;

/** E-mail ou senha errados. A mensagem é a mesma nos dois casos, de propósito. */
public final class CredenciaisInvalidas extends ErroDominio {

    public CredenciaisInvalidas() {
        super("E-mail ou senha incorretos.");
    }
}
