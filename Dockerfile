# A plataforma: a API em Java servindo o portal exportado pelo Next.
#
# Um serviço só no Railway (`app`). O adaptador MCP mora em outro repositório
# (mvp-portal-mcp) e fala com este por HTTP, na rede privada.

FROM node:22-bookworm-slim AS frontend-build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Portal em Next.js exportado como site estático (frontend/out), servido pela API.
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
# As dependências primeiro, na própria camada: mexer em src/ não refaz o
# download de meio Maven Central a cada deploy.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ ./src/
# Os testes são da CI, que sobe Postgres pelo Testcontainers — dentro do build
# do Railway não há Docker para isso.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app
COPY --from=build /build/target/api-*.jar ./api.jar
COPY --from=frontend-build /app/frontend/out ./frontend/out
ENV FRONTEND_DIR=/app/frontend/out

# O Flyway roda na partida com baseline-version 1: banco que já tem o schema é
# marcado sem executar nada, banco vazio recebe a V1. Se a migração falhar, a
# aplicação não sobe e o Railway mantém a versão anterior no ar.
EXPOSE 8080
CMD ["java", "-jar", "api.jar"]
