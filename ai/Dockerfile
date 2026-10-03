FROM python:3.12.12-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH=/opt/deskseed-ai/.venv/bin:$PATH

RUN groupadd --system deskseed-ai && useradd --system --gid deskseed-ai --home /opt/deskseed-ai deskseed-ai
WORKDIR /opt/deskseed-ai

COPY --from=ghcr.io/astral-sh/uv:0.11.7 /uv /usr/local/bin/uv
COPY ai/pyproject.toml ai/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY ai ./
RUN uv sync --frozen --no-dev && chown -R deskseed-ai:deskseed-ai /opt/deskseed-ai

USER deskseed-ai
EXPOSE 8090
CMD ["uvicorn", "deskseed_ai.main:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
