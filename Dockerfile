FROM node:22-bookworm-slim AS frontend-build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Portal em Next.js exportado como site estático (frontend/out), servido pelo backend.
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/backend

WORKDIR /app

# LibreOffice sem interface: o importador de simulado converte com ele as
# figuras em formato antigo do Word (EMF, WMF, prévia de "Equação 3.0") para
# PNG. Pesa algumas centenas de MB — ver docs/IMPORTADOR-SIMULADO.md.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-draw-nogui fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
COPY backend/ ./backend/
RUN python -m pip install --no-cache-dir .

COPY --from=frontend-build /app/frontend/out ./frontend/out
COPY scripts/ ./scripts/

# A migração vem antes do servidor: se ela falhar, o container não sobe e o
# Railway mantém a versão anterior no ar (ver app/migracoes.py).
# --forwarded-allow-ips: o container só é alcançado pelo proxy do Railway, e é
# do X-Forwarded-For que sai o IP do limite de tentativas de login.
# WEB_CONCURRENCY: quantos processos servem o portal. Um só usa um núcleo dos
# oito — é o teto de ~60 pedidos por segundo medido em docs/CARGA.md. Mais de um
# processo multiplica esse teto, MAS o MCP guarda a sessão do conector na
# memória do processo: com dois ou mais, um pedido pode cair no processo errado.
# Por isso o padrão é 1, e subir daqui só depois de separar o MCP do portal.
CMD ["sh", "-c", "python -m app.migracoes && exec python -m uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers ${WEB_CONCURRENCY:-1} --proxy-headers --forwarded-allow-ips '*'"]
