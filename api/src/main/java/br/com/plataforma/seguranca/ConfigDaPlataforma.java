package br.com.plataforma.seguranca;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * O segredo que prova que o pedido veio do adaptador MCP.
 *
 * <p>Validado na partida: sem ele, ou com um fraco, a aplicação não sobe. É melhor um deploy que
 * falha do que uma porta de comando aberta com token vazio.
 */
@Validated
@ConfigurationProperties("plataforma")
public record ConfigDaPlataforma(
        @NotBlank(message = "defina SERVICO_TOKEN: é ele que prova que o comando veio do MCP")
        @Size(min = 32, message = "SERVICO_TOKEN precisa de pelo menos 32 caracteres")
        String tokenDeServico) {}
