package br.com.plataforma.taxonomia;

/**
 * Uma classificação com os ids, para a tela que a mostra e a troca: o assunto e, quando houver, o
 * sub-assunto.
 */
public record EtiquetaComId(Integer assuntoId, String assunto, Integer subassuntoId, String subassunto) {}
