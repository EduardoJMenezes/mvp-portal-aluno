package br.com.plataforma.vendas;

import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** De dez em dez minutos, tira o acesso que venceu: atraso além da tolerância, fim do mês cancelado. */
@Component
class RotinaDasVendas {

    private final VendasServico vendas;

    RotinaDasVendas(VendasServico vendas) {
        this.vendas = vendas;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void revogarVencidos() {
        var quantos = vendas.revogarVencidos(Instant.now());
        if (quantos > 0) {
            LoggerFactory.getLogger(RotinaDasVendas.class).info("{} acesso(s) vencido(s) retirado(s)", quantos);
        }
    }
}
