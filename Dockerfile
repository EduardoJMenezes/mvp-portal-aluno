FROM node:22-bookworm-slim AS frontend-build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Portal em Next.js exportado como site estático (frontend/out), servido pela API.
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/mcp

WORKDIR /app

# LibreOffice sem interface: o importador de simulado converte com ele as
# figuras em formato antigo do Word (EMF, WMF, prévia de "Equação 3.0") para
# PNG. Pesa algumas centenas de MB — ver docs/IMPORTADOR-SIMULADO.md.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-draw-nogui fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
COPY mcp/ ./mcp/
RUN python -m pip install --no-cache-dir .

COPY --from=frontend-build /app/frontend/out ./frontend/out
COPY scripts/ ./scripts/

# A migração vem antes do servidor: se ela falhar, o container não sobe e o
# Railway mantém a versão anterior no ar (ver app/migracoes.py).
# --forwarded-allow-ips: o container só é alcançado pelo proxy do Railway, e é
# do X-Forwarded-For que sai o IP do limite de tentativas de login.
# Sem argumento, `app.servir` lê PAPEL e WEB_CONCURRENCY do ambiente e cai no
# padrão de sempre: os dois papéis num processo só. Cada serviço da Railway
# sobrescreve isto com o seu comando — `python -m app.servir portal --workers 4`
# ou `python -m app.servir mcp` —, que é onde o papel fica visível no painel.
# Ver docs/RAILWAY-PASSO-A-PASSO.md.
CMD ["python", "-m", "app.servir"]
