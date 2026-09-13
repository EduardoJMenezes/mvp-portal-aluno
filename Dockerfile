FROM node:20-bookworm-slim AS frontend-build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/backend

WORKDIR /app
COPY pyproject.toml ./
COPY backend/ ./backend/
RUN python -m pip install --no-cache-dir .

COPY --from=frontend-build /app/frontend/dist ./frontend/dist
COPY scripts/ ./scripts/

# A migração vem antes do servidor: se ela falhar, o container não sobe e o
# Railway mantém a versão anterior no ar (ver app/migracoes.py).
CMD ["sh", "-c", "python -m app.migracoes && exec python -m uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
