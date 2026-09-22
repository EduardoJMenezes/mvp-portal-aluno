package br.com.plataforma.comum;

/**
 * Falha do lado de fora: Vimeo, Zoom. A mensagem já vem escrita para gente, e o problema não é
 * deste servidor — por isso não é {@link ErroDominio}: vira 502, não 400.
 */
public class ServicoExterno extends RuntimeException {

    public ServicoExterno(String mensagem) {
        super(mensagem);
    }

    public ServicoExterno(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
