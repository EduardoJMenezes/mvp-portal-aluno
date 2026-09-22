package br.com.plataforma.taxonomia;

/** Uma classificação, como as respostas a mostram: o assunto e, quando houver, o sub-assunto. */
public record Etiqueta(String assunto, String subassunto) {

    public static Etiqueta de(VideoAssunto vinculo) {
        return new Etiqueta(vinculo.getAssunto().getNome(),
                vinculo.getSubassunto() == null ? null : vinculo.getSubassunto().getNome());
    }
}
