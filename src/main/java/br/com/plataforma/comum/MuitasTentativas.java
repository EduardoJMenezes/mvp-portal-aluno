package br.com.plataforma.comum;

/** Tentativas demais de login numa janela: a conta ou o IP esperam {@code segundos}. */
public final class MuitasTentativas extends ErroDominio {

    private final int segundos;

    public MuitasTentativas(int segundos) {
        super("Muitas tentativas. Tente de novo em %d segundos.".formatted(segundos));
        this.segundos = segundos;
    }

    public int getSegundos() {
        return segundos;
    }
}
