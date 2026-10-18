FROM python:3.12.10-slim-bookworm AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /build
COPY pyproject.toml ./
COPY src ./src
RUN python -m pip install --upgrade "pip==25.0.1" && \
    python -m pip wheel --wheel-dir /wheels .

FROM python:3.12.10-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1
RUN addgroup --system --gid 10001 app && \
    adduser --system --uid 10001 --ingroup app --home /app app
WORKDIR /app
COPY --from=builder /wheels /wheels
RUN python -m pip install --no-index --find-links=/wheels hsbc-estimator-service && \
    rm -r /wheels
USER app
EXPOSE 8001
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health/ready', timeout=2)"
CMD ["uvicorn", "estimator_service.main:app", "--host", "0.0.0.0", "--port", "8001", "--no-access-log"]
