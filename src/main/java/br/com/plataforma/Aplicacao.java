package br.com.plataforma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Sem o usuário em memória que o Spring Security cria sozinho: ninguém entra por senha nesta
 * API, e ele ainda imprimiria a senha gerada no log de produção.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class Aplicacao {

    public static void main(String[] args) {
        SpringApplication.run(Aplicacao.class, args);
    }
}
