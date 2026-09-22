package br.com.plataforma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres de verdade, na versão de produção: índice parcial e substring em bytea não existem no H2.
 *
 * <p>Sem Docker na máquina, aponte {@code SPRING_DATASOURCE_URL} (e usuário/senha) para um banco
 * local <b>de teste</b> — a suíte apaga tudo entre um teste e outro — e o contêiner não sobe.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "spring.datasource.url", matchIfMissing = true, havingValue = "nunca-e-este-valor")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }
}
