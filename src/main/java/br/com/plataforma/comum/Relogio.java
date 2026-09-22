package br.com.plataforma.comum;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * O fuso da plataforma.
 *
 * <p>O relógio entra como parâmetro nos serviços, nunca como {@code Instant.now()} escondido: é o
 * que permite testar prova aberta e prova fechada sem esperar.
 */
public final class Relogio {

    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter LEGIVEL =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private Relogio() {}

    /** Para mensagem: "10/10/2026 às 14:00". */
    public static String emBrasilia(Instant instante) {
        return instante == null ? null : LEGIVEL.format(instante.atZone(BRASILIA));
    }

    /** Para dado: ISO no fuso de Brasília, que o front e o modelo leem igual. */
    public static String iso(Instant instante) {
        return instante == null
                ? null
                : instante.atZone(BRASILIA).toOffsetDateTime().withNano(0).withSecond(0).toString();
    }
}
