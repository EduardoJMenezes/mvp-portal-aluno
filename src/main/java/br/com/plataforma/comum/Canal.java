package br.com.plataforma.comum;

/**
 * Por onde a identidade entrou.
 *
 * <p>Autorização é por identidade, não por canal: o canal só decide o que exige um humano de
 * fato ({@link Identidade#exigirHumanoNoPortal}).
 */
public enum Canal {
    PORTAL,
    MCP,
    /** O .docx que chegou pelo link de envio, pedido por alguém no MCP. */
    DOCX,
    /** A gravação que o Zoom avisou que ficou pronta, de uma aula agendada pelo professor. */
    ZOOM
}
