# A API em Java. O outro Dockerfile, na raiz, é do processo Python (portal +
# adaptador MCP) — dois serviços no Railway, um repositório só.
#
# Ver docs/RAILWAY-PASSO-A-PASSO.md: o serviço do Python precisa de
# API_BASE_URL e SERVICO_TOKEN apontando para este, e os dois compartilham o
# mesmo Postgres enquanto o portal não for portado.

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# As dependências primeiro, na própria camada: mexer em src/ não refaz o
# download de meio Maven Central a cada deploy.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ ./src/
# Os testes são da CI, que sobe Postgres pelo Testcontainers — dentro do build
# do Railway não há Docker para isso, e um deploy não é o lugar de descobrir
# que a suíte quebrou.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app
COPY --from=build /build/target/api-*.jar ./api.jar

# O Flyway roda na partida com baseline-version 1: banco que já tem o schema é
# marcado sem executar nada, banco vazio recebe a V1. Se a migração falhar, a
# aplicação não sobe e o Railway mantém a versão anterior no ar.
EXPOSE 8080
CMD ["java", "-jar", "api.jar"]
